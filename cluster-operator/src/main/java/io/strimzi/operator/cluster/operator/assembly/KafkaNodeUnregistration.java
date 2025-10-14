/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.strimzi.api.kafka.model.kafka.KafkaResources;
//import io.strimzi.operator.cluster.model.DnsNameGenerator;
import io.strimzi.operator.cluster.model.KafkaCluster;
//import io.strimzi.operator.cluster.model.NodeRef;
import io.strimzi.operator.common.AdminClientProvider;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.auth.PemAuthIdentity;
import io.strimzi.operator.common.auth.PemTrustSet;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.QuorumInfo;
import org.apache.kafka.clients.admin.RaftVoterEndpoint;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;

import java.util.ArrayList;
import java.util.Collection;
//import java.util.HashSet;
import java.util.List;
import java.util.Set;
//import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

import static io.strimzi.operator.common.Util.unwrap;

/**
 * Contains utility methods for unregistering KRaft nodes from a Kafka cluster after scale-down
 */
// TODO: class to be renamed because it handls controllers registration as well
public class KafkaNodeUnregistration {
    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(KafkaNodeUnregistration.class.getName());

    private KafkaNodeUnregistration() { }

    /**
     * Unregisters Kafka broker nodes from a KRaft-based Kafka cluster
     *
     * @param reconciliation        Reconciliation marker
     * @param adminClientProvider   Kafka Admin API client provider
     * @param pemTrustSet           Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity       Key set  for the admin client to connect to the Kafka cluster
     * @param nodeIdsToUnregister   List of node IDs that should be unregistered
     *
     * @return  CompletableFuture that completes when all broker nodes are unregistered
     */
    public static CompletableFuture<Void> unregisterBrokerNodes(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            Set<Integer> nodeIdsToUnregister
    ) {
        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Integer nodeId : nodeIdsToUnregister) {
                futures.add(unregisterBrokerNode(reconciliation, adminClient, nodeId));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .whenComplete((v, t) -> {
                        adminClient.close();
                    });
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to unregister nodes", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * List registered Kafka broker nodes within a KRaft-based cluster
     *
     * @param reconciliation        Reconciliation marker
     * @param adminClientProvider   Kafka Admin API client provider
     * @param pemTrustSet           Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity       Key set  for the admin client to connect to the Kafka cluster
     * @param includeFencedBrokers  If listing should include fenced brokers
     *
     * @return  CompletableFuture that completes when all registered broker nodes are listed
     */
    public static CompletableFuture<Collection<Node>> listRegisteredBrokerNodes(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            boolean includeFencedBrokers) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

            DescribeClusterOptions option = new DescribeClusterOptions().includeFencedBrokers(includeFencedBrokers);

            return adminClient.describeCluster(option).nodes()
                    .whenComplete((nodes, t) -> {
                        if (t == null) {
                            LOGGER.debugCr(reconciliation, "Describe cluster: nodes (fenced included) = {}", nodes);
                        }
                        adminClient.close();
                    })
                    .toCompletionStage()
                    .toCompletableFuture();
        } catch (KafkaException e) {
            LOGGER.warnCr(reconciliation, "Failed to list nodes", e);
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
     * @return  CompletableFuture that completes when the node is unregistered
     */
    private static CompletableFuture<Void> unregisterBrokerNode(Reconciliation reconciliation, Admin adminClient, Integer nodeIdToUnregister) {
        LOGGER.debugCr(reconciliation, "Unregistering node {} from the Kafka cluster", nodeIdToUnregister);

        return adminClient.unregisterBroker(nodeIdToUnregister).all()
                .whenComplete((v, t) -> {
                    if (t != null) {
                        Throwable cause = unwrap(t);
                        LOGGER.warnCr(reconciliation, "Failed to unregister node {} from the Kafka cluster", nodeIdToUnregister, cause);
                    }
                })
                .toCompletionStage()
                .toCompletableFuture();
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
     * @param pemTrustSet               Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity           Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToRegister     List of NodeRef of the new KRaft controllers to be registered
     * @return  Future that completes when the KRaft controllers are registered
     */
    /*
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
    */

    /**
     * Register a list of newly added KRaft controllers
     *
     * @param reconciliation            Reconciliation marker
     * @param adminClientProvider       Kafka Admin API client provider
     * @param pemTrustSet               Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity           Key set  for the admin client to connect to the Kafka cluster
     * @param replicasToRegister        Set of ReplicaToRegister containing replica states with pre-constructed endpoints
     * @return  CompletableFuture that completes when the KRaft controllers are registered
     */
    public static CompletableFuture<Void> registerControllerReplicas(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            Set<KRaftQuorumReconciler.ReplicaToRegister> replicasToRegister) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

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
     * @param pemTrustSet               Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity           Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToUnregister   List of KRaft controllers IDs to be unregistered
     * @return  Future that completes when the KRaft controllers are unregistered
     */
    /*
    public static Future<Void> unregisterControllerNodes(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            Set<Integer> controllersToUnregister) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

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
     * @param pemTrustSet               Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity           Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToUnregister   Set of KRaft controller ReplicaState to be unregistered
     * @return  CompletableFuture that completes when the KRaft controllers are unregistered
     */
    public static CompletableFuture<Void> unregisterControllerReplicas(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            Set<QuorumInfo.ReplicaState> controllersToUnregister) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

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
     * @param pemTrustSet               Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity           Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToReconcile    List of NodeRef of the KRaft controllers to be reconciled (desired ones)
     * @return  Future that completes when the KRaft controllers are unregistered
     */
    /*
    public static Future<Void> reconcileKRaftQuorum(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            Set<NodeRef> controllersToReconcile) {

        try {
            LOGGER.infoCr(reconciliation, "**** controllersToReconcile: {}", controllersToReconcile);

            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

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
     * @param pemTrustSet               Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity           Key set  for the admin client to connect to the Kafka cluster
     * @param controllersToReconcile    List of NodeRef of the KRaft controllers to be reconciled (desired ones)
     * @return  Future that completes when the KRaft controllers are unregistered
     */
    /*
    public static Future<Void> reconcileKRaftQuorum(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity,
            Set<NodeRef> controllersToReconcile) {

        try {
            LOGGER.infoCr(reconciliation, "**** controllersToReconcile: {}", controllersToReconcile);

            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

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
     * @param pemTrustSet           Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity       Key set for the admin client to connect to the Kafka cluster
     *
     * @return  Future that completes when all quorum voters are listed
     */
    /*
    public static Future<Set<Integer>> listQuorumVoters(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

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
     * @param pemTrustSet           Trust set for the admin client to connect to the Kafka cluster
     * @param pemAuthIdentity       Key set for the admin client to connect to the Kafka cluster
     *
     * @return  CompletableFuture that completes with the QuorumInfo containing voters and observers
     */
    public static CompletableFuture<QuorumInfo> describeMetadataQuorum(
            Reconciliation reconciliation,
            AdminClientProvider adminClientProvider,
            PemTrustSet pemTrustSet,
            PemAuthIdentity pemAuthIdentity) {

        try {
            String bootstrapHostname = KafkaResources.bootstrapServiceName(reconciliation.name()) + "." + reconciliation.namespace() + ".svc:" + KafkaCluster.REPLICATION_PORT;
            Admin adminClient = adminClientProvider.createAdminClient(bootstrapHostname, pemTrustSet, pemAuthIdentity);

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
