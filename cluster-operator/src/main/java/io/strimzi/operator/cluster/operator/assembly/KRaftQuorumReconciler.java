/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.Pod;
import io.strimzi.api.kafka.model.kafka.KafkaControllerStatus;
import io.strimzi.api.kafka.model.kafka.KafkaControllerStatusBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaResources;
import io.strimzi.operator.cluster.model.DnsNameGenerator;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.NodeRef;
import io.strimzi.operator.cluster.operator.resource.KafkaAgentClient;
import io.strimzi.operator.cluster.operator.resource.KafkaAgentClientProvider;
import io.strimzi.operator.cluster.operator.resource.kubernetes.PodOperator;
import io.strimzi.operator.common.AdminClientProvider;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.auth.TlsPemIdentity;
import io.strimzi.operator.common.model.Labels;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.kafka.clients.admin.QuorumInfo;
import org.apache.kafka.clients.admin.RaftVoterEndpoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles KRaft quorum reconciliation logic for Kafka controllers.
 * This class centralizes the logic for analyzing quorum state, detecting disk changes,
 * and orchestrating controller registration/unregistration operations.
 */
public class KRaftQuorumReconciler {
    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(KRaftQuorumReconciler.class.getName());

    // Constants for controller endpoint construction
    private static final String CONTROLPLANE_LISTENER_NAME = "CONTROLPLANE-9090";
    private static final int CONTROLPLANE_PORT = 9090;

    // Instance fields
    private final Reconciliation reconciliation;
    private final Vertx vertx;
    private final AdminClientProvider adminClientProvider;
    private final KafkaAgentClientProvider kafkaAgentClientProvider;
    private final TlsPemIdentity coTlsPemIdentity;
    private final PodOperator podOperator;

    /**
     * Constructs a new KRaftQuorumReconciler instance.
     *
     * @param reconciliation            Reconciliation marker
     * @param vertx                     Vert.x instance
     * @param adminClientProvider       Kafka Admin API client provider
     * @param kafkaAgentClientProvider  Provider for Kafka agent client
     * @param coTlsPemIdentity          TLS identity for cluster operator (provides trust set and auth identity)
     * @param podOperator               Pod operator for accessing pods
     */
    public KRaftQuorumReconciler(
            Reconciliation reconciliation,
            Vertx vertx,
            AdminClientProvider adminClientProvider,
            KafkaAgentClientProvider kafkaAgentClientProvider,
            TlsPemIdentity coTlsPemIdentity,
            PodOperator podOperator) {
        this.reconciliation = reconciliation;
        this.vertx = vertx;
        this.adminClientProvider = adminClientProvider;
        this.kafkaAgentClientProvider = kafkaAgentClientProvider;
        this.coTlsPemIdentity = coTlsPemIdentity;
        this.podOperator = podOperator;
    }

    /**
     * Represents changes needed to the KRaft quorum.
     *
     * @param toRegister   List of replica states that need to be registered as voters
     * @param toUnregister List of replica states that need to be unregistered from voters
     */
    record QuorumChanges(List<QuorumInfo.ReplicaState> toRegister, List<QuorumInfo.ReplicaState> toUnregister) { }

    /**
     * Bundles a controller replica state with its pre-constructed endpoint for registration.
     * This ensures endpoint construction happens in KRaftQuorumReconciler using createControllerEndpoint(),
     * providing a single source of truth for endpoint construction logic.
     *
     * @param replicaState  The replica state from quorum info (contains node ID and directory ID)
     * @param endpoint      The pre-constructed RaftVoterEndpoint for this controller
     */
    record ReplicaToRegister(QuorumInfo.ReplicaState replicaState, RaftVoterEndpoint endpoint) { }

    /**
     * Creates a RaftVoterEndpoint for a controller node.
     * This method constructs the advertised listener endpoint for a controller.
     *
     * @param controller The controller node reference
     * @return RaftVoterEndpoint for the controller
     */
    RaftVoterEndpoint createControllerEndpoint(NodeRef controller) {
        String host = DnsNameGenerator.podDnsNameWithoutClusterDomain(
                reconciliation.namespace(),
                KafkaResources.brokersServiceName(reconciliation.name()),
                controller.podName());

        return new RaftVoterEndpoint(CONTROLPLANE_LISTENER_NAME, host, CONTROLPLANE_PORT);
    }

    /**
     * Builds controller statuses from the final quorum state.
     * This method converts quorum voter information into KafkaControllerStatus objects.
     *
     * @param quorumInfo The current quorum information
     * @return List of controller statuses derived from voters
     */
    List<KafkaControllerStatus> buildControllerStatuses(QuorumInfo quorumInfo) {
        List<KafkaControllerStatus> statuses = new ArrayList<>();
        for (QuorumInfo.ReplicaState voter : quorumInfo.voters()) {
            statuses.add(new KafkaControllerStatusBuilder()
                    .withId(voter.replicaId())
                    .withDirectoryId(voter.replicaDirectoryId().toString())
                    .build());
        }
        return statuses;
    }

    /**
     * Reads the directory ID from a controller pod's meta.properties file.
     *
     * @param controller The controller node reference
     * @return Future containing the directory ID string, or null if it cannot be read
     */
    Future<String> readDirectoryIdFromPod(NodeRef controller) {
        return vertx.executeBlocking(() -> {
            try {
                KafkaAgentClient agentClient = kafkaAgentClientProvider.createKafkaAgentClient(reconciliation, coTlsPemIdentity);
                return agentClient.getDirectoryId(controller.podName());
            } catch (Exception e) {
                LOGGER.warnCr(reconciliation, "Failed to read directory ID from pod {}", controller.podName(), e);
                return null;
            }
        });
    }

    /**
     * Reconciles a single controller after it has been rolled.
     * This method is used by KafkaRoller to handle disk changes for a just-restarted controller.
     * It analyzes only the specified controller and performs necessary register/unregister operations.
     *
     * @param controller    The controller node that was just rolled
     * @return Future that completes when the controller quorum membership has been reconciled
     */
    public Future<Void> reconcileSingleController(NodeRef controller) {
        LOGGER.infoCr(reconciliation, "Reconciling single controller {}", controller.nodeId());

        List<QuorumInfo.ReplicaState> toRegister = new ArrayList<>();
        List<QuorumInfo.ReplicaState> toUnregister = new ArrayList<>();

        return KafkaNodeUnregistration.describeMetadataQuorum(
                        reconciliation, vertx, adminClientProvider,
                        coTlsPemIdentity.pemTrustSet(), coTlsPemIdentity.pemAuthIdentity())
                .compose(quorumInfo -> {
                    // Analyze just this single controller
                    // Pass null for statusControllers - we don't need it for single controller reconciliation
                    return analyzeControllerNode(controller, quorumInfo, null, toRegister, toUnregister);
                })
                .compose(v -> executeQuorumChanges(new QuorumChanges(toRegister, toUnregister), Set.of(controller)))
                .onSuccess(v -> LOGGER.infoCr(reconciliation, "Successfully reconciled single controller {}", controller.nodeId()))
                .onFailure(t -> LOGGER.warnCr(reconciliation, "Failed to reconcile single controller {}", controller.nodeId(), t));
    }

    /**
     * Reconciles the full KRaft controller quorum.
     * This method is used by KafkaReconciler to perform complete quorum reconciliation,
     * handling scale-up, scale-down, and disk changes across all controllers.
     *
     * @param desiredControllers    Set of all controllers that should be in the quorum
     * @param kafka                 Kafka cluster model
     * @return Future containing the final list of controller statuses
     */
    public Future<List<KafkaControllerStatus>> reconcileControllerQuorum(Set<NodeRef> desiredControllers, KafkaCluster kafka) {
        LOGGER.infoCr(reconciliation, "Reconciling KRaft quorum with controllers: {}", desiredControllers);

        return KafkaNodeUnregistration.describeMetadataQuorum(
                        reconciliation, vertx, adminClientProvider,
                        coTlsPemIdentity.pemTrustSet(), coTlsPemIdentity.pemAuthIdentity())
                .compose(quorumInfo -> analyzeQuorumChanges(quorumInfo, desiredControllers, kafka))
                .compose(changes -> executeQuorumChanges(changes, desiredControllers))
                .compose(v -> {
                    // Read final quorum state to get directory IDs for status
                    return KafkaNodeUnregistration.describeMetadataQuorum(
                            reconciliation, vertx, adminClientProvider,
                            coTlsPemIdentity.pemTrustSet(), coTlsPemIdentity.pemAuthIdentity());
                })
                .compose(finalQuorumInfo -> {
                    // Build and return controller statuses from the final quorum state
                    List<KafkaControllerStatus> statuses = buildControllerStatuses(finalQuorumInfo);
                    LOGGER.infoCr(reconciliation, "Successfully reconciled KRaft quorum, controller statuses: {}", statuses);
                    return Future.succeededFuture(statuses);
                });
    }

    /**
     * Executes quorum changes by unregistering stale replicas then registering new ones.
     * This is the core reconciliation logic shared by both single and full quorum reconciliation.
     *
     * @param changes                   QuorumChanges containing replicas to register/unregister
     * @param nodeRefsForRegistration   NodeRefs to pass to registration (needed to construct endpoints)
     * @return Future that completes when both phases are done
     */
    private Future<Void> executeQuorumChanges(QuorumChanges changes, Set<NodeRef> nodeRefsForRegistration) {
        // Phase 1: Unregister first (allows disk changes where node already exists as voter)
        Future<Void> unregisterFuture;
        if (changes.toUnregister().isEmpty()) {
            LOGGER.infoCr(reconciliation, "No controllers to unregister");
            unregisterFuture = Future.succeededFuture();
        } else {
            LOGGER.infoCr(reconciliation, "Unregistering {} controllers from quorum", changes.toUnregister().size());
            unregisterFuture = KafkaNodeUnregistration.unregisterControllerReplicas(
                    reconciliation, vertx, adminClientProvider,
                    coTlsPemIdentity.pemTrustSet(), coTlsPemIdentity.pemAuthIdentity(),
                    new HashSet<>(changes.toUnregister()));
        }

        // Phase 2: Register after (adds new voters or re-adds after disk change)
        return unregisterFuture.compose(v -> {
            if (changes.toRegister().isEmpty()) {
                LOGGER.infoCr(reconciliation, "No controllers to register");
                return Future.succeededFuture();
            }

            // Build ReplicaToRegister set with pre-constructed endpoints
            Set<ReplicaToRegister> replicasWithEndpoints = changes.toRegister().stream()
                    .map(replicaState -> {
                        NodeRef nodeRef = nodeRefsForRegistration.stream()
                                .filter(nr -> nr.nodeId() == replicaState.replicaId())
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("NodeRef not found for replicaId: " + replicaState.replicaId()));
                        RaftVoterEndpoint endpoint = createControllerEndpoint(nodeRef);
                        return new ReplicaToRegister(replicaState, endpoint);
                    })
                    .collect(Collectors.toSet());

            LOGGER.infoCr(reconciliation, "Registering {} controllers to quorum", replicasWithEndpoints.size());
            return KafkaNodeUnregistration.registerControllerReplicas(
                    reconciliation, vertx, adminClientProvider,
                    coTlsPemIdentity.pemTrustSet(), coTlsPemIdentity.pemAuthIdentity(),
                    replicasWithEndpoints);
        });
    }

    /**
     * Analyzes the current quorum state and determines what changes are needed.
     * This method checks all desired controllers and determines which ones need to be
     * registered or unregistered based on the current quorum state.
     *
     * @param quorumInfo            Current quorum information from Kafka
     * @param desiredControllers    Set of controllers that should be in the quorum
     * @param kafka                 Kafka cluster model
     * @return Future containing QuorumChanges with lists of replicas to register/unregister
     */
    Future<QuorumChanges> analyzeQuorumChanges(QuorumInfo quorumInfo, Set<NodeRef> desiredControllers, KafkaCluster kafka) {
        Set<Integer> desiredVoters = desiredControllers.stream()
                .map(NodeRef::nodeId)
                .collect(Collectors.toSet());

        List<QuorumInfo.ReplicaState> toRegister = new ArrayList<>();
        List<QuorumInfo.ReplicaState> toUnregister = new ArrayList<>();

        // Fetch all pods to check controller role labels
        return podOperator.listAsync(reconciliation.namespace(), kafka.getSelectorLabels())
                .compose(pods -> {
                    // Create a map of pod name to pod for easy lookup
                    Map<String, Pod> podMap = pods.stream()
                            .collect(Collectors.toMap(pod -> pod.getMetadata().getName(), pod -> pod));

                    // PHASE 1: Analyze desired controllers
                    List<Future<Void>> analysisFutures = new ArrayList<>();

                    for (NodeRef controller : desiredControllers) {
                        Pod pod = podMap.get(controller.podName());

                        // Check if the pod has been rolled as a controller by checking the controller role label.
                        // We use 'false' as default to be conservative: we only proceed with registration when we can
                        // confirm the pod has actually been rolled with the controller role. If the pod doesn't exist
                        // or the label is missing/false, we skip it and wait for the rolling process to complete first.
                        // This prevents attempting to register nodes that are desired to be controllers but haven't yet
                        // been actualized as controllers (e.g., after a role change from broker-only to broker+controller).
                        boolean isActuallyController = Labels.booleanLabel(pod, Labels.STRIMZI_CONTROLLER_ROLE_LABEL, false);

                        if (!isActuallyController) {
                            LOGGER.infoCr(reconciliation, "Controller {} is desired but not yet rolled as controller (missing {} label), skipping registration analysis",
                                    controller.nodeId(), Labels.STRIMZI_CONTROLLER_ROLE_LABEL);
                            continue;
                        }

                        Future<Void> analysisFuture = analyzeControllerNode(controller, quorumInfo, kafka.getKafkaControllerStatuses(), toRegister, toUnregister);
                        analysisFutures.add(analysisFuture);
                    }

                    // PHASE 2: Handle scale-down (voters not in desired)
                    for (QuorumInfo.ReplicaState voter : quorumInfo.voters()) {
                        if (!desiredVoters.contains(voter.replicaId())) {
                            LOGGER.infoCr(reconciliation, "Controller {} is in voters but not in desired controllers, will unregister", voter.replicaId());
                            toUnregister.add(voter);
                        }
                    }

                    return Future.join(analysisFutures)
                            .map(v -> new QuorumChanges(toRegister, toUnregister));
                });
    }

    /**
     * Analyzes a single controller node to determine if registration/unregistration is needed.
     * This is the core reusable logic used by both full quorum reconciliation and single controller reconciliation.
     *
     * @param controller        The controller node to analyze
     * @param quorumInfo        Current quorum information
     * @param statusControllers Current controller statuses from Kafka status
     * @param toRegister        Accumulator list for replicas that need registration
     * @param toUnregister      Accumulator list for replicas that need unregistration
     * @return Future that completes when analysis is done
     */
    Future<Void> analyzeControllerNode(NodeRef controller, QuorumInfo quorumInfo,
                                       List<KafkaControllerStatus> statusControllers,
                                       List<QuorumInfo.ReplicaState> toRegister,
                                       List<QuorumInfo.ReplicaState> toUnregister) {
        int nodeId = controller.nodeId();
        String expectedDirId = statusControllers != null
                ? statusControllers.stream()
                        .filter(c -> c.getId() == nodeId)
                        .map(KafkaControllerStatus::getDirectoryId)
                        .findFirst()
                        .orElse(null)
                : null;

        // Look for this node ID within both voters and observers (in case of old and new incarnation, i.e. disk change)
        List<QuorumInfo.ReplicaState> voters = quorumInfo.voters().stream()
                .filter(rs -> rs.replicaId() == nodeId)
                .collect(Collectors.toList());
        List<QuorumInfo.ReplicaState> observers = quorumInfo.observers().stream()
                .filter(rs -> rs.replicaId() == nodeId)
                .collect(Collectors.toList());

        // Disk change recovery scenario: multiple voters, multiple observers, or voter+observer
        if (voters.size() > 1 || observers.size() > 1 || (!voters.isEmpty() && !observers.isEmpty())) {
            LOGGER.infoCr(reconciliation,
                    "Controller {} has multiple incarnations (voters: {}, observers: {}), reading meta.properties",
                    nodeId, voters.size(), observers.size());

            // Read actual directory ID from pod to determine which is current
            return readDirectoryIdFromPod(controller)
                    .compose(actualDirId -> {
                        if (actualDirId == null) {
                            LOGGER.warnCr(reconciliation, "Could not read directory ID from pod {}, failing reconciliation to retry later", controller.podName());
                            return Future.failedFuture(new RuntimeException("Could not read directory ID from pod " + controller.podName()));
                        }

                        // Unregister any voters with wrong directory ID
                        for (QuorumInfo.ReplicaState voter : voters) {
                            if (!voter.replicaDirectoryId().toString().equals(actualDirId)) {
                                LOGGER.infoCr(reconciliation, "Controller {} voter has stale directory ID {} (actual: {}), will unregister", nodeId, voter.replicaDirectoryId(), actualDirId);
                                toUnregister.add(voter);
                            }
                        }

                        // Register observer with correct directory ID if not already a voter
                        boolean hasCorrectVoter = voters.stream()
                                .anyMatch(v -> v.replicaDirectoryId().toString().equals(actualDirId));

                        if (!hasCorrectVoter) {
                            QuorumInfo.ReplicaState correctObserver = observers.stream()
                                    .filter(obs -> obs.replicaDirectoryId().toString().equals(actualDirId))
                                    .findFirst()
                                    .orElse(null);

                            if (correctObserver != null) {
                                LOGGER.infoCr(reconciliation, "Controller {} observer has correct directory ID {}, will register", nodeId, actualDirId);
                                toRegister.add(correctObserver);
                            }
                        }

                        return Future.succeededFuture();
                    });
        } else if (expectedDirId == null) {
            // New node, not in status yet
            if (!observers.isEmpty()) {
                LOGGER.infoCr(reconciliation, "Controller {} is new (not in status), observer present, will register", nodeId);
                toRegister.add(observers.get(0));
            } else if (!voters.isEmpty()) {
                // Already a voter, nothing to do
                LOGGER.debugCr(reconciliation, "Controller {} is new but already in voters", nodeId);
            }
            return Future.succeededFuture();
        } else if (!voters.isEmpty() && observers.isEmpty()) {
            // Controller is only in voters (no observer), it's already correct, leave it alone
            // Status might be stale (not synced yet after KafkaRoller changes)
            LOGGER.debugCr(reconciliation, "Controller {} is only in voters - already correct", nodeId);
            return Future.succeededFuture();
        } else {
            // Only in observers, since multiple observers already handled above, this is a single observer
            // Register it without comparing to expectedDirId (which could be stale)
            if (!observers.isEmpty()) {
                LOGGER.infoCr(reconciliation, "Controller {} is only in observers - will register", nodeId);
                toRegister.add(observers.get(0));
            }

            return Future.succeededFuture();
        }
    }
}
