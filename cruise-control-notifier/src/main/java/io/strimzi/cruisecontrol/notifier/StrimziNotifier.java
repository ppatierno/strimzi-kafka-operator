/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.cruisecontrol.notifier;

import com.linkedin.cruisecontrol.detector.Anomaly;
import com.linkedin.cruisecontrol.detector.AnomalyType;
import com.linkedin.kafka.cruisecontrol.detector.BrokerFailures;
import com.linkedin.kafka.cruisecontrol.detector.DiskFailures;
import com.linkedin.kafka.cruisecontrol.detector.GoalViolations;
import com.linkedin.kafka.cruisecontrol.detector.KafkaMetricAnomaly;
import com.linkedin.kafka.cruisecontrol.detector.MaintenanceEvent;
import com.linkedin.kafka.cruisecontrol.detector.TopicAnomaly;
import com.linkedin.kafka.cruisecontrol.detector.notifier.AnomalyNotificationResult;
import com.linkedin.kafka.cruisecontrol.detector.notifier.KafkaAnomalyType;
import com.linkedin.kafka.cruisecontrol.detector.notifier.SelfHealingNotifier;
//import io.fabric8.kubernetes.api.model.ConfigMap;
//import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.rebalance.KafkaAnomaly;
import io.strimzi.api.kafka.model.rebalance.KafkaAnomalyBuilder;

import java.util.Locale;
import java.util.Map;

/**
 * Strimzi Cruise Control notifier
 */
public class StrimziNotifier extends SelfHealingNotifier {

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
        System.out.println("**** onGoalViolation anomalyId = " + goalViolations.anomalyId());
        return this.updateAction(goalViolations, super.onGoalViolation(goalViolations));
    }

    @Override
    public AnomalyNotificationResult onBrokerFailure(BrokerFailures brokerFailures) {
        System.out.println("**** onBrokerFailure anomalyId = " + brokerFailures.anomalyId());
        return this.updateAction(brokerFailures, super.onBrokerFailure(brokerFailures));
    }

    @Override
    public AnomalyNotificationResult onMetricAnomaly(KafkaMetricAnomaly kafkaMetricAnomaly) {
        System.out.println("**** onMetricAnomaly");
        return AnomalyNotificationResult.ignore();
    }

    @Override
    public AnomalyNotificationResult onTopicAnomaly(TopicAnomaly topicAnomaly) {
        System.out.println("**** onTopicAnomaly anomalyId = " + topicAnomaly.anomalyId());
        return this.updateAction(topicAnomaly, super.onTopicAnomaly(topicAnomaly));
    }

    @Override
    public AnomalyNotificationResult onMaintenanceEvent(MaintenanceEvent maintenanceEvent) {
        System.out.println("**** onMaintenanceEvent");
        return AnomalyNotificationResult.ignore();
    }

    @Override
    public AnomalyNotificationResult onDiskFailure(DiskFailures diskFailures) {
        System.out.println("**** onDiskFailure anomalyId = " + diskFailures.anomalyId());
        return this.updateAction(diskFailures, super.onDiskFailure(diskFailures));
    }

    @Override
    public Map<AnomalyType, Boolean> selfHealingEnabled() {
        System.out.println("**** selfHealingEnabled");
        return super.selfHealingEnabled();
    }

    @Override
    public boolean setSelfHealingFor(AnomalyType anomalyType, boolean b) {
        System.out.println("**** setSelfHealingFor");
        return super.setSelfHealingFor(anomalyType, b);
    }

    @Override
    public Map<AnomalyType, Float> selfHealingEnabledRatio() {
        System.out.println("**** selfHealingEnabledRatio");
        return super.selfHealingEnabledRatio();
    }

    @Override
    public long uptimeMs(long l) {
        System.out.println("**** uptimeMs");
        return super.uptimeMs(l);
    }

    @Override
    public void configure(Map<String, ?> config) {
        System.out.println("**** configure");
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
                    .endSpec()
                    .build();
            System.out.println("**** alert kafkaanomaly created");
            Crds.kafkaAnomalyOperation(this.kubernetesClient).inNamespace(this.namespace).resource(kafkaAnomaly).create();
        } else {
            kafkaAnomaly = new KafkaAnomalyBuilder(kafkaAnomaly)
                    .editSpec()
                        .withAnomalyId(anomaly.anomalyId())
                        .withAnomalyType(kafkaAnomalyType.name())
                        .withAutoFixTriggered(autoFixTriggered)
                        .withSelfHealingStartTime(selfHealingStartTime)
                    .endSpec()
                    .build();
            System.out.println("**** alert kafkaanomaly updated");
            Crds.kafkaAnomalyOperation(this.kubernetesClient).inNamespace(this.namespace).resource(kafkaAnomaly).update();
        }
        System.out.println("**** alert spec = " + kafkaAnomaly.getSpec());

        /*
        KafkaAnomalyType kafkaAnomalyType = ((KafkaAnomalyType) anomalyType);
        Map<String, String> data = Map.of(
                "anomalyId", anomaly.anomalyId(),
                "anomalyType", kafkaAnomalyType.name(),
                "autoFixTriggered", Boolean.toString(autoFixTriggered),
                "selfHealingStartTime", Long.toString(selfHealingStartTime)
        );
        System.out.println("**** alert data = " + data);

        // TODO: ConfigMap creation to be replaced with some KafkaAnomaly custom resource
        ConfigMap cm = this.kubernetesClient.configMaps().inNamespace(this.namespace).withName(resource).get();
        if (cm == null) {
            cm = new ConfigMapBuilder()
                    .withNewMetadata()
                        .withName(resource)
                    .endMetadata()
                    .withData(data)
                    .build();
            System.out.println("**** alert cm created");
            this.kubernetesClient.configMaps().inNamespace(this.namespace).resource(cm).create();
        } else {
            cm.setData(data);
            System.out.println("**** alert cm updated");
            this.kubernetesClient.configMaps().inNamespace(this.namespace).resource(cm).update();
        }
        */
    }

    private AnomalyNotificationResult updateAction(Anomaly anomaly, AnomalyNotificationResult anomalyNotificationResult) {
        KafkaAnomalyType kafkaAnomalyType = ((KafkaAnomalyType) anomaly.anomalyType());
        String resource = this.anomalyResourceName(kafkaAnomalyType);

        KafkaAnomaly kafkaAnomaly = Crds.kafkaAnomalyOperation(this.kubernetesClient).inNamespace(this.namespace).withName(resource).get();
        if (kafkaAnomaly != null) {
            kafkaAnomaly = new KafkaAnomalyBuilder(kafkaAnomaly)
                    .editSpec()
                        .withAction(anomalyNotificationResult.action().name())
                    .endSpec()
                    .build();
            Crds.kafkaAnomalyOperation(this.kubernetesClient).inNamespace(this.namespace).resource(kafkaAnomaly).update();
            System.out.println("**** updateAction spec = " + kafkaAnomaly.getSpec());
        }
        /*
        ConfigMap cm = this.kubernetesClient.configMaps().inNamespace(this.namespace).withName(resource).get();
        if (cm != null) {
            Map<String, String> data = cm.getData();
            data.put("action", anomalyNotificationResult.action().name());
            cm.setData(data);
            this.kubernetesClient.configMaps().inNamespace(this.namespace).resource(cm).update();
            System.out.println("**** updateAction data = " + data);
        }
        */

        Kafka kafka = Crds.kafkaOperation(this.kubernetesClient).inNamespace(this.namespace).withName(this.cluster).get();
        String anno = kafka.getMetadata().getAnnotations().get("strimzi.io/self-healing-pause");
        boolean isSelfHealingPause = anno != null ? Boolean.parseBoolean(anno) : false;
        System.out.println("**** isSelfHealingPause = " + isSelfHealingPause);

        return isSelfHealingPause ? AnomalyNotificationResult.ignore() : anomalyNotificationResult;
    }

    private String anomalyResourceName(KafkaAnomalyType kafkaAnomalyType) {
        return this.cluster + "-" + kafkaAnomalyType.name().toLowerCase(Locale.ENGLISH).replace("_", "-");
    }
}
