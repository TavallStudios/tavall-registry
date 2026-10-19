package org.tavall.registry.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Orchestrates asynchronous reverse-dependency cascades across parent systems and child features.
 * <p>
 * <b>Architectural Role:</b> Coordinates non-blocking parent-to-child lifecycle cascades:
 * <ul>
 *   <li>Reverse-topological disable cascade awaiting each child completion asynchronously without blocking the host thread.</li>
 *   <li>Preserves child declarative desired states during parent disable cascades.</li>
 *   <li>Re-enables only children whose desired state is {@link ModuleDesiredState#ENABLED}.</li>
 *   <li>Reports honest partial failures if any child fails to quiesce, drain, or start.</li>
 *   <li>Restores modules with residual obligations to DISABLED after a restart.</li>
 * </ul>
 */
final class SystemCascadeCoordinator {

    private static final String RESIDUAL_RESTORE_ACTOR = "residual-obligation-restore";

    private final ModuleStateRegistry stateRegistry;
    private final HostLifecycleBoundary hostBoundary;
    private final BootstrapToken bootstrapToken;

    SystemCascadeCoordinator(
            ModuleStateRegistry stateRegistry,
            HostLifecycleBoundary hostBoundary,
            BootstrapToken bootstrapToken
    ) {
        this.stateRegistry = Objects.requireNonNull(stateRegistry, "stateRegistry must not be null");
        this.hostBoundary = Objects.requireNonNull(hostBoundary, "hostBoundary must not be null");
        this.bootstrapToken = Objects.requireNonNull(bootstrapToken, "bootstrapToken must not be null");
    }

    /**
     * Asynchronously disables all child features of a parent system in reverse topological order.
     *
     * @param runtimeEpochId current runtime epoch
     * @param systemId       parent system module ID
     * @param disableInvoker function invoking disableAsync for a child request
     * @return completion stage yielding a list of child disable results
     */
    CompletionStage<List<ModuleLifecycleResult>> cascadeDisableChildrenAsync(
            UUID runtimeEpochId,
            ModuleId systemId,
            Function<ModuleLifecycleRequest, CompletionStage<ModuleLifecycleResult>> disableInvoker
    ) {
        List<ModuleEntry> children = stateRegistry.findByParentSystem(systemId);
        List<ModuleEntry> reverseOrder = sortReverseTopologically(children);

        List<ModuleLifecycleResult> results = Collections.synchronizedList(new ArrayList<>());
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);

        for (ModuleEntry child : reverseOrder) {
            chain = chain.thenCompose(ignored -> {
                if (!child.state().isActive()) {
                    return CompletableFuture.completedFuture(null);
                }
                ModuleLifecycleRequest childReq = ModuleLifecycleRequest.internalCascade(
                        bootstrapToken,
                        runtimeEpochId,
                        "system-cascade",
                        child.descriptor().id(),
                        ModuleLifecycleOperation.DISABLE,
                        child.configGeneration(),
                        child.lifecycleGeneration()
                );
                return disableInvoker.apply(childReq).thenAccept(results::add);
            });
        }

        return chain.thenApply(ignored -> results);
    }

    /**
     * Asynchronously re-enables eligible child features of a parent system in topological order.
     * Only children whose declarative desiredState is ENABLED will be re-enabled.
     *
     * @param runtimeEpochId current runtime epoch
     * @param systemId       parent system module ID
     * @param enableInvoker  function invoking enableAsync for a child request
     * @return completion stage yielding a list of child enable results
     */
    CompletionStage<List<ModuleLifecycleResult>> cascadeEnableChildrenAsync(
            UUID runtimeEpochId,
            ModuleId systemId,
            Function<ModuleLifecycleRequest, CompletionStage<ModuleLifecycleResult>> enableInvoker
    ) {
        List<ModuleEntry> children = stateRegistry.findByParentSystem(systemId);
        List<ModuleEntry> topologicalOrder = sortTopologically(children);

        List<ModuleLifecycleResult> results = Collections.synchronizedList(new ArrayList<>());
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);

        for (ModuleEntry child : topologicalOrder) {
            chain = chain.thenCompose(ignored -> {
                // Invariant: Only re-enable children whose desiredState is ENABLED!
                if (child.desiredState() == ModuleDesiredState.ENABLED && !child.state().isActive()) {
                    ModuleLifecycleRequest childReq = ModuleLifecycleRequest.internalCascade(
                            bootstrapToken,
                            runtimeEpochId,
                            "system-cascade",
                            child.descriptor().id(),
                            ModuleLifecycleOperation.ENABLE,
                            child.configGeneration(),
                            child.lifecycleGeneration()
                    );
                    return enableInvoker.apply(childReq).thenAccept(results::add);
                }
                return CompletableFuture.completedFuture(null);
            });
        }

        return chain.thenApply(ignored -> results);
    }

    /**
     * Re-applies restored desired state to every root module (a SYSTEM or a module without a parent)
     * in dependency order. Roots whose desired state is ENABLED are enabled and their SYSTEM cascade
     * restores eligible children; an already-active SYSTEM only re-runs its child cascade. Desired
     * state is never mutated, so independently disabled FEATURES and disabled SYSTEMS stay inactive.
     *
     * @param runtimeEpochId current runtime epoch
     * @param enableInvoker  function invoking enableAsync for a root request
     * @return completion stage yielding the root and child enable results
     */
    CompletionStage<List<ModuleLifecycleResult>> reconcileDesiredRootsAsync(
            UUID runtimeEpochId,
            Function<ModuleLifecycleRequest, CompletionStage<ModuleLifecycleResult>> enableInvoker
    ) {
        hostBoundary.assertHostBoundary("reconcileDesiredRoots");
        List<ModuleEntry> roots = new ArrayList<>();
        for (ModuleEntry entry : stateRegistry.values()) {
            if (entry.descriptor().parentSystemId().isEmpty()) {
                roots.add(entry);
            }
        }
        List<ModuleEntry> order = sortTopologically(roots);

        List<ModuleLifecycleResult> results = Collections.synchronizedList(new ArrayList<>());
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (ModuleEntry root : order) {
            chain = chain.thenCompose(ignored -> {
                if (root.desiredState() != ModuleDesiredState.ENABLED) {
                    return CompletableFuture.completedFuture(null);
                }
                if (root.state().isActive()) {
                    if (root.descriptor().primaryType() != ModulePrimaryType.SYSTEM) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return cascadeEnableChildrenAsync(runtimeEpochId, root.descriptor().id(), enableInvoker)
                            .thenAccept(results::addAll);
                }
                ModuleLifecycleRequest rootReq = ModuleLifecycleRequest.internalCascade(
                        bootstrapToken,
                        runtimeEpochId,
                        "desired-state-reconcile",
                        root.descriptor().id(),
                        ModuleLifecycleOperation.ENABLE,
                        root.configGeneration(),
                        root.lifecycleGeneration()
                );
                return enableInvoker.apply(rootReq).thenAccept(results::add);
            });
        }
        return chain.thenApply(ignored -> List.copyOf(results));
    }

    /**
     * Restores residual obligations after a restart. A module that reconciliation left DISCOVERED is
     * loaded and then disabled when its participant declares residual capabilities, so a provider that
     * was disabled before the restart keeps servicing settlement, refunds and webhooks exactly as it
     * did before. Modules without residual capabilities stay unloaded, and desired state is never
     * mutated: the restored module reports DISABLED, not ENABLED.
     *
     * @param runtimeEpochId current runtime epoch
     * @param loadInvoker    function invoking loadAsync for a restore request
     * @param disableInvoker function invoking disableAsync for a restore request
     * @return completion stage yielding the load and disable results of every restored module
     */
    CompletionStage<List<ModuleLifecycleResult>> restoreResidualObligationsAsync(
            UUID runtimeEpochId,
            Function<ModuleLifecycleRequest, CompletionStage<ModuleLifecycleResult>> loadInvoker,
            Function<ModuleLifecycleRequest, CompletionStage<ModuleLifecycleResult>> disableInvoker
    ) {
        hostBoundary.assertHostBoundary("restoreResidualObligations");
        List<ModuleEntry> order = sortTopologically(new ArrayList<>(stateRegistry.values()));

        List<ModuleLifecycleResult> results = Collections.synchronizedList(new ArrayList<>());
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (ModuleEntry entry : order) {
            chain = chain.thenCompose(ignored -> {
                if (!declaresUnservedResidualObligations(entry)) {
                    return CompletableFuture.completedFuture(null);
                }
                ModuleLifecycleRequest loadReq = ModuleLifecycleRequest.internalCascade(
                        bootstrapToken,
                        runtimeEpochId,
                        RESIDUAL_RESTORE_ACTOR,
                        entry.descriptor().id(),
                        ModuleLifecycleOperation.LOAD,
                        entry.configGeneration(),
                        entry.lifecycleGeneration()
                );
                return loadInvoker.apply(loadReq).thenCompose(loaded -> {
                    results.add(loaded);
                    if (!loaded.succeeded()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    ModuleLifecycleRequest disableReq = ModuleLifecycleRequest.internalCascade(
                            bootstrapToken,
                            runtimeEpochId,
                            RESIDUAL_RESTORE_ACTOR,
                            entry.descriptor().id(),
                            ModuleLifecycleOperation.DISABLE,
                            entry.configGeneration(),
                            entry.lifecycleGeneration()
                    );
                    return disableInvoker.apply(disableReq).thenAccept(results::add);
                });
            });
        }
        return chain.thenApply(ignored -> List.copyOf(results));
    }

    /**
     * Reads the residual obligation policy from an unloaded participant instance. The policy is a static
     * property of the participant type (see {@link IModuleLifecycleParticipant#residualObligationPolicy()}),
     * so the instance is discarded without entering the lifecycle.
     */
    private boolean declaresUnservedResidualObligations(ModuleEntry entry) {
        if (entry.state() != ModuleLifecycleState.DISCOVERED) {
            return false;
        }
        IModuleLifecycleParticipant probe = entry.createParticipantInstance();
        return probe != null && !probe.residualObligationPolicy().residualCapabilities().isEmpty();
    }

    private List<ModuleEntry> sortTopologically(List<ModuleEntry> modules) {
        Map<ModuleId, ModuleEntry> map = new HashMap<>();
        for (ModuleEntry m : modules) {
            map.put(m.descriptor().id(), m);
        }

        List<ModuleEntry> sorted = new ArrayList<>(modules.size());
        Set<ModuleId> visited = new HashSet<>();
        Set<ModuleId> visiting = new HashSet<>();

        for (ModuleEntry entry : modules) {
            topologicalSortVisit(entry, map, visited, visiting, sorted);
        }
        return sorted;
    }

    List<ModuleEntry> sortReverseTopologically(List<ModuleEntry> modules) {
        List<ModuleEntry> sorted = sortTopologically(modules);
        Collections.reverse(sorted);
        return sorted;
    }

    private void topologicalSortVisit(
            ModuleEntry current,
            Map<ModuleId, ModuleEntry> map,
            Set<ModuleId> visited,
            Set<ModuleId> visiting,
            List<ModuleEntry> result
    ) {
        ModuleId id = current.descriptor().id();
        if (visited.contains(id)) {
            return;
        }
        if (visiting.contains(id)) {
            // Cycle detected: fallback to append to prevent infinite recursion
            visited.add(id);
            result.add(current);
            return;
        }

        visiting.add(id);
        // Parent system must start before its children (SYSTEM is a dependency of its child FEATUREs)
        current.descriptor().parentSystemId().ifPresent(parentId -> {
            ModuleEntry parentEntry = map.get(parentId);
            if (parentEntry != null) {
                topologicalSortVisit(parentEntry, map, visited, visiting, result);
            }
        });
        // Required dependencies must start before dependents
        for (ModuleId depId : current.descriptor().requiredDependencies()) {
            ModuleEntry depEntry = map.get(depId);
            if (depEntry != null) {
                topologicalSortVisit(depEntry, map, visited, visiting, result);
            }
        }
        visiting.remove(id);
        visited.add(id);
        result.add(current);
    }
}
