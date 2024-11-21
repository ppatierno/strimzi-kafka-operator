/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model.rebalance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.strimzi.api.kafka.model.common.Constants;
import io.strimzi.api.kafka.model.common.UnknownPropertyPreserving;
import io.strimzi.crdgenerator.annotations.Crd;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.BuildableReference;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonDeserialize
@Crd(
    spec = @Crd.Spec(
        names = @Crd.Spec.Names(
            kind = KafkaAnomaly.RESOURCE_KIND,
            plural = KafkaAnomaly.RESOURCE_PLURAL,
            shortNames = {KafkaAnomaly.SHORT_NAME},
            categories = {Constants.STRIMZI_CATEGORY}
        ),
        group = KafkaAnomaly.RESOURCE_GROUP,
        scope = KafkaAnomaly.SCOPE,
        versions = {
            @Crd.Spec.Version(name = KafkaAnomaly.V1BETA2, served = true, storage = false),
            @Crd.Spec.Version(name = KafkaAnomaly.V1ALPHA1, served = true, storage = true)
        },
        subresources = @Crd.Spec.Subresources(
            status = @Crd.Spec.Subresources.Status()
        ),
        additionalPrinterColumns = {
            @Crd.Spec.AdditionalPrinterColumn(
                name = "ID",
                description = "Anomaly ID",
                jsonPath = ".spec.anomalyId",
                type = "string"),
            @Crd.Spec.AdditionalPrinterColumn(
                name = "Type",
                description = "Anomaly type",
                jsonPath = ".spec.anomalyType",
                type = "string"),
            @Crd.Spec.AdditionalPrinterColumn(
                name = "Autofix",
                description = "Autofix triggered",
                jsonPath = ".spec.autoFixTriggered",
                type = "string"),
            @Crd.Spec.AdditionalPrinterColumn(
                name = "Time",
                description = "Self-healing start time",
                jsonPath = ".spec.selfHealingStartTime",
                type = "integer"),
            @Crd.Spec.AdditionalPrinterColumn(
                name = "Action",
                description = "Action result",
                jsonPath = ".spec.action",
                type = "string"),
            @Crd.Spec.AdditionalPrinterColumn(
                name = "Status",
                description = "Status progress",
                jsonPath = ".status.progress",
                type = "string")
        }
    )
)
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = Constants.FABRIC8_KUBERNETES_API,
        refs = {@BuildableReference(CustomResource.class)}
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"apiVersion", "kind", "metadata", "spec", "status"})
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Version(Constants.V1BETA2)
@Group(Constants.RESOURCE_GROUP_NAME)
public class KafkaAnomaly extends CustomResource<KafkaAnomalySpec, KafkaAnomalyStatus> implements Namespaced, UnknownPropertyPreserving {
    private static final long serialVersionUID = 1L;

    public static final String SCOPE = "Namespaced";
    public static final String V1BETA2 = Constants.V1BETA2;
    public static final String V1ALPHA1 = Constants.V1ALPHA1;
    public static final String CONSUMED_VERSION = V1BETA2;
    public static final List<String> VERSIONS = List.of(V1BETA2, V1ALPHA1);
    public static final String RESOURCE_KIND = "KafkaAnomaly";
    public static final String RESOURCE_LIST_KIND = RESOURCE_KIND + "List";
    public static final String RESOURCE_GROUP = Constants.RESOURCE_GROUP_NAME;
    public static final String RESOURCE_PLURAL = "kafkaanomalies";
    public static final String RESOURCE_SINGULAR = "kafkaanomaly";
    public static final String CRD_NAME = RESOURCE_PLURAL + "." + RESOURCE_GROUP;
    public static final String SHORT_NAME = "ka";
    public static final List<String> RESOURCE_SHORTNAMES = List.of(SHORT_NAME);

    private Map<String, Object> additionalProperties;

    // Added to avoid duplication during Json serialization
    private String apiVersion;
    private String kind;

    public KafkaAnomaly() {
        super();
    }

    public KafkaAnomaly(KafkaAnomalySpec spec, KafkaAnomalyStatus status) {
        super();
        this.spec = spec;
        this.status = status;
    }

    @Override
    @Description("The specification of the Kafka anomaly.")
    public KafkaAnomalySpec getSpec() {
        return super.getSpec();
    }

    @Override
    @Description("The status of the Kafka anomaly.")
    public KafkaAnomalyStatus getStatus() {
        return super.getStatus();
    }

    @Override
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties != null ? this.additionalProperties : Map.of();
    }

    @Override
    public void setAdditionalProperty(String name, Object value) {
        if (this.additionalProperties == null) {
            this.additionalProperties = new HashMap<>(2);
        }
        this.additionalProperties.put(name, value);
    }
}
