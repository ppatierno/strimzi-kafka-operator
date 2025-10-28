/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.strimzi.api.kafka.model.kafka.KafkaResources;
import io.strimzi.operator.cluster.model.DnsNameGenerator;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.NodeRef;
import io.strimzi.operator.cluster.operator.VertxUtil;
import io.strimzi.operator.common.AdminClientProvider;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.auth.PemAuthIdentity;
import io.strimzi.operator.common.auth.PemTrustSet;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.QuorumInfo;
import org.apache.kafka.clients.admin.RaftVoterEndpoint;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Contains utility methods for unregistering KRaft nodes from a Kafka cluster after scale-down
 */
// TODO: class to be renamed because it handls controllers registration as well
public class KafkaNodeUnregistration {
    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(KafkaNodeUnregistration.class.getName());

    /**
     * Unregisters Kafka broker nodes from a KRaft-based Kafka cluster
     *
     * @param reconciliation        Reconciliation marker
     * @param vertx                 Vert.x instance
     * @param adminClientProvider   Kafka Admin API client provider
     * @param pemTrustSet           Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity       Key set  for the admin client to connect to the Kafka cluster
     * @param nodeIdsToUnregister   List of node IDs that should be unregistered
     *
     * @return  Future that completes when all broker nodes are unregistered
     */
    public static Future<Void> unregisterBrokerNodes(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            Set<Integer> nodeIdsToUnregister
    ) {
        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

            List<Future<Void>> futures = new ArrayList<>();
            for (Integer nodeId : nodeIdsToUnregister) {
                futures.add(unregisterBrokerNode(reconciliation, vertx, adminClient, nodeId));
            }

            return Future.all(futures)
                    .eventually(() -> {
                        adminClient.close();
                        return Future.succeededFuture();
                    })
                    .mapEmpty();
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to unregister nodes", e);
            return Future.failedFuture(e);
        }
    }

    /**
     * List registered Kafka broker nodes within a KRaft-based cluster
     *
     * @param reconciliation        Reconciliation marker
     * @param vertx                 Vert.x instance
     * @param adminClientProvider   Kafka Admin API client provider
     * @param pemTrustSet           Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity       Key set  for the admin client to connect to the Kafka cluster
     * @param includeFencedBrokers  If listing should include fenced brokers
     *
     * @return  Future that completes when all registered broker nodes are listed
     */
    public static Future<Collection<Node>> listRegisteredBrokerNodes(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            boolean includeFencedBrokers) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

            DescribeClusterOptions option = new DescribeClusterOptions().includeFencedBrokers(includeFencedBrokers);
            return VertxUtil
                    .kafkaFutureToVertxFuture(reconciliation, vertx, adminClient.describeCluster(option).nodes())
                    .compose(nodes -> {
                        LOGGER.debugCr(reconciliation, "Describe cluster: nodes (fenced included) = {}", nodes);
                        adminClient.close();
                        return Future.succeededFuture(nodes);
                    })
                    .recover(throwable -> {
                        adminClient.close();
                        return Future.failedFuture(throwable);
                    });
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to list nodes", e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Unregisters a single Kafka broker node using the Kafka Admin API. In case the failure is caused by the node not being
     * registered, the error will be ignored.
     *
     * @param reconciliation        Reconciliation marker
     * @param vertx                 Vert.x instance
     * @param adminClient           Kafka Admin API client instance
     * @param nodeIdToUnregister    ID of the broker node that should be unregistered
     *
     * @return  Future that completes when the node is unregistered
     */
    private static Future<Void> unregisterBrokerNode(Reconciliation reconciliation, Vertx vertx, Admin adminClient, Integer nodeIdToUnregister) {
        LOGGER.debugCr(reconciliation, "Unregistering node {} from the Kafka cluster", nodeIdToUnregister);

        return VertxUtil
                .kafkaFutureToVertxFuture(reconciliation, vertx, adminClient.unregisterBroker(nodeIdToUnregister).all())
                .onFailure(t -> {
                    LOGGER.warnCr(reconciliation, "Failed to unregister node {} from the Kafka cluster", nodeIdToUnregister, t);
                });
    }

    /**
     * Register a single newly added KRaft controller
     *
     * @param reconciliation        Reconciliation marker
     * @param vertx                 Vert.x instance
     * @param adminClient           Kafka Admin API client instance
     * @param controllerToRegister  NodeRef of the new KRaft controller to be registered
     * @return  Future that completes when the KRaft controller is registered
     */
    private static Future<Void> registerControllerNode(Reconciliation reconciliation, Vertx vertx, Admin adminClient, NodeRef controllerToRegister) {
        LOGGER.infoCr(reconciliation, "Registering controller node {}", controllerToRegister.nodeId());

        return VertxUtil
                .kafkaFutureToVertxFuture(reconciliation, vertx, adminClient.describeMetadataQuorum().quorumInfo())
                .compose(quorumInfo -> {
                    // After the scale up, the new controller is still an observer, until it's registered
                    QuorumInfo.ReplicaState controllerState = quorumInfo.observers().stream()
                            .filter(replicaState -> replicaState.replicaId() == controllerToRegister.nodeId())
                            .findFirst().orElse(null);
                    if (controllerState != null) {
                        LOGGER.infoCr(reconciliation, "Controller node {} directory Id = {}", controllerToRegister.nodeId(), controllerState.replicaDirectoryId());

                        // Construct the controller endpoint (advertised listener) since observers are not in quorumInfo.nodes() (where Node instance contains endpoints)
                        String host = DnsNameGenerator.podDnsNameWithoutClusterDomain(
                                reconciliation.namespace(),
                                KafkaResources.brokersServiceName(reconciliation.name()),
                                controllerToRegister.podName());

                        // TODO: avoiding hard-coded listener and port?
                        RaftVoterEndpoint endpoint = new RaftVoterEndpoint("CONTROLPLANE-9090", host, 9090);
                        LOGGER.infoCr(reconciliation, "Constructed controller endpoint = {}", endpoint);

                        return VertxUtil
                                .kafkaFutureToVertxFuture(reconciliation, vertx,
                                        adminClient.addRaftVoter(controllerToRegister.nodeId(), controllerState.replicaDirectoryId(), Set.of(endpoint)).all());
                    } else {
                        LOGGER.warnCr(reconciliation, "Failed to get quorum info for controller node {} from the Kafka cluster", controllerToRegister.nodeId());
                        return Future.succeededFuture();
                    }
                })
                .onSuccess(v -> LOGGER.infoCr(reconciliation, "Controller node {} successfully registered", controllerToRegister.nodeId()))
                .onFailure(t -> LOGGER.warnCr(reconciliation, "Failed to register controller node {} from the Kafka cluster", controllerToRegister.nodeId(), t));
    }

    /**
     * Register a list of newly added KRaft controllers
     *
     * @param reconciliation            Reconciliation marker
     * @param vertx                     Vert.x instance
     * @param adminClientProvider       Kafka Admin API client provider
     * @param pemTrustSet               Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity           Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToRegister     List of NodeRef of the new KRaft controllers to be registered
     * @return  Future that completes when the KRaft controllers are registered
     */
    public static Future<Void> registerControllerNodes(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            Set<NodeRef> controllersToRegister) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

            Future<Void> compositeFuture = Future.succeededFuture();
            // Chain each controller registration future sequentially (with compose) because
            // Kafka can handle each addRaftVoter call one by one and not in parallel
            for (NodeRef controller : controllersToRegister) {
                compositeFuture = compositeFuture.compose(v -> registerControllerNode(reconciliation, vertx, adminClient, controller));
            }

            return compositeFuture
                    .eventually(() -> {
                        adminClient.close();
                        return Future.succeededFuture();
                    })
                    .mapEmpty();
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to register controllers", e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Unregister a single KRaft controller
     *
     * @param reconciliation            Reconciliation marker
     * @param vertx                     Vert.x instance
     * @param adminClient               Kafka Admin API client instance
     * @param controllerToUnregister    NodeRef of the KRaft controller to be unregistered
     * @return  Future that completes when the KRaft controller is unregistered
     */
    private static Future<Void> unregisterControllerNode(Reconciliation reconciliation, Vertx vertx, Admin adminClient, NodeRef controllerToUnregister) {
        LOGGER.infoCr(reconciliation, "Unregistering controller node {}", controllerToUnregister.nodeId());

        return VertxUtil
                .kafkaFutureToVertxFuture(reconciliation, vertx, adminClient.describeMetadataQuorum().quorumInfo())
                .compose(quorumInfo -> {
                    // After the scale up, the new controller is still an observer, until it's registered
                    QuorumInfo.ReplicaState controllerState = quorumInfo.voters().stream()
                            .filter(replicaState -> replicaState.replicaId() == controllerToUnregister.nodeId())
                            .findFirst().orElse(null);
                    if (controllerState != null) {
                        LOGGER.infoCr(reconciliation, "Controller node {} directory Id = {}", controllerToUnregister.nodeId(), controllerState.replicaDirectoryId());

                        return VertxUtil
                                .kafkaFutureToVertxFuture(reconciliation, vertx,
                                        adminClient.removeRaftVoter(controllerToUnregister.nodeId(), controllerState.replicaDirectoryId()).all());
                    } else {
                        LOGGER.warnCr(reconciliation, "Failed to get quorum info for controller node {} from the Kafka cluster", controllerToUnregister.nodeId());
                        return Future.succeededFuture();
                    }
                })
                .onSuccess(v -> LOGGER.infoCr(reconciliation, "Controller node {} successfully unregistered", controllerToUnregister.nodeId()))
                .onFailure(t -> LOGGER.warnCr(reconciliation, "Failed to unregister controller node {} from the Kafka cluster", controllerToUnregister.nodeId(), t));
    }

    /**
     * Unregister a list of KRaft controllers from the quorum
     *
     * @param reconciliation            Reconciliation marker
     * @param vertx                     Vert.x instance
     * @param adminClientProvider       Kafka Admin API client provider
     * @param pemTrustSet               Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity           Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToUnregister   List of NodeRef of the KRaft controllers to be unregistered
     * @return  Future that completes when the KRaft controllers are unregistered
     */
    public static Future<Void> unregisterControllerNodes(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            Set<NodeRef> controllersToUnregister) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

            Future<Void> compositeFuture = Future.succeededFuture();
            // Chain each controller unregistration future sequentially (with compose) because
            // Kafka can handle each removeRaftVoter call one by one and not in parallel
            for (NodeRef controller : controllersToUnregister) {
                compositeFuture = compositeFuture.compose(v -> unregisterControllerNode(reconciliation, vertx, adminClient, controller));
            }

            return compositeFuture
                    .eventually(() -> {
                        adminClient.close();
                        return Future.succeededFuture();
                    })
                    .mapEmpty();
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to unregister controllers", e);
            return Future.failedFuture(e);
        }
    }
}
