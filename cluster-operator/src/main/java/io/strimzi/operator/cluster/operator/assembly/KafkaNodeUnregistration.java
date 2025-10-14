/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.strimzi.api.kafka.model.kafka.KafkaResources;
import io.strimzi.operator.cluster.model.DnsNameGenerator;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.NodeRef;
import io.strimzi.operator.common.AdminClientProvider;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.auth.AuthIdentity;
import io.strimzi.operator.common.auth.TrustSet;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.QuorumInfo;
import org.apache.kafka.clients.admin.RaftVoterEndpoint;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static io.strimzi.operator.common.Util.maybeUnwrapCompletionException;

/**
 * Contains utility methods for unregistering KRaft nodes from a Kafka cluster after scale-down
 */
// TODO: class to be renamed because it handls controllers registration as well
public class KafkaNodeUnregistration {
    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(KafkaNodeUnregistration.class.getName());

    private KafkaNodeUnregistration() { }

    /**
     * Unregisters Kafka broker nodes that were removed from a KRaft-based Kafka cluster.
     * It lists all registered broker nodes and unregisters those not present in {@code currentBrokerNodes}.
     * Any listing or unregistration failures are logged and swallowed, the returned future always completes successfully.
     *
     * @param reconciliation        Reconciliation marker
     * @param adminClientProvider   Kafka Admin API client provider
     * @param kafkaTrustSet         Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity          Key set for the admin client to connect to the Kafka cluster
     * @param currentBrokerNodes    Currently active broker nodes after scale-down
     *
     * @return  CompletionStage that always completes successfully when broker unregistration is done
     */
    public static CompletionStage<Void> unregisterBrokerNodes(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            TrustSet kafkaTrustSet,
            AuthIdentity authIdentity,
            Set<NodeRef> currentBrokerNodes
    ) {
        List<Integer> currentBrokerIds = currentBrokerNodes.stream().map(NodeRef::nodeId).sorted().toList();
        CompletableFuture<Void> result = new CompletableFuture<>();

        listRegisteredBrokerNodes(reconciliation, adminClientProvider, kafkaTrustSet, authIdentity, true)
                .whenComplete((registeredBrokerNodes, listingFailure) -> {
                    if (listingFailure != null) {
                        // listing broker nodes failed, we will retry on next reconciliation
                        LOGGER.warnCr(reconciliation, "Failed to list Kafka broker nodes from the Kafka cluster", listingFailure);
                        // We complete the future with success even if listing failed as we do not want to fail the reconciliation.
                        result.complete(null);
                        return;
                    }

                    List<Integer> registeredBrokerIds = registeredBrokerNodes.stream().map(Node::id).toList();

                    LOGGER.debugCr(reconciliation, "Broker nodes unregistration: currentBrokerIds = {}, registeredBrokerIds = {}", currentBrokerIds, registeredBrokerIds);

                    Set<Integer> brokerIdsToUnregister = new HashSet<>(registeredBrokerIds);
                    brokerIdsToUnregister.removeAll(currentBrokerIds);

                    if (brokerIdsToUnregister.isEmpty()) {
                        result.complete(null);
                        return;
                    }

                    LOGGER.infoCr(reconciliation, "Kafka broker nodes {} were removed from the Kafka cluster and will be unregistered", brokerIdsToUnregister);

                    try {
                        String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
                        Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, kafkaTrustSet, authIdentity);

                        List<CompletionStage<Void>> futures = new ArrayList<>();
                        for (Integer nodeId : brokerIdsToUnregister) {
                            futures.add(unregisterBrokerNode(reconciliation, adminClient, nodeId));
                        }
                        CompletableFuture.allOf(futures.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new))
                                .whenComplete((v, unregistrationFailure) -> {
                                    adminClient.close();
                                    if (unregistrationFailure == null) {
                                        LOGGER.infoCr(reconciliation, "Kafka broker nodes {} were successfully unregistered from the Kafka cluster", brokerIdsToUnregister);
                                    } else {
                                        // unregistration failed, we will retry on next reconciliation
                                        LOGGER.warnCr(reconciliation, "Failed to unregister Kafka broker nodes {} from the Kafka cluster", brokerIdsToUnregister);
                                    }
                                    // We complete the future with success even if the unregistration failed as we do not want to fail the reconciliation.
                                    result.complete(null);
                                });
                    } catch (KafkaException e) {
                        // unregistration failed, we will retry on next reconciliation
                        LOGGER.warnCr(reconciliation, "Failed to unregister broker nodes", e);
                        // We complete the future with success even if the unregistration failed as we do not want to fail the reconciliation.
                        result.complete(null);
                    }
                });

        return result;
    }

    /* test */ static CompletionStage<Collection<Node>> listRegisteredBrokerNodes(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            TrustSet kafkaTrustSet,
            AuthIdentity authIdentity,
            boolean includeFencedBrokers) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, kafkaTrustSet, authIdentity);

            DescribeClusterOptions option = new DescribeClusterOptions().includeFencedBrokers(includeFencedBrokers);

            return adminClient.describeCluster(option).nodes()
                    .whenComplete((nodes, t) -> {
                        if (t == null) {
                            LOGGER.debugCr(reconciliation, "Describe cluster: broker nodes (fenced included) = {}", nodes);
                        }
                        adminClient.close();
                    })
                    .toCompletionStage();
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to list broker nodes", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Unregisters Kafka controller nodes that were removed from a KRaft-based Kafka cluster.
     * It lists all registered controller nodes and unregisters those not present in {@code currentControllerNodes}.
     * Any listing or unregistration failures are logged and swallowed, the returned future always completes successfully.
     *
     * @param reconciliation            Reconciliation marker
     * @param adminClientProvider       Kafka Admin API client provider
     * @param kafkaTrustSet             Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity              Key set for the admin client to connect to the Kafka cluster
     * @param currentControllerNodes    Currently active controller nodes after scale-down
     *
     * @return  CompletionStage that always completes successfully when controller unregistration is done
     */
    public static CompletionStage<Void> unregisterControllerNodes(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            TrustSet kafkaTrustSet,
            AuthIdentity authIdentity,
            Set<NodeRef> currentControllerNodes
    ) {
        List<Integer> currentControllerIds = currentControllerNodes.stream().map(NodeRef::nodeId).sorted().toList();
        CompletableFuture<Void> result = new CompletableFuture<>();

        listRegisteredControllerNodes(reconciliation, adminClientProvider, kafkaTrustSet, authIdentity, currentControllerNodes)
                .whenComplete((registeredControllerNodes, listingFailure) -> {
                    if (listingFailure != null) {
                        // listing registered controller nodes failed, we will retry on next reconciliation
                        LOGGER.warnCr(reconciliation, "Failed to list registered Kafka controller nodes from the Kafka cluster", listingFailure);
                        // We complete the future with success even if listing failed as we do not want to fail the reconciliation.
                        result.complete(null);
                        return;
                    }

                    List<Integer> registeredControllerIds = registeredControllerNodes.stream().map(Node::id).toList();

                    LOGGER.debugCr(reconciliation, "Controller nodes unregistration: currentControllerIds = {}, registeredControllerIds = {}", currentControllerIds, registeredControllerIds);

                    Set<Integer> controllerIdsToUnregister = new HashSet<>(registeredControllerIds);
                    controllerIdsToUnregister.removeAll(currentControllerIds);

                    if (controllerIdsToUnregister.isEmpty()) {
                        result.complete(null);
                        return;
                    }

                    LOGGER.infoCr(reconciliation, "Kafka controller nodes {} were removed from the Kafka cluster and will be unregistered", controllerIdsToUnregister);

                    try {
                        String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
                        Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, kafkaTrustSet, authIdentity);

                        List<CompletionStage<Void>> futures = new ArrayList<>();
                        for (Integer nodeId : controllerIdsToUnregister) {
                            futures.add(unregisterControllerNode(reconciliation, adminClient, nodeId));
                        }
                        CompletableFuture.allOf(futures.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new))
                                .whenComplete((v, unregistrationFailure) -> {
                                    adminClient.close();
                                    if (unregistrationFailure == null) {
                                        LOGGER.infoCr(reconciliation, "Kafka controller nodes {} were successfully unregistered from the Kafka cluster", controllerIdsToUnregister);
                                    } else {
                                        // unregistration failed, we will retry on next reconciliation
                                        LOGGER.warnCr(reconciliation, "Failed to unregister Kafka controller nodes {} from the Kafka cluster", controllerIdsToUnregister);
                                    }
                                    // We complete the future with success even if the unregistration failed as we do not want to fail the reconciliation.
                                    result.complete(null);
                                });
                    } catch (KafkaException e) {
                        // unregistration failed, we will retry on next reconciliation
                        LOGGER.warnCr(reconciliation, "Failed to unregister controller nodes", e);
                        // We complete the future with success even if the unregistration failed as we do not want to fail the reconciliation.
                        result.complete(null);
                    }
                });

        return result;
    }

    /* test */ static CompletionStage<Collection<Node>> listRegisteredControllerNodes(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            TrustSet kafkaTrustSet,
            AuthIdentity authIdentity,
            Set<NodeRef> controllerNodes) {

        try {
            // Connect to the controller endpoint so that describeCluster() returns the status
            // of registered controllers as they have a RegisterControllerRecord in the metadata log
            // but not a corresponding UnregisterControllerRecord.
            String bootstrapHostnames = controllerNodes.stream()
                    .map(node -> DnsNameGenerator.podDnsNameWithoutClusterDomain(
                            reconciliation.namespace(),
                            KafkaResources.brokersServiceName(reconciliation.name()),
                            node.podName()) + ":" + KafkaCluster.CONTROLPLANE_PORT)
                    .collect(Collectors.joining(","));
            Admin adminClient = adminClientProvider.createControllerAdminClient(bootstrapHostnames, kafkaTrustSet, authIdentity);

            return adminClient.describeCluster().nodes()
                    .whenComplete((nodes, t) -> {
                        if (t == null) {
                            LOGGER.debugCr(reconciliation, "Describe cluster (controller endpoint): controller nodes = {}", nodes);
                        }
                        adminClient.close();
                    })
                    .toCompletionStage();
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to list registered controller nodes", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Unregisters a single Kafka broker node using the Kafka Admin API. In case the failure is caused by the node not being
     * registered, the error will be ignored.
     *
     * @param reconciliation        Reconciliation marker
     * @param adminClient           Kafka Admin API client instance
     * @param nodeIdToUnregister    ID of the broker node that should be unregistered
     *
     * @return  CompletionStage that completes when the node is unregistered
     */
    private static CompletionStage<Void> unregisterBrokerNode(Reconciliation reconciliation, Admin adminClient, Integer nodeIdToUnregister) {
        LOGGER.debugCr(reconciliation, "Unregistering broker node {} from the Kafka cluster", nodeIdToUnregister);

        return adminClient.unregisterBroker(nodeIdToUnregister).all()
                .whenComplete((v, t) -> {
                    if (t != null) {
                        Throwable cause = maybeUnwrapCompletionException(t);
                        LOGGER.warnCr(reconciliation, "Failed to unregister broker node {} from the Kafka cluster", nodeIdToUnregister, cause);
                    }
                })
                .toCompletionStage();
    }

    /**
     * Unregisters a single Kafka controller node using the Kafka Admin API. In case the failure is caused by the node not being
     * registered, the error will be ignored.
     *
     * @param reconciliation        Reconciliation marker
     * @param adminClient           Kafka Admin API client instance
     * @param nodeIdToUnregister    ID of the controller node that should be unregistered
     *
     * @return  CompletionStage that completes when the node is unregistered
     */
    private static CompletionStage<Void> unregisterControllerNode(Reconciliation reconciliation, Admin adminClient, Integer nodeIdToUnregister) {
        LOGGER.debugCr(reconciliation, "Unregistering controller node {} from the Kafka cluster", nodeIdToUnregister);

        return adminClient.unregisterController(nodeIdToUnregister).all()
                .whenComplete((v, t) -> {
                    if (t != null) {
                        Throwable cause = maybeUnwrapCompletionException(t);
                        LOGGER.warnCr(reconciliation, "Failed to unregister controller node {} from the Kafka cluster", nodeIdToUnregister, cause);
                    }
                })
                .toCompletionStage();
    }

    // TODO: to be removed if using Apache Kafka 4.2.0 where the controller quorum auto join feature is used
    /**
     * Register a single newly added KRaft controller
     *
     * @param reconciliation        Reconciliation marker
     * @param vertx                 Vert.x instance
     * @param adminClient           Kafka Admin API client instance
     * @param controllerToRegister  NodeRef of the new KRaft controller to be registered
     * @return  Future that completes when the KRaft controller is registered
     */
    /*
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
    */

    // TODO: to be removed if using Apache Kafka 4.2.0 where the controller quorum auto join feature is used
    /**
     * Register a single newly added KRaft controller
     *
     * @param reconciliation    Reconciliation marker
     * @param adminClient       Kafka Admin API client instance
     * @param replica           ReplicaToRegister containing replica state and pre-constructed endpoint
     * @return  CompletableFuture that completes when the KRaft controller is registered
     */
    private static CompletableFuture<Void> registerControllerReplica(Reconciliation reconciliation, Admin adminClient, KRaftQuorumReconciler.ReplicaToRegister replica) {

        QuorumInfo.ReplicaState replicaState = replica.replicaState();
        RaftVoterEndpoint endpoint = replica.endpoint();

        LOGGER.infoCr(reconciliation, "Registering controller node [{}]-[{}] with endpoint {}",
                replicaState.replicaId(), replicaState.replicaDirectoryId(), endpoint);

        return adminClient.addRaftVoter(replicaState.replicaId(), replicaState.replicaDirectoryId(), Set.of(endpoint)).all()
                .whenComplete((v, t) -> {
                    if (t == null) {
                        LOGGER.infoCr(reconciliation, "Controller node [{}]-[{}] successfully registered",
                                replicaState.replicaId(), replicaState.replicaDirectoryId());
                    } else {
                        LOGGER.warnCr(reconciliation, "Failed to register controller node [{}]-[{}] from the Kafka cluster",
                                replicaState.replicaId(), replicaState.replicaDirectoryId(), t);
                    }
                })
                .toCompletionStage()
                .toCompletableFuture();
    }

    // TODO: to be removed if using Apache Kafka 4.2.0 where the controller quorum auto join feature is used
    /**
     * Register a list of newly added KRaft controllers
     *
     * @param reconciliation            Reconciliation marker
     * @param vertx                     Vert.x instance
     * @param adminClientProvider       Kafka Admin API client provider
     * @param trustSet                  Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity              Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToRegister     List of NodeRef of the new KRaft controllers to be registered
     * @return  Future that completes when the KRaft controllers are registered
     */
    /*
    public static Future<Void> registerControllerNodes(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            TrustSet trustSet,
            AuthIdentity authIdentity,
            Set<NodeRef> controllersToRegister) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, trustSet, authIdentity);

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
    */

    /**
     * Register a list of newly added KRaft controllers
     *
     * @param reconciliation            Reconciliation marker
     * @param adminClientProvider       Kafka Admin API client provider
     * @param trustSet                  Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity              Key set  for the admin client to connect to the Kafka cluster
     * @param replicasToRegister        Set of ReplicaToRegister containing replica states with pre-constructed endpoints
     * @return  CompletableFuture that completes when the KRaft controllers are registered
     */
    public static CompletableFuture<Void> registerControllerReplicas(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            TrustSet trustSet,
            AuthIdentity authIdentity,
            Set<KRaftQuorumReconciler.ReplicaToRegister> replicasToRegister) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, trustSet, authIdentity);

            LOGGER.infoCr(reconciliation, "Controllers to register: {}", replicasToRegister);
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            // Chain each controller registration future sequentially (with thenCompose) because
            // Kafka can handle each addRaftVoter call one by one and not in parallel
            for (KRaftQuorumReconciler.ReplicaToRegister replica : replicasToRegister) {
                chain = chain.thenCompose(v -> registerControllerReplica(reconciliation, adminClient, replica));
            }

            return chain
                    .whenComplete((v, t) -> {
                        adminClient.close();
                    });
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to register controllers", e);
            return CompletableFuture.failedFuture(e);
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
    /*
    private static Future<Void> unregisterControllerNode(Reconciliation reconciliation, Vertx vertx, Admin adminClient, NodeRef controllerToUnregister) {
        return unregisterControllerNode(reconciliation, vertx, adminClient, controllerToUnregister.nodeId());
    }
    */

    /**
     * Unregister a single KRaft controller
     *
     * @param reconciliation            Reconciliation marker
     * @param adminClient               Kafka Admin API client instance
     * @param replicaState              QuorumInfo.ReplicaState of the KRaft controller to be unregistered
     * @return  CompletableFuture that completes when the KRaft controller is unregistered
     */
    private static CompletableFuture<Void> unregisterControllerReplica(Reconciliation reconciliation, Admin adminClient, QuorumInfo.ReplicaState replicaState) {
        LOGGER.infoCr(reconciliation, "Unregistering controller node [{}]-[{}]", replicaState.replicaId(), replicaState.replicaDirectoryId());

        return adminClient.removeRaftVoter(replicaState.replicaId(), replicaState.replicaDirectoryId()).all()
                .whenComplete((v, t) -> {
                    if (t == null) {
                        LOGGER.infoCr(reconciliation, "Controller node [{}]-[{}] successfully unregistered",
                                replicaState.replicaId(), replicaState.replicaDirectoryId());
                    } else {
                        LOGGER.warnCr(reconciliation, "Failed to unregister controller node [{}]-[{}] from the Kafka cluster",
                                replicaState.replicaId(), replicaState.replicaDirectoryId(), t);
                    }
                })
                .toCompletionStage()
                .toCompletableFuture();
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
    /*
    private static Future<Void> unregisterControllerNode(Reconciliation reconciliation, Vertx vertx, Admin adminClient, Integer controllerToUnregister) {
        LOGGER.infoCr(reconciliation, "Unregistering controller node {}", controllerToUnregister);

        return VertxUtil
                .kafkaFutureToVertxFuture(reconciliation, vertx, adminClient.describeMetadataQuorum().quorumInfo())
                .compose(quorumInfo -> {
                    // After the scale up, the new controller is still an observer, until it's registered
                    QuorumInfo.ReplicaState controllerState = quorumInfo.voters().stream()
                            .filter(replicaState -> replicaState.replicaId() == controllerToUnregister)
                            .findFirst().orElse(null);
                    if (controllerState != null) {
                        LOGGER.infoCr(reconciliation, "Controller node {} directory Id = {}", controllerToUnregister, controllerState.replicaDirectoryId());

                        return VertxUtil
                                .kafkaFutureToVertxFuture(reconciliation, vertx,
                                        adminClient.removeRaftVoter(controllerToUnregister, controllerState.replicaDirectoryId()).all());
                    } else {
                        LOGGER.warnCr(reconciliation, "Failed to get quorum info for controller node {} from the Kafka cluster", controllerToUnregister);
                        return Future.succeededFuture();
                    }
                })
                .onSuccess(v -> LOGGER.infoCr(reconciliation, "Controller node {} successfully unregistered", controllerToUnregister))
                .onFailure(t -> LOGGER.warnCr(reconciliation, "Failed to unregister controller node {} from the Kafka cluster", controllerToUnregister, t));
    }
    */

    /**
     * Unregister a list of KRaft controllers from the quorum
     *
     * @param reconciliation            Reconciliation marker
     * @param vertx                     Vert.x instance
     * @param adminClientProvider       Kafka Admin API client provider
     * @param trustSet                  Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity              Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToUnregister   List of KRaft controllers IDs to be unregistered
     * @return  Future that completes when the KRaft controllers are unregistered
     */
    /*
    public static Future<Void> unregisterControllerNodes(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            TrustSet trustSet,
            AuthIdentity authIdentity,
            Set<Integer> controllersToUnregister) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, trustSet, authIdentity);

            Future<Void> compositeFuture = Future.succeededFuture();
            // Chain each controller unregistration future sequentially (with compose) because
            // Kafka can handle each removeRaftVoter call one by one and not in parallel
            for (Integer controller : controllersToUnregister) {
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
    */

    /**
     * Unregister a list of KRaft controllers from the quorum
     *
     * @param reconciliation            Reconciliation marker
     * @param adminClientProvider       Kafka Admin API client provider
     * @param trustSet                  Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity              Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToUnregister   Set of KRaft controller ReplicaState to be unregistered
     * @return  CompletableFuture that completes when the KRaft controllers are unregistered
     */
    public static CompletableFuture<Void> unregisterControllerReplicas(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            TrustSet trustSet,
            AuthIdentity authIdentity,
            Set<QuorumInfo.ReplicaState> controllersToUnregister) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, trustSet, authIdentity);

            LOGGER.infoCr(reconciliation, "Controllers to unregister: {}", controllersToUnregister);
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            // Chain each controller unregistration future sequentially (with thenCompose) because
            // Kafka can handle each removeRaftVoter call one by one and not in parallel
            for (QuorumInfo.ReplicaState replicaState : controllersToUnregister) {
                chain = chain.thenCompose(v -> unregisterControllerReplica(reconciliation, adminClient, replicaState));
            }

            return chain
                    .whenComplete((v, t) -> {
                        adminClient.close();
                    });
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to unregister controllers", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Reconcile the KRaft controllers quorum, by registering new controllers (scale up) or
     * unregistering old ones (scale down)
     *
     * @param reconciliation            Reconciliation marker
     * @param vertx                     Vert.x instance
     * @param adminClientProvider       Kafka Admin API client provider
     * @param trustSet                  Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity              Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToReconcile    List of NodeRef of the KRaft controllers to be reconciled (desired ones)
     * @return  Future that completes when the KRaft controllers are unregistered
     */
    /*
    public static Future<Void> reconcileKRaftQuorum(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            TrustSet trustSet,
            AuthIdentity authIdentity,
            Set<NodeRef> controllersToReconcile) {

        try {
            LOGGER.infoCr(reconciliation, "**** controllersToReconcile: {}", controllersToReconcile);

            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, trustSet, authIdentity);

            return VertxUtil
                    .kafkaFutureToVertxFuture(reconciliation, vertx, adminClient.describeMetadataQuorum().quorumInfo())
                    .compose(quorumInfo -> {

                        Set<Integer> voters = quorumInfo.voters().stream()
                                .map(QuorumInfo.ReplicaState::replicaId)
                                .collect(Collectors.toSet());

                        Set<Integer> desiredVoters = controllersToReconcile.stream()
                                .map(NodeRef::nodeId)
                                .collect(Collectors.toSet());

                        // Identify controllers that need to be registered (scale up: in controllersToReconcile but not in voters)
                        Set<NodeRef> controllersToRegister = controllersToReconcile.stream()
                                .filter(controller -> !voters.contains(controller.nodeId()))
                                .collect(Collectors.toSet());

                        // Identify controllers that need to be unregistered (scale down: in voters but not in desiredVoters)
                        Set<Integer> controllersToUnregister = voters.stream()
                                .filter(id -> !desiredVoters.contains(id))
                                .collect(Collectors.toSet());

                        if (!controllersToRegister.isEmpty()) {
                            LOGGER.infoCr(reconciliation, "**** Controllers to register: {}", controllersToRegister);
                            // TODO: Register controllers to the quorum
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
                        }

                        if (!controllersToUnregister.isEmpty()) {
                            LOGGER.infoCr(reconciliation, "**** Controllers to unregister: {}", controllersToUnregister);
                            // TODO: Unregister controllers from the quorum
                            Future<Void> compositeFuture = Future.succeededFuture();
                            // Chain each controller unregistration future sequentially (with compose) because
                            // Kafka can handle each removeRaftVoter call one by one and not in parallel
                            for (Integer controllerId : controllersToUnregister) {
                                compositeFuture = compositeFuture.compose(v -> unregisterControllerNode(reconciliation, vertx, adminClient, controllerId));
                            }

                            return compositeFuture
                                    .eventually(() -> {
                                        adminClient.close();
                                        return Future.succeededFuture();
                                    })
                                    .mapEmpty();
                        }

                        return Future.succeededFuture();
                    });

        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to unregister controllers", e);
            return Future.failedFuture(e);
        }
    }
    */

    /**
     * Reconcile the KRaft controllers quorum, by registering new controllers (scale up or new disk) or
     * unregistering old ones (scale down or old disk)
     *
     * @param reconciliation            Reconciliation marker
     * @param vertx                     Vert.x instance
     * @param adminClientProvider       Kafka Admin API client provider
     * @param trustSet                  Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity              Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToReconcile    List of NodeRef of the KRaft controllers to be reconciled (desired ones)
     * @return  Future that completes when the KRaft controllers are unregistered
     */
    /*
    public static Future<Void> reconcileKRaftQuorum(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            TrustSet trustSet,
            AuthIdentity authIdentity,
            Set<NodeRef> controllersToReconcile) {

        try {
            LOGGER.infoCr(reconciliation, "**** controllersToReconcile: {}", controllersToReconcile);

            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, trustSet, authIdentity);

            return VertxUtil
                    .kafkaFutureToVertxFuture(reconciliation, vertx, adminClient.describeMetadataQuorum().quorumInfo())
                    .compose(quorumInfo -> {

                        Set<Integer> desiredVoters = controllersToReconcile.stream()
                                .map(NodeRef::nodeId)
                                .collect(Collectors.toSet());

                        // COLLECTING unregistrations

                        // Identify controllers that need to be unregistered because of scale down operation (in voters but not in desiredVoters)
                        Set<QuorumInfo.ReplicaState> scaledDownControllersToUnregister = quorumInfo.voters().stream()
                                .filter(rs -> !desiredVoters.contains(rs.replicaId()))
                                .collect(Collectors.toSet());

                        // Identify controllers for which we need to unregister their old "incarnation", with a different directory id (i.e. disk change)
                        // They are desiredVoters, and also they are both in voters and observers but the ones in observers have old directory id
                        Set<QuorumInfo.ReplicaState> diskChangeControllersToUnregister = quorumInfo.voters().stream()
                                .filter(rs -> desiredVoters.contains(rs.replicaId()))
                                .filter(rs -> quorumInfo.observers().stream()
                                        .anyMatch(obs -> obs.replicaId() == rs.replicaId()))
                                .collect(Collectors.toSet());

                        // Combine both sets of controllers to unregister
                        Set<QuorumInfo.ReplicaState> controllersToUnregister = new HashSet<>(scaledDownControllersToUnregister);
                        controllersToUnregister.addAll(diskChangeControllersToUnregister);

                        // COLLECTING registrations

                        // Identify controllers that need to be registered because of scale up operation (in desiredVoters and still observers but not in actual voters)
                        Set<QuorumInfo.ReplicaState> controllersToRegister = quorumInfo.observers().stream()
                                .filter(obs -> desiredVoters.contains(obs.replicaId()))
                                .filter(obs -> quorumInfo.voters().stream()
                                        .noneMatch(voter -> voter.replicaId() == obs.replicaId()))
                                .collect(Collectors.toSet());

                        if (!controllersToUnregister.isEmpty()) {
                            LOGGER.infoCr(reconciliation, "**** Controllers to unregister: {}", controllersToUnregister);
                            Future<Void> compositeFuture = Future.succeededFuture();
                            // Chain each controller unregistration future sequentially (with compose) because
                            // Kafka can handle each removeRaftVoter call one by one and not in parallel
                            for (QuorumInfo.ReplicaState replicaState : controllersToUnregister) {
                                compositeFuture = compositeFuture.compose(v -> unregisterControllerNode(reconciliation, vertx, adminClient, replicaState));
                            }

                            return compositeFuture
                                    .eventually(() -> {
                                        adminClient.close();
                                        return Future.succeededFuture();
                                    })
                                    .mapEmpty();
                        }

                        if (!controllersToRegister.isEmpty()) {
                            LOGGER.infoCr(reconciliation, "**** Controllers to register: {}", controllersToRegister);
                            Future<Void> compositeFuture = Future.succeededFuture();
                            // Chain each controller registration future sequentially (with compose) because
                            // Kafka can handle each addRaftVoter call one by one and not in parallel
                            for (QuorumInfo.ReplicaState replicaState : controllersToRegister) {
                                NodeRef nodeRef = controllersToReconcile.stream()
                                        .filter(nr -> nr.nodeId() == replicaState.replicaId())
                                        .findFirst()
                                        .orElseThrow(() -> new IllegalStateException("NodeRef not found for replicaId: " + replicaState.replicaId()));
                                compositeFuture = compositeFuture.compose(v -> registerControllerNode(reconciliation, vertx, adminClient, replicaState, nodeRef));
                            }

                            return compositeFuture
                                    .eventually(() -> {
                                        adminClient.close();
                                        return Future.succeededFuture();
                                    })
                                    .mapEmpty();
                        }

                        return Future.succeededFuture();
                    });

        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to reconcile controllers", e);
            return Future.failedFuture(e);
        }
    }
    */

    /**
     * List quorum voters within a KRaft-based cluster
     *
     * @param reconciliation        Reconciliation marker
     * @param vertx                 Vert.x instance
     * @param adminClientProvider   Kafka Admin API client provider
     * @param trustSet              Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity          Key set for the admin client to connect to the Kafka cluster
     *
     * @return  Future that completes when all quorum voters are listed
     */
    /*
    public static Future<Set<Integer>> listQuorumVoters(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            TrustSet trustSet,
            AuthIdentity authIdentity) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, trustSet, authIdentity);

            return VertxUtil
                    .kafkaFutureToVertxFuture(reconciliation, vertx, adminClient.describeMetadataQuorum().quorumInfo())
                    .compose(quorumInfo -> {
                        Set<Integer> voters = quorumInfo.voters().stream()
                                .map(QuorumInfo.ReplicaState::replicaId)
                                .collect(Collectors.toSet());
                        LOGGER.infoCr(reconciliation, "**** Describe Metadata Quorum voters: {}", voters);
                        adminClient.close();
                        return Future.succeededFuture(voters);
                    })
                    .recover(throwable -> {
                        adminClient.close();
                        return Future.failedFuture(throwable);
                    });
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to list quorum voters", e);
            return Future.failedFuture(e);
        }
    }
    */

    /**
     * Describe metadata quorum within a KRaft-based cluster
     *
     * @param reconciliation        Reconciliation marker
     * @param adminClientProvider   Kafka Admin API client provider
     * @param trustSet              Trust set for the admin client to connect to the Kafka cluster
     * @param authIdentity          Key set for the admin client to connect to the Kafka cluster
     *
     * @return  CompletableFuture that completes with the QuorumInfo containing voters and observers
     */
    public static CompletableFuture<QuorumInfo> describeMetadataQuorum(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            TrustSet trustSet,
            AuthIdentity authIdentity) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, trustSet, authIdentity);

            return adminClient.describeMetadataQuorum().quorumInfo()
                    .whenComplete((quorumInfo, t) -> {
                        if (t == null) {
                            LOGGER.infoCr(reconciliation, "Describe Metadata Quorum - voters: {}, observers: {}",
                                    quorumInfo.voters().size(), quorumInfo.observers().size());
                        }
                        adminClient.close();
                    })
                    .toCompletionStage()
                    .toCompletableFuture();
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to describe metadata quorum", e);
            return CompletableFuture.failedFuture(e);
        }
    }
}
