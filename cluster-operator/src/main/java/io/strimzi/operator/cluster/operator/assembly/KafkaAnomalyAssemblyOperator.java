/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaList;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.CruiseControlResources;
import io.strimzi.api.kafka.model.rebalance.KafkaAnomaly;
import io.strimzi.api.kafka.model.rebalance.KafkaAnomalyList;
import io.strimzi.api.kafka.model.rebalance.KafkaAnomalySpec;
import io.strimzi.api.kafka.model.rebalance.KafkaAnomalyStatus;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.CruiseControl;
import io.strimzi.operator.cluster.model.cruisecontrol.CruiseControlConfiguration;
import io.strimzi.operator.cluster.operator.VertxUtil;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApi;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApiImpl;
import io.strimzi.operator.cluster.operator.resource.kubernetes.AbstractWatchableStatusedNamespacedResourceOperator;
import io.strimzi.operator.cluster.operator.resource.kubernetes.CrdOperator;
import io.strimzi.operator.cluster.operator.resource.kubernetes.SecretOperator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.Map;
import java.util.stream.StreamSupport;

import static io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApiImpl.HTTP_DEFAULT_IDLE_TIMEOUT_SECONDS;

/**
 * <p>Assembly operator for a "KafkaAnomaly" assembly, which interacts with the Cruise Control REST API</p>
 *
 */
public class KafkaAnomalyAssemblyOperator
        extends AbstractOperator<KafkaAnomaly, KafkaAnomalySpec, KafkaAnomalyStatus, AbstractWatchableStatusedNamespacedResourceOperator<KubernetesClient, KafkaAnomaly, KafkaAnomalyList, Resource<KafkaAnomaly>>> {

    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(KafkaAnomalyAssemblyOperator.class.getName());

    private final CrdOperator<KubernetesClient, KafkaAnomaly, KafkaAnomalyList> kafkaAnomalyOperator;
    private final CrdOperator<KubernetesClient, Kafka, KafkaList> kafkaOperator;
    private final SecretOperator secretOperations;
    private final int cruiseControlPort;

    /**
     * @param vertx The Vertx instance
     * @param supplier Supplies the operators for different resources
     * @param config Cluster Operator configuration
     */
    public KafkaAnomalyAssemblyOperator(Vertx vertx,
                                        ResourceOperatorSupplier supplier,
                                        ClusterOperatorConfig config) {
        this(vertx, supplier, config, CruiseControl.REST_API_PORT);
    }

    /**
     * @param vertx The Vertx instance
     * @param supplier Supplies the operators for different resources
     * @param config Cluster Operator configuration
     * @param cruiseControlPort Cruise Control server port
     */
    private KafkaAnomalyAssemblyOperator(Vertx vertx,
                                         ResourceOperatorSupplier supplier,
                                         ClusterOperatorConfig config,
                                         int cruiseControlPort) {
        super(vertx, KafkaAnomaly.RESOURCE_KIND, supplier.kafkaAnomalyOperator, supplier.metricsProvider, null);
        this.kafkaAnomalyOperator = supplier.kafkaAnomalyOperator;
        this.kafkaOperator = supplier.kafkaOperator;
        this.secretOperations = supplier.secretOperations;
        this.cruiseControlPort = cruiseControlPort;
    }

    /**
     * Provides an implementation of the Cruise Control API client
     *
     * @param ccSecret Cruise Control secret
     * @param ccApiSecret Cruise Control API secret
     * @param apiAuthEnabled if enabled, configures auth
     * @param apiSslEnabled if enabled, configures SSL
     * @return Cruise Control API client instance
     */
    public CruiseControlApi cruiseControlClientProvider(Secret ccSecret, Secret ccApiSecret,
                                                        boolean apiAuthEnabled, boolean apiSslEnabled) {
        return new CruiseControlApiImpl(HTTP_DEFAULT_IDLE_TIMEOUT_SECONDS, ccSecret, ccApiSecret, apiAuthEnabled, apiSslEnabled);
    }

    /**
     * The Cruise Control hostname to connect to
     *
     * @param clusterName the Kafka cluster resource name
     * @param clusterNamespace the namespace of the Kafka cluster
     * @return the Cruise Control hostname to connect to
     */
    protected String cruiseControlHost(String clusterName, String clusterNamespace) {
        return CruiseControlResources.qualifiedServiceName(clusterName, clusterNamespace);
    }

    @Override
    protected Future<KafkaAnomalyStatus> createOrUpdate(Reconciliation reconciliation, KafkaAnomaly resource) {
        LOGGER.infoCr(reconciliation, "**** KafkaAnomaly createOrUpdate");

        KafkaAnomalyStatus kafkaAnomalyStatus = new KafkaAnomalyStatus();

        String clusterName = resource.getMetadata().getLabels() == null ? null : resource.getMetadata().getLabels().get(Labels.STRIMZI_CLUSTER_LABEL);
        String clusterNamespace = resource.getMetadata().getNamespace();
        // TODO: check that the cluster name label was set or failing (see KafkaRebalanceAssemblyOperator)

        return kafkaOperator.getAsync(clusterNamespace, clusterName)
                .compose(kafka -> {

                    String ccSecretName =  CruiseControlResources.secretName(clusterName);
                    String ccApiSecretName =  CruiseControlResources.apiSecretName(clusterName);

                    Future<Secret> ccSecretFuture = secretOperations.getAsync(clusterNamespace, ccSecretName);
                    Future<Secret> ccApiSecretFuture = secretOperations.getAsync(clusterNamespace, ccApiSecretName);

                    return Future.join(ccSecretFuture, ccApiSecretFuture)
                            .compose(compositeFuture -> {
                                Secret ccSecret = compositeFuture.resultAt(0);
                                if (ccSecret == null) {
                                    return Future.failedFuture(Util.missingSecretException(clusterNamespace, ccSecretName));
                                }

                                Secret ccApiSecret = compositeFuture.resultAt(1);
                                if (ccApiSecret == null) {
                                    return Future.failedFuture(Util.missingSecretException(clusterNamespace, ccApiSecretName));
                                }

                                CruiseControlConfiguration ccConfig = new CruiseControlConfiguration(reconciliation, kafka.getSpec().getCruiseControl().getConfig().entrySet(), Map.of());
                                boolean apiAuthEnabled = ccConfig.isApiAuthEnabled();
                                boolean apiSslEnabled = ccConfig.isApiSslEnabled();
                                CruiseControlApi apiClient = cruiseControlClientProvider(ccSecret, ccApiSecret, apiAuthEnabled, apiSslEnabled);

                                return this.kafkaAnomalyOperator.getAsync(resource.getMetadata().getNamespace(), resource.getMetadata().getName())
                                        .compose(kafkaAnomaly -> {
                                            LOGGER.infoCr(reconciliation, "**** KafkaAnomaly spec = {}", kafkaAnomaly.getSpec());
                                            if (kafkaAnomaly.getSpec().getAction() == null ||
                                                    "IGNORE".equals(kafkaAnomaly.getSpec().getAction()) ||
                                                    "CHECK".equals(kafkaAnomaly.getSpec().getAction())) {
                                                return Future.succeededFuture(kafkaAnomalyStatus);
                                            } else {
                                                // query anomaly detector and then executor endpoints
                                                return VertxUtil.completableFutureToVertxFuture(apiClient.getCruiseControlState(reconciliation, cruiseControlHost(clusterName, clusterNamespace), cruiseControlPort, false, "anomaly_detector"))
                                                        .compose(cruiseControlResponse -> {
                                                            JsonNode jsonAnomalyDetectorState = this.getAnomalyStatus(kafkaAnomaly, cruiseControlResponse.getJson().get("AnomalyDetectorState"));
                                                            LOGGER.infoCr(reconciliation, "AnomalyDetectorState = {}", jsonAnomalyDetectorState);
                                                            String anomalyStatus = jsonAnomalyDetectorState.asText("status");
                                                            if ("FIX_STARTED".equals(anomalyStatus)) {
                                                                LOGGER.infoCr(reconciliation, "**** FIX_STARTED going to call executor endpoint");
                                                                return VertxUtil.completableFutureToVertxFuture(apiClient.getCruiseControlState(reconciliation, cruiseControlHost(clusterName, clusterNamespace), cruiseControlPort, false, "executor"))
                                                                        .compose(cruiseControlResponse1 -> {
                                                                            JsonNode jsonExecutorState = cruiseControlResponse1.getJson().get("ExecutorState");
                                                                            LOGGER.infoCr(reconciliation, "ExecutorState = {}", jsonExecutorState);
                                                                            // check the ongoing task is related to the anomaly
                                                                            if (kafkaAnomaly.getSpec().getAnomalyId().equals(jsonExecutorState.asText("triggeredSelfHealingTaskId"))) {
                                                                                kafkaAnomalyStatus.setProgress(jsonAnomalyDetectorState.asText("status") + " : " +
                                                                                        jsonExecutorState.asText("state"));
                                                                            } else {
                                                                                kafkaAnomalyStatus.setProgress("FIXED");
                                                                            }
                                                                            return Future.succeededFuture(kafkaAnomalyStatus);
                                                                        });
                                                            } else {
                                                                kafkaAnomalyStatus.setProgress(jsonAnomalyDetectorState.asText("status"));
                                                                return Future.succeededFuture(kafkaAnomalyStatus);
                                                            }
                                                        });
                                            }
                                        });
                            });
                });
    }

    private JsonNode getAnomalyStatus(KafkaAnomaly kafkaAnomaly, JsonNode anomalyDetectorState) {
        String anomalies = null;
        switch (kafkaAnomaly.getSpec().getAnomalyType()) {
            case "BROKER_FAILURE" -> anomalies = "recentBrokerFailures";
            case "DISK_FAILURE" -> anomalies = "recentDiskFailures";
            case "GOAL_VIOLATION" -> anomalies = "recentGoalViolations";
            case "TOPIC_ANOMALY" -> anomalies = "recentTopicAnomalies";
            case "METRIC_ANOMALY" -> anomalies = "recentMetricAnomalies";
        }

        JsonNode json = StreamSupport.stream(anomalyDetectorState.get(anomalies).spliterator(), false)
                .filter(anomaly -> {
                    return anomaly.asText("anomalyId").equals(kafkaAnomaly.getSpec().getAnomalyId());
                })
                .findFirst()
                .get();
        return json;
    }

    @Override
    protected Future<Boolean> delete(Reconciliation reconciliation) {
        LOGGER.infoCr(reconciliation, "**** KafkaAnomaly delete");
        return Future.succeededFuture(Boolean.TRUE);
    }

    @Override
    protected KafkaAnomalyStatus createStatus(KafkaAnomaly cr) {
        return new KafkaAnomalyStatus();
    }
}
