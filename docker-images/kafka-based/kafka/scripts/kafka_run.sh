#!/usr/bin/env bash
set -e
set +x

# Clean-up /tmp directory from files which might have remained from previous container restart
# We ignore any errors which might be caused by files injected by different agents which we do not have the rights to delete
rm -rfv /tmp/* || true

STRIMZI_BROKER_ID=$(hostname | awk -F'-' '{print $NF}')
export STRIMZI_BROKER_ID
echo "STRIMZI_BROKER_ID=${STRIMZI_BROKER_ID}"

# Disable Kafka's GC logging (which logs to a file)...
export GC_LOG_ENABLED="false"

if [ -z "$KAFKA_LOG4J_OPTS" ]; then
  export KAFKA_LOG4J_OPTS="-Dlog4j2.configurationFile=$KAFKA_HOME/custom-config/log4j2.properties"
fi

. ./set_kafka_jmx_options.sh "${STRIMZI_JMX_ENABLED}" "${STRIMZI_JMX_USERNAME}" "${STRIMZI_JMX_PASSWORD}"

if [ -n "$STRIMZI_JAVA_SYSTEM_PROPERTIES" ]; then
    export KAFKA_OPTS="${KAFKA_OPTS} ${STRIMZI_JAVA_SYSTEM_PROPERTIES}"
fi

# Disable FIPS if needed
if [ "$FIPS_MODE" = "disabled" ]; then
    export KAFKA_OPTS="${KAFKA_OPTS} -Dcom.redhat.fips=false"
fi

# Enable Prometheus JMX Exporter as Java agent
if [ "$KAFKA_JMX_EXPORTER_ENABLED" = "true" ]; then
  KAFKA_OPTS="${KAFKA_OPTS} -javaagent:$(ls "$JMX_EXPORTER_HOME"/jmx_prometheus_javaagent*.jar)=9404:$KAFKA_HOME/custom-config/metrics-config.json"
  export KAFKA_OPTS
fi

# We don't need LOG_DIR because we write no log files, but setting it to a
# directory avoids trying to create it (and logging a permission denied error)
export LOG_DIR="$KAFKA_HOME"

# Generate temporary keystore password
CERTS_STORE_PASSWORD=$(< /dev/urandom tr -dc _A-Z-a-z-0-9 | head -c32)
export CERTS_STORE_PASSWORD

mkdir -p /tmp/kafka

# Generate and print the config file
echo "Starting Kafka with configuration:"
tee /tmp/strimzi.properties < "$KAFKA_HOME/custom-config/server.config" | sed -e 's/sasl.jaas.config=.*/sasl.jaas.config=[hidden]/g' -e 's/password=.*/password=[hidden]/g'
echo ""

# Configure heap based on the available resources if needed
. ./dynamic_resources.sh

# Format the KRaft storage
STRIMZI_CLUSTER_ID=$(cat "$KAFKA_HOME/custom-config/cluster.id")
METADATA_VERSION=$(cat "$KAFKA_HOME/custom-config/metadata.version")
CONTROLLERS=$(cat "$KAFKA_HOME/custom-config/controllers" 2>/dev/null || true)

echo "Making sure the Kraft storage is formatted with cluster ID $STRIMZI_CLUSTER_ID and metadata version $METADATA_VERSION"
echo "Controllers: $CONTROLLERS"

# Using "=" to assign arguments for the Kafka storage tool to avoid issues if the generated
# cluster ID starts with a "-". See https://issues.apache.org/jira/browse/KAFKA-15754.
# The -g option makes sure the tool will ignore any volumes that are already formatted.

if [ -z "$CONTROLLERS" ]; then
  # Not using dynamic quorum - use standard formatting
  echo "Not using dynamic quorum, formatting with standard options"
  ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g
else
  # Using dynamic quorum - check if this node is a controller
  PROCESS_ROLES=$(grep -Po '(?<=^process.roles=).+' /tmp/strimzi.properties || echo "")

  if [[ "$PROCESS_ROLES" =~ "controller" ]]; then
    # Get the configured metadata log directory
    KRAFT_METADATA_LOG_DIR=$(grep "metadata\.log\.dir=" /tmp/strimzi.properties | sed "s/metadata\.log\.dir=*//")

    # Check if metadata exists on a DIFFERENT disk (disk change scenario)
    DISK_CHANGED=false
    for CURRENT_DIR in $(ls -d /var/lib/kafka/data-*/kafka-log"$STRIMZI_BROKER_ID"/__cluster_metadata-0 2> /dev/null || true); do
      # Check if it's on a different disk than the configured one
      if [[ "$CURRENT_DIR" != $KRAFT_METADATA_LOG_DIR* ]]; then
        echo "Metadata exists on different disk - disk change detected"
        DISK_CHANGED=true
        break
      fi
    done

    # OLD CODE - Commented out to preserve history
    # This logic incorrectly used -I for new controllers during scale-up
    # Check if it's a controller (new cluster) or scale-up
    # Extract node IDs from CONTROLLERS (format: id@host:port:directory-id,...)
    #CONTROLLER_IDS=$(echo "$CONTROLLERS" | grep -oP '\d+(?=@)' | tr '\n' ' ')
    #echo "Controller IDs: $CONTROLLER_IDS"
    #
    #if echo "$CONTROLLER_IDS" | grep -qw "$STRIMZI_BROKER_ID"; then
    #  if [ "$DISK_CHANGED" = true ]; then
    #    # Disk changed - use -N to generate new random directory ID
    #    echo "Controller with disk change, formatting with -N (new random directory ID)"
    #    ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -N
    #  else
    #    # This node is a controller with same disk, so using -I (works for both new cluster and restart)
    #    echo "Controller, formatting with -I"
    #    ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -I="$CONTROLLERS"
    #  fi
    #else
    #  # This node is NOT a controller, so using -N for scale-up
    #  echo "Scaling up controller, formatting with -N"
    #  ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -N
    #fi

    # NEW CODE - Proper -I vs -N logic
    # Determine if this is a new cluster or existing cluster operation
    #
    # The cluster.new marker is set by the Strimzi operator based on status.clusterId:
    #   "true"  = Brand new cluster, never bootstrapped before
    #   "false" = Existing cluster (already bootstrapped)
    #
    # The controllers string contains controllers with their directory IDs.
    # Logic:
    #   - New cluster: All controllers use -I to bootstrap with specified directory IDs
    #   - Existing cluster:
    #     - Controller in controllers list -> use -I to preserve directory ID
    #       (handles rolling restart, PVC replacement, disaster recovery)
    #     - Controller NOT in controllers list -> use -N for scale-up
    #       (Kafka generates new random directory ID)
    #
    # The -g (--ignore-formatted) flag ensures already-formatted storage is not reformatted.
    IS_NEW_CLUSTER=$(cat "$KAFKA_HOME/custom-config/cluster.new" 2>/dev/null || echo "false")
    echo "New cluster: $IS_NEW_CLUSTER"

    if [ "$IS_NEW_CLUSTER" = "true" ]; then
      # Brand new cluster: use -I to bootstrap the initial controller quorum
      if [ "$DISK_CHANGED" = true ]; then
        # Disk changed during initial bootstrap, use -N to generate new random directory ID
        # TODO: should this be removed? is it ever possible?
        echo "New cluster with disk change, formatting with -N (new random directory ID)"
        ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -N
      else
        # Normal new cluster bootstrap, use -I with controllers list
        echo "New cluster initial bootstrap, formatting with -I"
        ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -I="$CONTROLLERS"
      fi
    else
      # Existing cluster: check if this controller is in the controllers list
      # Extract node IDs from CONTROLLERS (format: id@host:port:directory-id,...)
      CONTROLLER_IDS=$(echo "$CONTROLLERS" | grep -oP '\d+(?=@)' | tr '\n' ' ')
      echo "Controllers in controllers list: $CONTROLLER_IDS"

      if echo "$CONTROLLER_IDS" | grep -qw "$STRIMZI_BROKER_ID"; then
        # This controller is in the initial.controllers list
        if [ "$DISK_CHANGED" = true ]; then
          # Disk changed, use -N to generate new random directory ID
          echo "Existing cluster, disk changed, formatting with -N (new random directory ID)"
          ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -N
        else
          # Use -I to format with the directory ID from controllers
          # This handles: rolling restart, PVC replacement, disaster recovery
          # The -g flag will skip if already formatted
          echo "Existing cluster, controller in list, formatting with -I to preserve directory ID"
          ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -I="$CONTROLLERS"
        fi
      else
        # This controller is NOT in the controllers list
        # This is a new controller being added during scale-up
        echo "New controller being added to existing cluster, formatting with -N"
        ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -N
      fi
    fi
  else
    # This is a broker with dynamic quorum, always use -N
    echo "Broker with dynamic quorum, formatting with -N"
    ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -N
  fi
fi
echo "KRaft storage formatting is done"

# Manage the metadata log file changes
KRAFT_METADATA_LOG_DIR=$(grep "metadata\.log\.dir=" /tmp/strimzi.properties | sed "s/metadata\.log\.dir=*//")
echo "KRAFT_METADATA_LOG_DIR= $KRAFT_METADATA_LOG_DIR"

# Find all existing metadata directories and delete any that don't match the configured one
# When using -I on formatting, the __cluster_metadata-0 directory is created immediately so there will be the new and the old one which needs to be deleted
for CURRENT_DIR in $(ls -d /var/lib/kafka/data-*/kafka-log"$STRIMZI_BROKER_ID"/__cluster_metadata-0 2> /dev/null || true); do
  echo "Found metadata directory: $CURRENT_DIR"
  if [[ "$CURRENT_DIR" != $KRAFT_METADATA_LOG_DIR* ]]; then
    echo "The desired KRaft metadata log directory ($KRAFT_METADATA_LOG_DIR) and the current one ($CURRENT_DIR) differ. The current directory will be deleted."
    rm -rf "$CURRENT_DIR"
  fi
done

# Remove quorum-state file so that we won't enter voter not match error after scaling up/down
if [ -f "$KRAFT_METADATA_LOG_DIR/__cluster_metadata-0/quorum-state" ]; then
  echo "Removing quorum-state file"
  rm -f "$KRAFT_METADATA_LOG_DIR/__cluster_metadata-0/quorum-state"
fi

# Generate the Kafka Agent configuration file
echo ""
echo "Preparing Kafka Agent configuration"
rm -f /tmp/kafka-agent.properties
NAMESPACE=$(cat /var/run/secrets/kubernetes.io/serviceaccount/namespace)
cat <<EOF > /tmp/kafka-agent.properties
sslTrustStoreSecretName=${KAFKA_CLUSTER_NAME}-cluster-ca-cert
sslKeyStoreSecretName=${HOSTNAME}
namespace=${NAMESPACE}
kraftMetadataLogDir=${KRAFT_METADATA_LOG_DIR}
EOF
echo ""

KAFKA_OPTS="${KAFKA_OPTS} -javaagent:$(ls "$KAFKA_HOME"/libs/kafka-agent*.jar)=/tmp/kafka-agent.properties"
export KAFKA_OPTS

# Configure Garbage Collection logging
. ./set_kafka_gc_options.sh

set -x

# starting Kafka server with final configuration
exec /usr/bin/tini -w -e 143 -- "${KAFKA_HOME}/bin/kafka-server-start.sh" /tmp/strimzi.properties
