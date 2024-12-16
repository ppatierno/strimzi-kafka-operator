/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.cruisecontrol.notifier;

import com.linkedin.cruisecontrol.detector.Anomaly;
import com.linkedin.cruisecontrol.detector.AnomalyType;
import com.linkedin.cruisecontrol.detector.metricanomaly.MetricAnomaly;
import com.linkedin.kafka.cruisecontrol.detector.BrokerFailures;
import com.linkedin.kafka.cruisecontrol.detector.DiskFailures;
import com.linkedin.kafka.cruisecontrol.detector.GoalViolations;
import com.linkedin.kafka.cruisecontrol.detector.KafkaMetricAnomaly;
import com.linkedin.kafka.cruisecontrol.detector.MaintenanceEvent;
import com.linkedin.kafka.cruisecontrol.detector.TopicAnomaly;
import com.linkedin.kafka.cruisecontrol.detector.notifier.AnomalyNotificationResult;
import com.linkedin.kafka.cruisecontrol.detector.notifier.KafkaAnomalyType;
import com.linkedin.kafka.cruisecontrol.detector.notifier.SelfHealingNotifier;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.strimzi.api.ResourceAnnotations;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.rebalance.KafkaAnomaly;
import io.strimzi.api.kafka.model.rebalance.KafkaAnomalyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * Strimzi Cruise Control notifier
 */
public class StrimziNotifier extends SelfHealingNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(StrimziNotifier.class);

    /**
     * Default namespace for the notifier to look at Strimzi resources
     */
    public static final String DEFAULT_NAMESPACE = "default";

    /**
     * Configuration parameter to define the namespace where the notifier looks for Strimzi resources
     */
    public static final String NAMESPACE = "strimzi.notifier.namespace";

    /**
     * Configuration parameter to define the Apache Kafka cluster related to the Cruise Control instance
     */
    public static final String CLUSTER = "strimzi.notifier.cluster";

    private final KubernetesClient kubernetesClient;
    private String namespace;
    private String cluster;

    /**
     * Constructor
     */
    public StrimziNotifier() {
        super();
        this.kubernetesClient = new KubernetesClientBuilder().build();
    }

    @Override
    public AnomalyNotificationResult onGoalViolation(GoalViolations goalViolations) {
        LOGGER.info("**** onGoalViolation anomalyId = {}", goalViolations.anomalyId());
        return this.updateAction(goalViolations, super.onGoalViolation(goalViolations));
    }

    @Override
    public AnomalyNotificationResult onBrokerFailure(BrokerFailures brokerFailures) {
        LOGGER.info("**** onBrokerFailure anomalyId = {}", brokerFailures.anomalyId());
        return this.updateAction(brokerFailures, super.onBrokerFailure(brokerFailures));
    }

    @Override
    public AnomalyNotificationResult onMetricAnomaly(KafkaMetricAnomaly kafkaMetricAnomaly) {
        LOGGER.info("**** onMetricAnomaly");
        return AnomalyNotificationResult.ignore();
    }

    @Override
    public AnomalyNotificationResult onTopicAnomaly(TopicAnomaly topicAnomaly) {
        LOGGER.info("**** onTopicAnomaly anomalyId = {}", topicAnomaly.anomalyId());
        return this.updateAction(topicAnomaly, super.onTopicAnomaly(topicAnomaly));
    }

    @Override
    public AnomalyNotificationResult onMaintenanceEvent(MaintenanceEvent maintenanceEvent) {
        LOGGER.info("**** onMaintenanceEvent");
        return AnomalyNotificationResult.ignore();
    }

    @Override
    public AnomalyNotificationResult onDiskFailure(DiskFailures diskFailures) {
        LOGGER.info("**** onDiskFailure anomalyId = {}", diskFailures.anomalyId());
        return this.updateAction(diskFailures, super.onDiskFailure(diskFailures));
    }

    @Override
    public Map<AnomalyType, Boolean> selfHealingEnabled() {
        //LOGGER.info("**** selfHealingEnabled");
        return super.selfHealingEnabled();
    }

    @Override
    public boolean setSelfHealingFor(AnomalyType anomalyType, boolean b) {
        //LOGGER.info("**** setSelfHealingFor");
        return super.setSelfHealingFor(anomalyType, b);
    }

    @Override
    public Map<AnomalyType, Float> selfHealingEnabledRatio() {
        //LOGGER.info("**** selfHealingEnabledRatio");
        return super.selfHealingEnabledRatio();
    }

    @Override
    public long uptimeMs(long l) {
        //LOGGER.info("**** uptimeMs");
        return super.uptimeMs(l);
    }

    @Override
    public void configure(Map<String, ?> config) {
        LOGGER.info("**** configure");
        this.namespace = config.get(NAMESPACE) == null ? DEFAULT_NAMESPACE : (String) config.get(NAMESPACE);
        if (config.get(CLUSTER) == null) {
            throw new IllegalArgumentException("No cluster specified");
        }
        this.cluster = (String) config.get(CLUSTER);
        super.configure(config);
    }

    @Override
    public void alert(Anomaly anomaly, boolean autoFixTriggered, long selfHealingStartTime, AnomalyType anomalyType) {
        super.alert(anomaly, autoFixTriggered, selfHealingStartTime, anomalyType);

        KafkaAnomalyType kafkaAnomalyType = ((KafkaAnomalyType) anomalyType);
        String resource = this.anomalyResourceName(kafkaAnomalyType);

        KafkaAnomaly kafkaAnomaly = Crds.kafkaAnomalyOperation(this.kubernetesClient).inNamespace(this.namespace).withName(resource).get();
        if (kafkaAnomaly == null) {
            kafkaAnomaly = new KafkaAnomalyBuilder()
                    .withNewMetadata()
                        .withName(resource)
                        .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, this.cluster))
                    .endMetadata()
                    .withNewSpec()
                        .withAnomalyId(anomaly.anomalyId())
                        .withAnomalyType(kafkaAnomalyType.name())
                        .withAutoFixTriggered(autoFixTriggered)
                        .withSelfHealingStartTime(selfHealingStartTime)
                        .withDetails(this.anomalyDetails(anomaly))
                    .endSpec()
                    .build();
            LOGGER.info("**** alert kafkaanomaly created");
            Crds.kafkaAnomalyOperation(this.kubernetesClient).inNamespace(this.namespace).resource(kafkaAnomaly).create();
        } else {
            kafkaAnomaly = new KafkaAnomalyBuilder(kafkaAnomaly)
                    .editSpec()
                        .withAnomalyId(anomaly.anomalyId())
                        .withAnomalyType(kafkaAnomalyType.name())
                        .withAutoFixTriggered(autoFixTriggered)
                        .withSelfHealingStartTime(selfHealingStartTime)
                        .withDetails(this.anomalyDetails(anomaly))
                    .endSpec()
                    .build();
            LOGGER.info("**** alert kafkaanomaly updated");
            Crds.kafkaAnomalyOperation(this.kubernetesClient).inNamespace(this.namespace).resource(kafkaAnomaly).update();
        }
        LOGGER.info("**** alert spec = {}", kafkaAnomaly.getSpec());
    }

    private AnomalyNotificationResult updateAction(Anomaly anomaly, AnomalyNotificationResult anomalyNotificationResult) {
        Kafka kafka = Crds.kafkaOperation(this.kubernetesClient).inNamespace(this.namespace).withName(this.cluster).get();
        String anno = kafka.getMetadata().getAnnotations().get(ResourceAnnotations.ANNO_STRIMZI_IO_SELF_HEALING_PAUSED);
        boolean isSelfHealingPause = anno != null ? Boolean.parseBoolean(anno) : false;
        LOGGER.info("**** isSelfHealingPause = {}", isSelfHealingPause);

        KafkaAnomalyType kafkaAnomalyType = ((KafkaAnomalyType) anomaly.anomalyType());
        String resource = this.anomalyResourceName(kafkaAnomalyType);

        KafkaAnomaly kafkaAnomaly = Crds.kafkaAnomalyOperation(this.kubernetesClient).inNamespace(this.namespace).withName(resource).get();
        if (kafkaAnomaly != null) {
            kafkaAnomaly = new KafkaAnomalyBuilder(kafkaAnomaly)
                    .editSpec()
                        .withAction(isSelfHealingPause ? AnomalyNotificationResult.ignore().action().name() : anomalyNotificationResult.action().name())
                    .endSpec()
                    .build();
            Crds.kafkaAnomalyOperation(this.kubernetesClient).inNamespace(this.namespace).resource(kafkaAnomaly).update();
            LOGGER.info("**** updateAction spec = {}", kafkaAnomaly.getSpec());
        }

        return isSelfHealingPause ? AnomalyNotificationResult.ignore() : anomalyNotificationResult;
    }

    private String anomalyResourceName(KafkaAnomalyType kafkaAnomalyType) {
        return this.cluster + "-" + kafkaAnomalyType.name().toLowerCase(Locale.ENGLISH).replace("_", "-");
    }

    private String anomalyDetails(Anomaly anomaly) {
        KafkaAnomalyType kafkaAnomalyType = ((KafkaAnomalyType) anomaly.anomalyType());
        String details;
        switch (kafkaAnomalyType) {
            case BROKER_FAILURE -> details = ((BrokerFailures) anomaly).failedBrokers().toString();
            case DISK_FAILURE -> details = ((DiskFailures) anomaly).failedDisks().toString();
            case TOPIC_ANOMALY -> details = ((TopicAnomaly) anomaly).toString();
            case METRIC_ANOMALY -> details = ((MetricAnomaly) anomaly).description();
            case GOAL_VIOLATION -> details = ((GoalViolations) anomaly).violatedGoalsByFixability().toString();
            default -> details = anomaly.toString();
        }
        return details;
    }
}
