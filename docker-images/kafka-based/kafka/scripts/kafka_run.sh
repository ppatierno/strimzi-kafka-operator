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

# Import certificates into keystore and truststore
./kafka_tls_prepare_certificates.sh

# Generate and print the config file
echo "Starting Kafka with configuration:"
tee /tmp/strimzi.properties < "$KAFKA_HOME/custom-config/server.config" | sed -e 's/sasl.jaas.config=.*/sasl.jaas.config=[hidden]/g' -e 's/password=.*/password=[hidden]/g'
echo ""

# Configure heap based on the available resources if needed
. ./dynamic_resources.sh

# Format the KRaft storage
STRIMZI_CLUSTER_ID=$(cat "$KAFKA_HOME/custom-config/cluster.id")
METADATA_VERSION=$(cat "$KAFKA_HOME/custom-config/metadata.version")
INITIAL_CONTROLLERS=$(cat "$KAFKA_HOME/custom-config/initial.controllers" 2>/dev/null || true)

echo "Making sure the Kraft storage is formatted with cluster ID $STRIMZI_CLUSTER_ID and metadata version $METADATA_VERSION"
echo "Initial controllers: $INITIAL_CONTROLLERS"

# Using "=" to assign arguments for the Kafka storage tool to avoid issues if the generated
# cluster ID starts with a "-". See https://issues.apache.org/jira/browse/KAFKA-15754.
# The -g option makes sure the tool will ignore any volumes that are already formatted.

if [ -z "$INITIAL_CONTROLLERS" ]; then
  # Not using dynamic quorum - use standard formatting
  echo "Not using dynamic quorum, formatting with standard options"
  ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g
else
  # Using dynamic quorum - check if this node is a controller
  PROCESS_ROLES=$(grep -Po '(?<=^process.roles=).+' /tmp/strimzi.properties || echo "")

  if [[ "$PROCESS_ROLES" =~ "controller" ]]; then
    # Check if it's an initial controller (new cluster) or scale-up
    # Extract node IDs from INITIAL_CONTROLLERS (format: id@host:port:directory-id,...)
    INITIAL_CONTROLLER_IDS=$(echo "$INITIAL_CONTROLLERS" | grep -oP '\d+(?=@)' | tr '\n' ' ')
    echo "Initial controller IDs: $INITIAL_CONTROLLER_IDS"

    if echo "$INITIAL_CONTROLLER_IDS" | grep -qw "$STRIMZI_BROKER_ID"; then
      # This node is an initial controller, so using -I (works for both new cluster and restart)
      echo "Initial controller, formatting with -I"
      ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -I="$INITIAL_CONTROLLERS"
    else
      # This node is NOT an initial controller, so using -N for scale-up
      echo "Scaling up controller, formatting with -N"
      ./bin/kafka-storage.sh format -t="$STRIMZI_CLUSTER_ID" -r="$METADATA_VERSION" -c=/tmp/strimzi.properties -g -N
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
CURRENT_KRAFT_METADATA_LOG_DIR=$(ls -d /var/lib/kafka/data-*/kafka-log"$STRIMZI_BROKER_ID"/__cluster_metadata-0 2> /dev/null || true)
if [[ -d "$CURRENT_KRAFT_METADATA_LOG_DIR" && "$CURRENT_KRAFT_METADATA_LOG_DIR" != $KRAFT_METADATA_LOG_DIR* ]]; then
  echo "The desired KRaft metadata log directory ($KRAFT_METADATA_LOG_DIR) and the current one ($CURRENT_KRAFT_METADATA_LOG_DIR) differ. The current directory will be deleted."
  rm -rf "$CURRENT_KRAFT_METADATA_LOG_DIR"
else
  # remove quorum-state file so that we won't enter voter not match error after scaling up/down
  if [ -f "$KRAFT_METADATA_LOG_DIR/__cluster_metadata-0/quorum-state" ]; then
    echo "Removing quorum-state file"
    rm -f "$KRAFT_METADATA_LOG_DIR/__cluster_metadata-0/quorum-state"
  fi
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
EOF
echo ""

KAFKA_OPTS="${KAFKA_OPTS} -javaagent:$(ls "$KAFKA_HOME"/libs/kafka-agent*.jar)=/tmp/kafka-agent.properties"
export KAFKA_OPTS

# Configure Garbage Collection logging
. ./set_kafka_gc_options.sh

set -x

# starting Kafka server with final configuration
exec /usr/bin/tini -w -e 143 -- "${KAFKA_HOME}/bin/kafka-server-start.sh" /tmp/strimzi.properties
