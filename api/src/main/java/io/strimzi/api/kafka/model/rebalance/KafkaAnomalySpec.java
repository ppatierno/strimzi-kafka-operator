/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model.rebalance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.api.kafka.model.common.Constants;
import io.strimzi.api.kafka.model.common.Spec;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = Constants.FABRIC8_KUBERNETES_API
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "anomalyId", "anomalyType", "goals", "autoFixTriggered", "selfHealingStartTime", "action" })
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class KafkaAnomalySpec extends Spec {

    private String anomalyId;
    private String anomalyType;
    private boolean autoFixTriggered;
    private long selfHealingStartTime;
    private String action;

    @Description("AnomalyId")
    public String getAnomalyId() {
        return anomalyId;
    }

    public void setAnomalyId(String anomalyId) {
        this.anomalyId = anomalyId;
    }

    @Description("AnomalyType")
    public String getAnomalyType() {
        return anomalyType;
    }

    public void setAnomalyType(String anomalyType) {
        this.anomalyType = anomalyType;
    }

    @Description("AutoFixTriggered")
    public boolean isAutoFixTriggered() {
        return autoFixTriggered;
    }

    public void setAutoFixTriggered(boolean autoFixTriggered) {
        this.autoFixTriggered = autoFixTriggered;
    }

    @Description("SelfHealingStartTime")
    public long getSelfHealingStartTime() {
        return selfHealingStartTime;
    }

    public void setSelfHealingStartTime(long selfHealingStartTime) {
        this.selfHealingStartTime = selfHealingStartTime;
    }

    @Description("Action")
    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
