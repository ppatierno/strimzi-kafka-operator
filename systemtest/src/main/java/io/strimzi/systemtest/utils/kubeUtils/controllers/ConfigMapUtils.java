/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kubeUtils.controllers;

import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.TestConstants;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class ConfigMapUtils {

    private static final Logger LOGGER = LogManager.getLogger(ConfigMapUtils.class);

    private ConfigMapUtils() { }

    public static void waitForConfigMapLabelsChange(String namespaceName, String configMapName, Map<String, String> labels) {
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            boolean isK8sTag = entry.getKey().equals("controller-revision-hash") || entry.getKey().equals("statefulset.kubernetes.io/pod-name");
            boolean isStrimziTag = entry.getKey().startsWith(Labels.STRIMZI_DOMAIN);
            // ignoring strimzi.io and k8s labels
            if (!(isStrimziTag || isK8sTag)) {
                LOGGER.info("Waiting for ConfigMap: {}/{} label to change {} -> {}", namespaceName, configMapName, entry.getKey(), entry.getValue());
                TestUtils.waitFor("ConfigMap label to change " + entry.getKey() + " -> " + entry.getValue(), TestConstants.POLL_INTERVAL_FOR_RESOURCE_READINESS,
                    TestConstants.GLOBAL_TIMEOUT, () ->
                        kubeClient(namespaceName).getConfigMap(namespaceName, configMapName).getMetadata().getLabels().get(entry.getKey()).equals(entry.getValue())
                );
            }
        }
    }

    /**
     * Waits for ConfigMap with specified name and in specified Namespace will be created.
     *
     * @param namespaceName     name of the Namespace where the ConfigMap should be created
     * @param configMapName     name of the ConfigMap that should be created
     */
    public static void waitForCreationOfConfigMap(String namespaceName, String configMapName) {
        LOGGER.info("Waiting for ConfigMap: {}/{} to be created", namespaceName, configMapName);
        TestUtils.waitFor(String.format("ConfigMap: %s/%s to be created", namespaceName, configMapName),
            TestConstants.POLL_INTERVAL_FOR_RESOURCE_READINESS,
            TestConstants.GLOBAL_TIMEOUT,
            () -> kubeClient().getConfigMap(namespaceName, configMapName) != null
        );
    }
}
