package org.tavall.registry.module;

import org.tavall.dependency.annotations.DelegatesTo;
import org.tavall.dependency.maps.DependencyMap;
import org.tavall.dependency.maps.interfaces.IDependencyMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Canonical in-process lifecycle registry and state coordinator for Tavall modules.
 * <p>
 * <b>Architectural Role:</b> Core system coordinator managing in-process module lifecycles across Tavall
 * runtimes. Coordinates declarative desired states, observed transitions, single-host DI container bindings,
 * reverse-dependency cascading, optimistic fences, and snapshot persistence.
 * <p>
 * <b>Host Serialization (Paper-Safe):</b> All state transitions and context mutations are serialized
 * through {@link HostLifecycleBoundary} and validated against the designated host thread. Stage completions
 * are marshaled back to the host boundary.
 * <p>
 * <b>DI Container Ownership:</b> Integrates directly with the single host {@link IDependencyMap}.
 * Handles extending {@link org.tavall.dependency.GenerationOwnedDependencyAccess} fail closed upon reload/unload.
 */
@DelegatesTo(IModuleLifecycleRegistry.class)
public class ModuleLifecycleRegistry implements IModuleLifecycleRegistry {

    private final UUID runtimeEpochId;
    private final RuntimeTarget runtimeTarget;
    private final IDependencyMap dependencyMap;
    private final AtomicLong runtimeConfigGeneration = new AtomicLong(1L);
    private final Map<ModuleId, ModuleDesiredState> restoredDesiredStates = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> restoreCompletion = new CompletableFuture<>();

    private BootstrapToken bootstrapToken;
    private HostLifecycleBoundary hostBoundary;
    private ModuleStateRegistry stateRegistry;
    private ReplayIdempotencyHandler idempotencyHandler;
    private SystemCascadeCoordinator cascadeCoordinator;
    private IModuleLifecycleAuthorizer authorizer;
    private IModuleDesiredStateSnapshotProvider snapshotProvider;
    private IRollbackCompatibilityValidator rollbackValidator;

    private volatile boolean configured;

    /**
     * Default no-arg constructor for DI container discovery and instantiation.
     * <p>
     * <b>Security Invariant:</b> Creates an unconfigured, fail-closed component. It does NOT invent
     * fake host boundaries or permissive authorizers. Before processing lifecycle mutations,
     * the host environment must invoke {@link #configure}.
     */
    public ModuleLifecycleRegistry() {
        this.runtimeEpochId = UUID.randomUUID();
        this.runtimeTarget = RuntimeTarget.APPLICATION;
        this.dependencyMap = DependencyMap.getDependencyMap();
        this.configured = false;
        this.idempotencyHandler = new ReplayIdempotencyHandler();
    }

    /**
     * Fully configured production constructor.
     *
     * @param runtimeTarget      execution target host; must not be null
     * @param dependencyMap      single host DI container; must not be null
     * @param hostBoundary       Paper-safe host lifecycle execution boundary; must not be null
     * @param authorizer         authorization callback; must not be null
     * @param snapshotProvider   durable desired state snapshot provider; must not be null
     * @param rollbackValidator  rollback compatibility validator; must not be null
     */
    public ModuleLifecycleRegistry(
            RuntimeTarget runtimeTarget,
            IDependencyMap dependencyMap,
            HostLifecycleBoundary hostBoundary,
            IModuleLifecycleAuthorizer authorizer,
            IModuleDesiredStateSnapshotProvider snapshotProvider,
            IRollbackCompatibilityValidator rollbackValidator
    ) {
        this(UUID.randomUUID(), runtimeTarget, dependencyMap, hostBoundary, authorizer, snapshotProvider, rollbackValidator);
    }

    /**
     * Fully configured constructor with an explicit runtime boot instance epoch.
     */
    public ModuleLifecycleRegistry(
            UUID runtimeEpochId,
            RuntimeTarget runtimeTarget,
            IDependencyMap dependencyMap,
            HostLifecycleBoundary hostBoundary,
            IModuleLifecycleAuthorizer authorizer,
            IModuleDesiredStateSnapshotProvider snapshotProvider,
            IRollbackCompatibilityValidator rollbackValidator
    ) {
        this.runtimeEpochId = Objects.requireNonNull(runtimeEpochId, "runtimeEpochId must not be null");
        this.runtimeTarget = Objects.requireNonNull(runtimeTarget, "runtimeTarget must not be null");
        this.dependencyMap = Objects.requireNonNull(dependencyMap, "dependencyMap must not be null");
        configure(hostBoundary, authorizer, snapshotProvider, rollbackValidator);
    }

    /**
     * Configures this coordinator with required host boundary, authorizer, and snapshot authority.
     */
    public synchronized void configure(
            HostLifecycleBoundary hostBoundary,
            IModuleLifecycleAuthorizer authorizer,
            IModuleDesiredStateSnapshotProvider snapshotProvider,
            IRollbackCompatibilityValidator rollbackValidator
    ) {
        this.hostBoundary = Objects.requireNonNull(hostBoundary, "hostBoundary must not be null");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider must not be null");
        this.rollbackValidator = Objects.requireNonNull(rollbackValidator, "rollbackValidator must not be null");

        this.bootstrapToken = new BootstrapToken(this.runtimeEpochId);
        this.stateRegistry = new ModuleStateRegistry(runtimeTarget);
        this.idempotencyHandler = new ReplayIdempotencyHandler();
        this.cascadeCoordinator = new SystemCascadeCoordinator(stateRegistry, hostBoundary, bootstrapToken);
        this.configured = true;

        // Automatically load and restore previous authoritative desired state snapshot asynchronously
        loadAndApplyInitialSnapshot();
    }

    @Override
    public boolean isReady() {
        return configured && restoreCompletion.isDone() && !restoreCompletion.isCompletedExceptionally();
    }

    @Override
    public CompletionStage<Void> whenReady() {
        return restoreCompletion;
    }

    @Override
    public UUID runtimeEpochId() {
        return runtimeEpochId;
    }

    @Override
    public RuntimeTarget runtimeTarget() {
        return runtimeTarget;
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    public HostLifecycleBoundary hostBoundary() {
        return hostBoundary;
    }

    public void assertHostBoundary() {
        if (!configured || hostBoundary == null) {
            throw new IllegalStateException("Host serialization violation: coordinator is unconfigured");
        }
        hostBoundary.assertHostBoundary();
    }

    // --------------------------------------------------------------------------------------------
    // Registration API
    // --------------------------------------------------------------------------------------------

    @Override
    public void registerModule(
            ModuleDescriptor descriptor,
            Supplier<IModuleLifecycleParticipant> participantSupplier
    ) {
        registerModule(descriptor, participantSupplier, null);
    }

    @Override
    public void registerModule(
            ModuleDescriptor descriptor,
            Supplier<IModuleLifecycleParticipant> participantSupplier,
            IModuleHealthCheck healthCheck
    ) {
        requireReady();
        Objects.requireNonNull(descriptor, "descriptor must not be null");

        synchronized (hostBoundary.monitor()) {
            assertHostBoundary();
            ModuleEntry entry = new ModuleEntry(descriptor, participantSupplier, healthCheck);
            entry.setConfigGeneration(runtimeConfigGeneration.get());
            ModuleDesiredState restored = restoredDesiredStates.get(descriptor.id());
            if (restored != null) {
                entry.setDesiredState(restored);
            }
            stateRegistry.registerIndexed(descriptor.id(), entry);
        }
    }

    @Override
    public void unregisterModule(ModuleId moduleId) {
        requireReady();
        Objects.requireNonNull(moduleId, "moduleId must not be null");

        synchronized (hostBoundary.monitor()) {
            assertHostBoundary();
            ModuleEntry entry = stateRegistry.get(moduleId);
            if (entry != null && (entry.state().isActive() || entry.state().isLoaded())) {
                throw new IllegalStateException("Cannot unregister active or loaded module " + moduleId + " in state " + entry.state());
            }
            stateRegistry.unregisterIndexed(moduleId);
        }
    }

    // --------------------------------------------------------------------------------------------
    // Synchronous Convenience Methods (Fail-Fast on Host Thread to Prevent Deadlock)
    // --------------------------------------------------------------------------------------------

    @Override
    public ModuleLifecycleResult load(ModuleLifecycleRequest request) {
        return awaitCompletionNonBlocking(loadAsync(request), request);
    }

    @Override
    public ModuleLifecycleResult enable(ModuleLifecycleRequest request) {
        return awaitCompletionNonBlocking(enableAsync(request), request);
    }

    @Override
    public ModuleLifecycleResult disable(ModuleLifecycleRequest request) {
        return awaitCompletionNonBlocking(disableAsync(request), request);
    }

    @Override
    public ModuleLifecycleResult reload(ModuleLifecycleRequest request) {
        return awaitCompletionNonBlocking(reloadAsync(request), request);
    }

    @Override
    public ModuleLifecycleResult unload(ModuleLifecycleRequest request) {
        return awaitCompletionNonBlocking(unloadAsync(request), request);
    }

    @Override
    public ModuleLifecycleResult rollback(ModuleLifecycleRequest request) {
        return awaitCompletionNonBlocking(rollbackAsync(request), request);
    }

    // --------------------------------------------------------------------------------------------
    // Asynchronous Lifecycle Operations
    // --------------------------------------------------------------------------------------------

    @Override
    public CompletionStage<ModuleLifecycleResult> loadAsync(ModuleLifecycleRequest request) {
        return executeLifecycleOperation(request, () -> {
            CompletableFuture<ModuleLifecycleResult> future = new CompletableFuture<>();
            dispatchToHost(() -> {
                try {
                    future.complete(doLoad(request));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, future);
            return future;
        });
    }

    @Override
    public CompletionStage<ModuleLifecycleResult> enableAsync(ModuleLifecycleRequest request) {
        return executeLifecycleOperation(request, () -> {
            CompletableFuture<ModuleLifecycleResult> future = new CompletableFuture<>();
            dispatchToHost(() -> {
                try {
                    doEnable(request).whenComplete((res, err) -> {
                        if (err != null) {
                            future.completeExceptionally(unwrap(err));
                        } else {
                            future.complete(res);
                        }
                    });
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, future);
            return future;
        });
    }

    @Override
    public CompletionStage<ModuleLifecycleResult> disableAsync(ModuleLifecycleRequest request) {
        return executeLifecycleOperation(request, () -> {
            CompletableFuture<ModuleLifecycleResult> future = new CompletableFuture<>();
            dispatchToHost(() -> {
                try {
                    doDisable(request).whenComplete((res, err) -> {
                        if (err != null) {
                            future.completeExceptionally(unwrap(err));
                        } else {
                            future.complete(res);
                        }
                    });
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, future);
            return future;
        });
    }

    @Override
    public CompletionStage<ModuleLifecycleResult> reloadAsync(ModuleLifecycleRequest request) {
        return executeLifecycleOperation(request, () -> {
            CompletableFuture<ModuleLifecycleResult> future = new CompletableFuture<>();
            dispatchToHost(() -> {
                try {
                    doReload(request).whenComplete((res, err) -> {
                        if (err != null) {
                            future.completeExceptionally(unwrap(err));
                        } else {
                            future.complete(res);
                        }
                    });
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, future);
            return future;
        });
    }

    @Override
    public CompletionStage<ModuleLifecycleResult> unloadAsync(ModuleLifecycleRequest request) {
        return executeLifecycleOperation(request, () -> {
            CompletableFuture<ModuleLifecycleResult> future = new CompletableFuture<>();
            dispatchToHost(() -> {
                try {
                    doUnload(request).whenComplete((res, err) -> {
                        if (err != null) {
                            future.completeExceptionally(unwrap(err));
                        } else {
                            future.complete(res);
                        }
                    });
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, future);
            return future;
        });
    }

    @Override
    public CompletionStage<ModuleLifecycleResult> rollbackAsync(ModuleLifecycleRequest request) {
        return executeLifecycleOperation(request, () -> {
            CompletableFuture<ModuleLifecycleResult> future = new CompletableFuture<>();
            dispatchToHost(() -> {
                try {
                    doRollback(request).whenComplete((res, err) -> {
                        if (err != null) {
                            future.completeExceptionally(unwrap(err));
                        } else {
                            future.complete(res);
                        }
                    });
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, future);
            return future;
        });
    }

    private CompletionStage<ModuleLifecycleResult> executeLifecycleOperation(
            ModuleLifecycleRequest request,
            Supplier<CompletionStage<ModuleLifecycleResult>> operation
    ) {
        Objects.requireNonNull(request, "request must not be null");
        if (!configured) {
            return CompletableFuture.completedFuture(unconfiguredResult(request));
        }

        return restoreCompletion.handle((val, err) -> {
            if (err != null) {
                return CompletableFuture.completedFuture(initializationFailedResult(request, unwrap(err)));
            }
            return preFlightCheck(request).thenCompose(preFlightResult -> {
                if (preFlightResult != null) {
                    return CompletableFuture.completedFuture(preFlightResult);
                }
                CompletionStage<ModuleLifecycleResult> stage;
                try {
                    stage = operation.get();
                } catch (RuntimeException failure) {
                    stage = CompletableFuture.failedFuture(failure);
                }
                // A reservation taken in preFlightCheck must never outlive an exceptional completion,
                // otherwise every later request for the module reports conflict_in_flight.
                return stage.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        idempotencyHandler.clearInFlight(request);
                    }
                });
            });
        }).thenCompose(stage -> stage);
    }

    // --------------------------------------------------------------------------------------------
    // Internal Lifecycle Execution (Synchronized on hostBoundary.monitor())
    // --------------------------------------------------------------------------------------------

    private ModuleLifecycleResult doLoad(ModuleLifecycleRequest request) {
        assertHostBoundary();
        ModuleEntry entry = stateRegistry.get(request.moduleId());
        if (entry == null) {
            ModuleLifecycleResult fail = unknownModuleResult(request);
            idempotencyHandler.recordCompletion(request, fail);
            return fail;
        }

        // Fencing
        ModuleFailure fence = checkFences(request, entry);
        if (fence != null) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(), fence);
            idempotencyHandler.recordCompletion(request, fail);
            return fail;
        }

        // Idempotency: already loaded or active
        if (entry.state() == ModuleLifecycleState.LOADED || entry.state() == ModuleLifecycleState.ENABLED) {
            ModuleLifecycleResult succ = successResult(entry, request.operation(), entry.state(), false);
            idempotencyHandler.recordCompletion(request, succ);
            return succ;
        }

        // Verify required dependencies are loaded
        Set<ModuleId> missing = new LinkedHashSet<>();
        for (ModuleId depId : entry.descriptor().requiredDependencies()) {
            ModuleEntry depEntry = stateRegistry.get(depId);
            if (depEntry == null || !depEntry.state().isLoaded()) {
                missing.add(depId);
            }
        }
        if (!missing.isEmpty()) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                    ModuleFailure.of("missing_required_dependencies", "Required dependencies are not loaded: " + missing));
            idempotencyHandler.recordCompletion(request, fail);
            return fail;
        }

        // Increment lifecycle generation
        long nextGen = entry.lifecycleGeneration() + 1L;
        entry.setLifecycleGeneration(nextGen);

        ScopedModuleContext context = new ScopedModuleContext(
                entry.descriptor(),
                nextGen,
                dependencyMap,
                this::assertHostBoundary
        );
        entry.setContext(context);

        // Instantiate participant
        IModuleLifecycleParticipant participant = entry.createParticipantInstance();
        entry.setActiveParticipant(participant);

        ModuleLifecycleState previousState = entry.state();
        entry.transition(ModuleLifecycleState.LOADED);

        ModuleLifecycleResult result = successResult(entry, request.operation(), previousState, true);
        idempotencyHandler.recordCompletion(request, result);
        return result;
    }

    private CompletionStage<ModuleLifecycleResult> doEnable(ModuleLifecycleRequest request) {
        assertHostBoundary();
        ModuleEntry entry = stateRegistry.get(request.moduleId());
        if (entry == null) {
            ModuleLifecycleResult fail = unknownModuleResult(request);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Fencing
        ModuleFailure fence = checkFences(request, entry);
        if (fence != null) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(), fence);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Check required dependencies:
        // 1. Missing or unloaded dependencies: "missing_required_dependencies"
        // 2. Inactive dependencies: "required_dependencies_not_active"
        Set<ModuleId> missingDeps = new LinkedHashSet<>();
        Set<ModuleId> inactiveDeps = new LinkedHashSet<>();
        for (ModuleId depId : entry.descriptor().requiredDependencies()) {
            ModuleEntry depEntry = stateRegistry.get(depId);
            if (depEntry == null || !depEntry.state().isLoaded()) {
                missingDeps.add(depId);
            } else if (!depEntry.state().isActive()) {
                inactiveDeps.add(depId);
            }
        }
        if (!missingDeps.isEmpty()) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                    ModuleFailure.of("missing_required_dependencies", "Required dependencies are not loaded: " + missingDeps));
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }
        if (!inactiveDeps.isEmpty()) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                    ModuleFailure.of("required_dependencies_not_active", "Required dependencies are not active: " + inactiveDeps));
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Ensure loaded
        if (!entry.state().isLoaded()) {
            ModuleLifecycleResult loadRes = doLoad(request);
            if (!loadRes.succeeded()) {
                return CompletableFuture.completedFuture(loadRes);
            }
        }

        // Atomic CAS desired state advance (only change desired state if not internal cascade and not already enabled)
        boolean isCascade = request.isBootstrap() && "system-cascade".equals(request.actor());
        boolean needsDesiredMutation = !isCascade && !request.isBootstrap() && (entry.desiredState() != ModuleDesiredState.ENABLED || entry.state() != ModuleLifecycleState.ENABLED);

        long currentConfigGen = runtimeConfigGeneration.get();
        long nextConfigGen = needsDesiredMutation ? currentConfigGen + 1L : currentConfigGen;

        CompletionStage<Boolean> casStage = needsDesiredMutation
                ? snapshotProvider.casSnapshotAsync(runtimeTarget, currentConfigGen,
                        createSnapshotWithUpdatedDesiredState(entry.descriptor().id(), ModuleDesiredState.ENABLED, nextConfigGen))
                : CompletableFuture.completedFuture(true);

        return casStage.thenCompose(casSuccess -> {
            if (!casSuccess) {
                ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                        ModuleFailure.of("config_generation_conflict", "Concurrent snapshot modification detected"));
                idempotencyHandler.recordCompletion(request, fail);
                return CompletableFuture.completedFuture(fail);
            }

            // CAS succeeded: update in-memory desired state and publish config generation to ALL module views
            CompletableFuture<ModuleLifecycleResult> resultFuture = new CompletableFuture<>();
            hostBoundary.dispatchToHost(() -> {
                if (needsDesiredMutation) {
                    runtimeConfigGeneration.set(nextConfigGen);
                    entry.setDesiredState(ModuleDesiredState.ENABLED);
                    for (ModuleEntry e : stateRegistry.values()) {
                        e.setConfigGeneration(nextConfigGen);
                    }
                }

                if (entry.state() == ModuleLifecycleState.ENABLED) {
                    ModuleLifecycleResult res = successResult(entry, request.operation(), entry.state(), false);
                    idempotencyHandler.recordCompletion(request, res);
                    resultFuture.complete(res);
                    return;
                }

                ModuleLifecycleState previousState = entry.state();
                entry.transition(ModuleLifecycleState.ENABLING);
                if (entry.context() != null) {
                    entry.context().setQuiescing(false);
                }

                IModuleLifecycleParticipant participant = entry.activeParticipant();
                CompletionStage<Void> startStage = participant == null
                        ? CompletableFuture.completedFuture(null)
                        : participant.onStartAsync(entry.context());

                startStage.whenComplete((ignored, failure) -> hostBoundary.marshalToHost(() -> {
                    if (failure == null) {
                        entry.transition(ModuleLifecycleState.ENABLED);
                        entry.setLastFailure(null);
                        ModuleLifecycleResult res = successResult(entry, request.operation(), previousState, true);

                        // A SYSTEM reports success only when every eligible child FEATURE enabled.
                        if (entry.descriptor().primaryType() == ModulePrimaryType.SYSTEM) {
                            cascadeCoordinator.cascadeEnableChildrenAsync(runtimeEpochId, entry.descriptor().id(), this::enableAsync)
                                    .whenComplete((childResults, cascadeErr) -> {
                                        ModuleLifecycleResult aggregate = aggregateCascade(entry, request.operation(), res,
                                                childResults, cascadeErr, "child_cascade_enable_failed");
                                        idempotencyHandler.recordCompletion(request, aggregate);
                                        resultFuture.complete(aggregate);
                                    });
                        } else {
                            idempotencyHandler.recordCompletion(request, res);
                            resultFuture.complete(res);
                        }
                    } else {
                        Throwable cause = unwrap(failure);
                        closeContextSafely(entry);
                        entry.setLastFailure(ModuleFailure.from("module_start_failed", cause));
                        entry.transition(ModuleLifecycleState.FAILED);
                        ModuleLifecycleResult fail = failureResult(entry, request.operation(), entry.lastFailure());
                        idempotencyHandler.recordCompletion(request, fail);
                        resultFuture.complete(fail);
                    }
                }, resultFuture));
            });
            return resultFuture;
        });
    }

    private CompletionStage<ModuleLifecycleResult> doDisable(ModuleLifecycleRequest request) {
        assertHostBoundary();
        ModuleEntry entry = stateRegistry.get(request.moduleId());
        if (entry == null) {
            ModuleLifecycleResult fail = unknownModuleResult(request);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Fencing
        ModuleFailure fence = checkFences(request, entry);
        if (fence != null) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(), fence);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Dependency removal fails closed: an enabled dependent that is not one of this SYSTEM's
        // children (children are disabled by the cascade below) must be disabled first.
        if (!request.isBootstrap()) {
            Set<ModuleId> activeDependents = activeDependentsOutsideSystem(entry);
            if (!activeDependents.isEmpty()) {
                ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                        ModuleFailure.of("required_by_active_dependents",
                                "Module is a required dependency of enabled modules: " + activeDependents));
                idempotencyHandler.recordCompletion(request, fail);
                return CompletableFuture.completedFuture(fail);
            }
        }

        // Cascade disable children first if this is a SYSTEM
        CompletionStage<List<ModuleLifecycleResult>> cascadeStage = CompletableFuture.completedFuture(Collections.emptyList());
        if (entry.descriptor().primaryType() == ModulePrimaryType.SYSTEM) {
            cascadeStage = cascadeCoordinator.cascadeDisableChildrenAsync(runtimeEpochId, entry.descriptor().id(), this::disableAsync);
        }

        return cascadeStage.thenCompose(childResults -> {
            // Check if any child cascade failed
            Optional<ModuleLifecycleResult> childFailure = childResults.stream().filter(r -> !r.succeeded()).findFirst();
            if (childFailure.isPresent()) {
                ModuleLifecycleResult failedChild = childFailure.get();
                ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                        ModuleFailure.of("child_cascade_drain_failed", "Child feature failed during system disable: " + failedChild.moduleId() + " -> " + failedChild.failure()));
                idempotencyHandler.recordCompletion(request, fail);
                return CompletableFuture.completedFuture(fail);
            }

            // Atomic CAS desired state advance (only change desired state if not internal cascade and not already disabled)
            boolean isCascade = request.isBootstrap() && "system-cascade".equals(request.actor());
            boolean needsDesiredMutation = !isCascade && !request.isBootstrap() && (entry.desiredState() != ModuleDesiredState.DISABLED || (entry.state() != ModuleLifecycleState.DISABLED && entry.state() != ModuleLifecycleState.UNLOADED));

            long currentConfigGen = runtimeConfigGeneration.get();
            long nextConfigGen = needsDesiredMutation ? currentConfigGen + 1L : currentConfigGen;

            CompletionStage<Boolean> casStage = needsDesiredMutation
                    ? snapshotProvider.casSnapshotAsync(runtimeTarget, currentConfigGen,
                            createSnapshotWithUpdatedDesiredState(entry.descriptor().id(), ModuleDesiredState.DISABLED, nextConfigGen))
                    : CompletableFuture.completedFuture(true);

            return casStage.thenCompose(casSuccess -> {
                if (!casSuccess) {
                    ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                            ModuleFailure.of("config_generation_conflict", "Concurrent snapshot modification detected"));
                    idempotencyHandler.recordCompletion(request, fail);
                    return CompletableFuture.completedFuture(fail);
                }

                CompletableFuture<ModuleLifecycleResult> resultFuture = new CompletableFuture<>();
                hostBoundary.dispatchToHost(() -> {
                    if (needsDesiredMutation) {
                        runtimeConfigGeneration.set(nextConfigGen);
                        entry.setDesiredState(ModuleDesiredState.DISABLED);
                        for (ModuleEntry e : stateRegistry.values()) {
                            e.setConfigGeneration(nextConfigGen);
                        }
                    }

                    if (entry.state() == ModuleLifecycleState.DISABLED || entry.state() == ModuleLifecycleState.UNLOADED) {
                        ModuleLifecycleResult res = successResult(entry, request.operation(), entry.state(), false);
                        idempotencyHandler.recordCompletion(request, res);
                        resultFuture.complete(res);
                        return;
                    }

                    ModuleLifecycleState previousState = entry.state();
                    entry.transition(ModuleLifecycleState.DISABLING);
                    if (entry.context() != null) {
                        entry.context().setQuiescing(true);
                    }

                    IModuleLifecycleParticipant participant = entry.activeParticipant();
                    CompletionStage<Void> quiesceStage = participant == null
                            ? CompletableFuture.completedFuture(null)
                            : participant.onQuiesceAsync(entry.context());

                    quiesceStage.thenCompose(ignored -> participant == null
                            ? CompletableFuture.completedFuture(null)
                            : participant.onDrainAsync(entry.context())
                    ).whenComplete((drainRes, drainFailure) -> hostBoundary.marshalToHost(() -> {
                        if (drainFailure == null) {
                            entry.transition(ModuleLifecycleState.DISABLED);
                            entry.setLastFailure(null);
                            ModuleLifecycleResult res = successResult(entry, request.operation(), previousState, true);
                            idempotencyHandler.recordCompletion(request, res);
                            resultFuture.complete(res);
                        } else {
                            Throwable cause = unwrap(drainFailure);
                            closeContextSafely(entry);
                            entry.setLastFailure(ModuleFailure.from("module_drain_failed", cause));
                            entry.transition(ModuleLifecycleState.FAILED);
                            ModuleLifecycleResult fail = failureResult(entry, request.operation(), entry.lastFailure());
                            idempotencyHandler.recordCompletion(request, fail);
                            resultFuture.complete(fail);
                        }
                    }, resultFuture));
                });
                return resultFuture;
            });
        });
    }

    private CompletionStage<ModuleLifecycleResult> doReload(ModuleLifecycleRequest request) {
        assertHostBoundary();
        ModuleEntry entry = stateRegistry.get(request.moduleId());
        if (entry == null) {
            ModuleLifecycleResult fail = unknownModuleResult(request);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Fencing
        ModuleFailure fence = checkFences(request, entry);
        if (fence != null) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(), fence);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Descriptor must explicitly support reload
        if (!entry.descriptor().reloadSupported()) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                    ModuleFailure.of("reload_not_supported", "Module does not support dynamic reload: " + request.moduleId()));
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        ModuleLifecycleState previousState = entry.state();
        entry.transition(ModuleLifecycleState.DISABLING);
        if (entry.context() != null) {
            entry.context().setQuiescing(true);
        }

        IModuleLifecycleParticipant oldParticipant = entry.activeParticipant();
        CompletionStage<Void> quiesceStage = oldParticipant == null
                ? CompletableFuture.completedFuture(null)
                : oldParticipant.onQuiesceAsync(entry.context());

        CompletableFuture<ModuleLifecycleResult> resultFuture = new CompletableFuture<>();

        quiesceStage.thenCompose(ign -> oldParticipant == null
                ? CompletableFuture.completedFuture(null)
                : oldParticipant.onDrainAsync(entry.context())
        ).whenComplete((drainRes, drainErr) -> hostBoundary.marshalToHost(() -> {
            if (drainErr != null) {
                closeContextSafely(entry);
                entry.setLastFailure(ModuleFailure.from("reload_drain_failed", unwrap(drainErr)));
                entry.transition(ModuleLifecycleState.FAILED);
                ModuleLifecycleResult fail = failureResult(entry, request.operation(), entry.lastFailure());
                idempotencyHandler.recordCompletion(request, fail);
                resultFuture.complete(fail);
                return;
            }

            // Close old context safely (fails closed all DI handles from old generation)
            closeContextSafely(entry);
            entry.clearGenerationReferences();

            // Increment lifecycle generation
            long nextGen = entry.lifecycleGeneration() + 1L;
            entry.setLifecycleGeneration(nextGen);

            ScopedModuleContext newContext = new ScopedModuleContext(
                    entry.descriptor(),
                    nextGen,
                    dependencyMap,
                    this::assertHostBoundary
            );
            entry.setContext(newContext);

            IModuleLifecycleParticipant newParticipant = entry.createParticipantInstance();
            entry.setActiveParticipant(newParticipant);

            CompletionStage<Void> startStage = newParticipant == null
                    ? CompletableFuture.completedFuture(null)
                    : newParticipant.onLoadAsync(newContext).thenCompose(ign -> newParticipant.onStartAsync(newContext));

            startStage.whenComplete((ign, startErr) -> hostBoundary.marshalToHost(() -> {
                if (startErr == null) {
                    entry.transition(ModuleLifecycleState.ENABLED);
                    entry.setLastFailure(null);
                    ModuleLifecycleResult res = successResult(entry, request.operation(), previousState, true);
                    idempotencyHandler.recordCompletion(request, res);
                    resultFuture.complete(res);
                } else {
                    closeContextSafely(entry);
                    entry.setLastFailure(ModuleFailure.from("reload_start_failed", unwrap(startErr)));
                    entry.transition(ModuleLifecycleState.FAILED);
                    ModuleLifecycleResult fail = failureResult(entry, request.operation(), entry.lastFailure());
                    idempotencyHandler.recordCompletion(request, fail);
                    resultFuture.complete(fail);
                }
            }, resultFuture));
        }, resultFuture));

        return resultFuture;
    }

    private CompletionStage<ModuleLifecycleResult> doUnload(ModuleLifecycleRequest request) {
        assertHostBoundary();
        ModuleEntry entry = stateRegistry.get(request.moduleId());
        if (entry == null) {
            ModuleLifecycleResult fail = unknownModuleResult(request);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        ModuleFailure fence = checkFences(request, entry);
        if (fence != null) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(), fence);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        if (entry.state() == ModuleLifecycleState.UNLOADED) {
            ModuleLifecycleResult res = successResult(entry, request.operation(), entry.state(), false);
            idempotencyHandler.recordCompletion(request, res);
            return CompletableFuture.completedFuture(res);
        }

        if (!request.isBootstrap()) {
            Set<ModuleId> loadedDependents = loadedDependents(entry);
            if (!loadedDependents.isEmpty()) {
                ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                        ModuleFailure.of("required_by_loaded_dependents",
                                "Module is a required dependency of loaded modules: " + loadedDependents));
                idempotencyHandler.recordCompletion(request, fail);
                return CompletableFuture.completedFuture(fail);
            }
        }

        ModuleLifecycleState previousState = entry.state();
        if (!entry.state().isActive()) {
            return closeAndUnload(request, entry, previousState);
        }

        // An active module is quiesced and drained before its generation closes; unload never skips drain.
        ModuleLifecycleRequest drain = ModuleLifecycleRequest.internalCascade(bootstrapToken, runtimeEpochId, "unload",
                entry.descriptor().id(), ModuleLifecycleOperation.DISABLE, runtimeConfigGeneration.get(), entry.lifecycleGeneration());
        CompletableFuture<ModuleLifecycleResult> resultFuture = new CompletableFuture<>();
        doDisable(drain).whenComplete((drained, drainErr) -> hostBoundary.marshalToHost(() -> {
            if (drainErr != null || !drained.succeeded()) {
                ModuleFailure failure = drainErr != null
                        ? ModuleFailure.from("unload_drain_failed", unwrap(drainErr))
                        : drained.failure();
                ModuleLifecycleResult fail = failureResult(entry, request.operation(), failure);
                idempotencyHandler.recordCompletion(request, fail);
                resultFuture.complete(fail);
                return;
            }
            closeAndUnload(request, entry, previousState).whenComplete((res, err) -> {
                if (err != null) {
                    resultFuture.completeExceptionally(unwrap(err));
                } else {
                    resultFuture.complete(res);
                }
            });
        }, resultFuture));
        return resultFuture;
    }

    private CompletionStage<ModuleLifecycleResult> closeAndUnload(
            ModuleLifecycleRequest request,
            ModuleEntry entry,
            ModuleLifecycleState previousState
    ) {
        IModuleLifecycleParticipant participant = entry.activeParticipant();
        CompletionStage<Void> closeStage = participant == null || entry.context() == null
                ? CompletableFuture.completedFuture(null)
                : participant.onCloseAsync(entry.context());

        CompletableFuture<ModuleLifecycleResult> resultFuture = new CompletableFuture<>();
        closeStage.whenComplete((ignored, closeFailure) -> hostBoundary.marshalToHost(() -> {
            // The generation's DI bindings and resources are released either way; a failed participant
            // close is reported, never hidden behind a successful UNLOADED transition.
            closeContextSafely(entry);
            entry.clearGenerationReferences();
            if (closeFailure == null) {
                entry.transition(ModuleLifecycleState.UNLOADED);
                ModuleLifecycleResult res = successResult(entry, request.operation(), previousState, true);
                idempotencyHandler.recordCompletion(request, res);
                resultFuture.complete(res);
            } else {
                entry.setLastFailure(ModuleFailure.from("module_close_failed", unwrap(closeFailure)));
                entry.transition(ModuleLifecycleState.FAILED);
                ModuleLifecycleResult fail = failureResult(entry, request.operation(), entry.lastFailure());
                idempotencyHandler.recordCompletion(request, fail);
                resultFuture.complete(fail);
            }
        }, resultFuture));
        return resultFuture;
    }

    private CompletionStage<ModuleLifecycleResult> doRollback(ModuleLifecycleRequest request) {
        assertHostBoundary();
        ModuleEntry entry = stateRegistry.get(request.moduleId());
        if (entry == null) {
            ModuleLifecycleResult fail = unknownModuleResult(request);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Fencing
        ModuleFailure fence = checkFences(request, entry);
        if (fence != null) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(), fence);
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Rollback eligibility
        if (!entry.descriptor().rollbackSupported()) {
            ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                    ModuleFailure.of("rollback_not_supported", "Module does not support rollback: " + request.moduleId()));
            idempotencyHandler.recordCompletion(request, fail);
            return CompletableFuture.completedFuture(fail);
        }

        // Load candidate snapshot asynchronously
        return snapshotProvider.loadSnapshotAsync(runtimeTarget).thenCompose(optSnapshot -> {
            if (optSnapshot.isEmpty()) {
                ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                        ModuleFailure.of("no_prior_snapshot", "No prior configuration snapshot available for rollback"));
                idempotencyHandler.recordCompletion(request, fail);
                return CompletableFuture.completedFuture(fail);
            }

            ModuleDesiredStateSnapshot candidate = optSnapshot.get();
            // Validate compatibility before mutating ANY state
            return rollbackValidator.validateRollbackCompatibility(
                    runtimeTarget,
                    runtimeConfigGeneration.get(),
                    candidate,
                    stateRegistry.descriptors()
            ).thenCompose(valResult -> {
                if (!valResult.compatible()) {
                    ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                            ModuleFailure.of("incompatible_rollback_target", "Rollback candidate rejected: " + valResult.reason()));
                    idempotencyHandler.recordCompletion(request, fail);
                    return CompletableFuture.completedFuture(fail);
                }

                // Invariant: Config generation is NEVER lowered. Rollback advances config generation.
                long currentGen = runtimeConfigGeneration.get();
                long nextConfigGen = currentGen + 1L;
                ModuleDesiredState targetDesired = candidate.getDesiredState(entry.descriptor().id());

                ModuleDesiredStateSnapshot newSnapshot = createSnapshotWithUpdatedDesiredState(entry.descriptor().id(), targetDesired, nextConfigGen);
                return snapshotProvider.casSnapshotAsync(runtimeTarget, currentGen, newSnapshot).thenCompose(casSuccess -> {
                    if (!casSuccess) {
                        ModuleLifecycleResult fail = failureResult(entry, request.operation(),
                                ModuleFailure.of("config_generation_conflict", "Concurrent snapshot modification detected"));
                        idempotencyHandler.recordCompletion(request, fail);
                        return CompletableFuture.completedFuture(fail);
                    }

                    CompletableFuture<ModuleLifecycleResult> resultFuture = new CompletableFuture<>();
                    hostBoundary.dispatchToHost(() -> {
                        ModuleLifecycleState previousState = entry.state();
                        runtimeConfigGeneration.set(nextConfigGen);
                        entry.setDesiredState(targetDesired);
                        for (ModuleEntry e : stateRegistry.values()) {
                            e.setConfigGeneration(nextConfigGen);
                        }

                        // Trigger the transition that realizes the restored desired state and report its outcome.
                        ModuleLifecycleOperation transition = targetDesired == ModuleDesiredState.DISABLED
                                ? ModuleLifecycleOperation.DISABLE
                                : ModuleLifecycleOperation.ENABLE;
                        ModuleLifecycleRequest internal = ModuleLifecycleRequest.internalCascade(bootstrapToken, runtimeEpochId,
                                "rollback", entry.descriptor().id(), transition, nextConfigGen, entry.lifecycleGeneration());
                        CompletionStage<ModuleLifecycleResult> transitionStage = transition == ModuleLifecycleOperation.DISABLE
                                ? doDisable(internal)
                                : doEnable(internal);
                        transitionStage.whenComplete((transitionResult, transitionErr) -> {
                            ModuleLifecycleResult res;
                            if (transitionErr != null) {
                                res = failureResult(entry, request.operation(),
                                        ModuleFailure.from("rollback_transition_failed", unwrap(transitionErr)));
                            } else if (!transitionResult.succeeded()) {
                                res = failureResult(entry, request.operation(), transitionResult.failure());
                            } else {
                                res = successResult(entry, request.operation(), previousState, true);
                            }
                            idempotencyHandler.recordCompletion(request, res);
                            resultFuture.complete(res);
                        });
                    });
                    return resultFuture;
                });
            });
        });
    }

    // --------------------------------------------------------------------------------------------
    // Queries & Snapshot Surface (Returns Immutable Snapshots)
    // --------------------------------------------------------------------------------------------

    // Package-private internal accessor for package tests
    synchronized Optional<ModuleEntry> findEntry(ModuleId moduleId) {
        if (!configured || stateRegistry == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(stateRegistry.get(moduleId));
    }

    @Override
    public Optional<ModuleSnapshot> findModule(ModuleId moduleId) {
        requireReady();
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        return stateRegistry.snapshotOf(moduleId);
    }

    @Override
    public List<ModuleSnapshot> listModules() {
        requireReady();
        return stateRegistry.allSnapshots();
    }

    @Override
    public List<ModuleSnapshot> findByParentSystem(ModuleId parentSystemId) {
        requireReady();
        Objects.requireNonNull(parentSystemId, "parentSystemId must not be null");
        List<ModuleEntry> entries = stateRegistry.findByParentSystem(parentSystemId);
        List<ModuleSnapshot> list = new ArrayList<>(entries.size());
        for (ModuleEntry e : entries) {
            list.add(ModuleStateRegistry.toSnapshot(e));
        }
        return list;
    }

    @Override
    public List<ModuleSnapshot> findByPrimaryType(ModulePrimaryType type) {
        requireReady();
        Objects.requireNonNull(type, "type must not be null");
        List<ModuleEntry> entries = stateRegistry.findByPrimaryType(type);
        List<ModuleSnapshot> list = new ArrayList<>(entries.size());
        for (ModuleEntry e : entries) {
            list.add(ModuleStateRegistry.toSnapshot(e));
        }
        return list;
    }

    @Override
    public List<ModuleSnapshot> findByCapability(CapabilityId capabilityId) {
        requireReady();
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        List<ModuleEntry> entries = stateRegistry.findByCapability(capabilityId);
        List<ModuleSnapshot> list = new ArrayList<>(entries.size());
        for (ModuleEntry e : entries) {
            list.add(ModuleStateRegistry.toSnapshot(e));
        }
        return list;
    }

    @Override
    public boolean isCapabilityAvailable(CapabilityId capabilityId) {
        requireReady();
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");

        List<ModuleEntry> providers = stateRegistry.findByCapability(capabilityId);
        for (ModuleEntry entry : providers) {
            if (entry.state() == ModuleLifecycleState.ENABLED) {
                return true;
            }
            if (entry.state() == ModuleLifecycleState.DISABLED) {
                IModuleLifecycleParticipant participant = entry.activeParticipant();
                if (participant != null && participant.residualObligationPolicy().isResidual(capabilityId)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public ModuleHealth checkAggregateHealth(Optional<ModuleId> parentSystemId) {
        requireReady();
        Objects.requireNonNull(parentSystemId, "parentSystemId must not be null");

        List<ModuleEntry> targetEntries = parentSystemId.isPresent()
                ? stateRegistry.findByParentSystem(parentSystemId.get())
                : new ArrayList<>(stateRegistry.values());

        if (targetEntries.isEmpty()) {
            return ModuleHealth.healthy("No modules active in scope");
        }

        ModuleHealthStatus aggregatedStatus = ModuleHealthStatus.HEALTHY;
        List<String> messages = new ArrayList<>();

        for (ModuleEntry entry : targetEntries) {
            ModuleHealth moduleHealth = entry.assessHealth();
            if (moduleHealth.status() == ModuleHealthStatus.UNHEALTHY) {
                if (entry.descriptor().required()) {
                    aggregatedStatus = ModuleHealthStatus.UNHEALTHY;
                } else if (aggregatedStatus != ModuleHealthStatus.UNHEALTHY) {
                    aggregatedStatus = ModuleHealthStatus.DEGRADED;
                }
                messages.add(entry.descriptor().id() + ": " + moduleHealth.summary());
            } else if (moduleHealth.status() == ModuleHealthStatus.DEGRADED && aggregatedStatus == ModuleHealthStatus.HEALTHY) {
                aggregatedStatus = ModuleHealthStatus.DEGRADED;
                messages.add(entry.descriptor().id() + ": " + moduleHealth.summary());
            }
        }

        String summary = messages.isEmpty() ? "All modules healthy" : String.join("; ", messages);
        return new ModuleHealth(aggregatedStatus, summary, Map.of());
    }

    @Override
    public CompletionStage<List<ModuleLifecycleResult>> reconcileDesiredStatesAsync() {
        requireReady();
        CompletableFuture<List<ModuleLifecycleResult>> future = new CompletableFuture<>();
        dispatchToHost(() -> cascadeCoordinator.reconcileDesiredRootsAsync(runtimeEpochId, this::enableAsync)
                .whenComplete((results, err) -> {
                    if (err != null) {
                        future.completeExceptionally(unwrap(err));
                    } else {
                        future.complete(results);
                    }
                }), future);
        return future;
    }

    @Override
    public CompletionStage<Void> restoreFromSnapshotAsync(ModuleDesiredStateSnapshot snapshot) {
        requireReady();
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!runtimeTarget.equals(snapshot.target())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Snapshot runtime target mismatch: expected " + runtimeTarget + " but was " + snapshot.target()));
        }

        long currentGen = runtimeConfigGeneration.get();
        if (snapshot.configGeneration() < currentGen) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Cannot restore snapshot with generation " + snapshot.configGeneration() + " lower than current: " + currentGen));
        }

        // Must update store via atomic CAS before mutating local desired states
        return snapshotProvider.casSnapshotAsync(runtimeTarget, currentGen, snapshot).thenCompose(ok -> {
            if (!ok) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Snapshot store CAS failed during restore: expected config generation " + currentGen));
            }
            return hostBoundary.dispatchToHost(() -> {
                runtimeConfigGeneration.set(snapshot.configGeneration());
                restoredDesiredStates.putAll(snapshot.desiredStates());
                for (Map.Entry<ModuleId, ModuleDesiredState> entry : snapshot.desiredStates().entrySet()) {
                    ModuleEntry moduleEntry = stateRegistry.get(entry.getKey());
                    if (moduleEntry != null) {
                        moduleEntry.setDesiredState(entry.getValue());
                        moduleEntry.setConfigGeneration(snapshot.configGeneration());
                    }
                }
            });
        });
    }

    @Override
    public ModuleDesiredStateSnapshot createSnapshot() {
        requireReady();
        Map<ModuleId, ModuleDesiredState> desiredMap = new LinkedHashMap<>();
        for (ModuleEntry entry : stateRegistry.values()) {
            desiredMap.put(entry.descriptor().id(), entry.desiredState());
        }
        return ModuleDesiredStateSnapshot.now(runtimeTarget, runtimeConfigGeneration.get(), desiredMap);
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        if (!configured || stateRegistry == null) {
            return CompletableFuture.completedFuture(null);
        }

        List<ModuleEntry> allModules = new ArrayList<>(stateRegistry.values());
        Collections.reverse(allModules);
        CompletionStage<Void> closeStage = CompletableFuture.completedFuture(null);

        for (ModuleEntry entry : allModules) {
            closeStage = closeStage.thenCompose(ignored -> {
                if (entry.state().isActive() || entry.state().isLoaded()) {
                    return disableAsync(ModuleLifecycleRequest.internalCascade(
                            bootstrapToken, runtimeEpochId, "shutdown", entry.descriptor().id(),
                            ModuleLifecycleOperation.DISABLE, entry.configGeneration(), entry.lifecycleGeneration()
                    )).thenCompose(disRes -> {
                        return unloadAsync(ModuleLifecycleRequest.internalCascade(
                                bootstrapToken, runtimeEpochId, "shutdown", entry.descriptor().id(),
                                ModuleLifecycleOperation.UNLOAD, entry.configGeneration(), entry.lifecycleGeneration()
                        )).thenApply(unRes -> null);
                    });
                }
                return CompletableFuture.completedFuture(null);
            });
        }

        return closeStage.whenComplete((ign, err) -> {
            idempotencyHandler.clear();
        });
    }

    @Override
    public void close() {
        if (configured) {
            CompletableFuture<Void> future = closeAsync().toCompletableFuture();
            if (future.isDone()) {
                future.join();
            } else if (hostBoundary != null && hostBoundary.isHostThread()) {
                throw new IllegalStateException("Synchronous close() on host thread cannot join pending async lifecycle; use closeAsync()");
            } else {
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new RuntimeException("Timeout or error during coordinator close", ex);
                }
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    // Private Helpers
    // --------------------------------------------------------------------------------------------

    private void dispatchToHost(Runnable action, CompletableFuture<?> completion) {
        try {
            hostBoundary.dispatchToHost(action).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    completion.completeExceptionally(unwrap(failure));
                }
            });
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException("Lifecycle coordinator is unconfigured and fail-closed");
        }
    }

    private void requireReady() {
        requireConfigured();
        if (!restoreCompletion.isDone()) {
            throw new IllegalStateException("Lifecycle coordinator is not ready: asynchronous initialization is in progress");
        }
        if (restoreCompletion.isCompletedExceptionally()) {
            Throwable cause = null;
            try {
                restoreCompletion.join();
            } catch (Throwable t) {
                cause = unwrap(t);
            }
            throw new IllegalStateException("Lifecycle coordinator initialization failed and is fail-closed", cause);
        }
    }

    private ModuleLifecycleResult initializationFailedResult(ModuleLifecycleRequest request, Throwable err) {
        ModuleEntry entry = stateRegistry == null ? null : stateRegistry.get(request.moduleId());
        ModuleLifecycleState state = entry == null ? ModuleLifecycleState.DISCOVERED : entry.state();
        ModuleDesiredState desired = entry == null ? ModuleDesiredState.DISABLED : entry.desiredState();
        long gen = entry == null ? 0L : entry.lifecycleGeneration();
        long cfg = entry == null ? runtimeConfigGeneration.get() : entry.configGeneration();
        return ModuleLifecycleResult.failure(
                request.moduleId(),
                request.operation(),
                state,
                state,
                desired,
                gen,
                cfg,
                ModuleFailure.from("initialization_failed", err)
        );
    }

    private void loadAndApplyInitialSnapshot() {
        snapshotProvider.loadSnapshotAsync(runtimeTarget).whenComplete((optSnapshot, loadErr) -> {
            if (loadErr != null) {
                restoreCompletion.completeExceptionally(unwrap(loadErr));
                return;
            }

            if (optSnapshot == null) {
                restoreCompletion.completeExceptionally(new IllegalStateException("Snapshot provider returned null optional"));
                return;
            }

            if (optSnapshot.isPresent()) {
                ModuleDesiredStateSnapshot snapshot = optSnapshot.get();
                // 1. Validate snapshot target
                if (!runtimeTarget.equals(snapshot.target())) {
                    restoreCompletion.completeExceptionally(new IllegalArgumentException(
                            "Snapshot runtime target mismatch: expected " + runtimeTarget + " but was " + snapshot.target()));
                    return;
                }

                // 2. Validate configuration generation monotonicity (> 0)
                if (snapshot.configGeneration() <= 0L) {
                    restoreCompletion.completeExceptionally(new IllegalArgumentException(
                            "Invalid snapshot configuration generation: " + snapshot.configGeneration() + "; must be positive"));
                    return;
                }

                // 3. Validate desired states schema
                if (snapshot.desiredStates() == null) {
                    restoreCompletion.completeExceptionally(new IllegalArgumentException("Snapshot desired states map must not be null"));
                    return;
                }

                // 4. Marshal publication through actual HostLifecycleBoundary
                try {
                    hostBoundary.dispatchToHost(() -> {
                        runtimeConfigGeneration.set(snapshot.configGeneration());
                        restoredDesiredStates.putAll(snapshot.desiredStates());
                        for (ModuleEntry entry : stateRegistry.values()) {
                            ModuleDesiredState restoredState = snapshot.desiredStates().get(entry.descriptor().id());
                            if (restoredState != null) {
                                entry.setDesiredState(restoredState);
                            }
                            entry.setConfigGeneration(snapshot.configGeneration());
                        }
                    }).whenComplete((ignored, publishErr) -> {
                        if (publishErr != null) {
                            restoreCompletion.completeExceptionally(unwrap(publishErr));
                        } else {
                            restoreCompletion.complete(null);
                        }
                    });
                } catch (Throwable t) {
                    restoreCompletion.completeExceptionally(unwrap(t));
                }
            } else {
                // No snapshot exists yet; perform initial atomic CAS (0L -> 1L)
                ModuleDesiredStateSnapshot initial = ModuleDesiredStateSnapshot.now(runtimeTarget, 1L, Map.of());
                try {
                    snapshotProvider.casSnapshotAsync(runtimeTarget, 0L, initial).whenComplete((ok, casErr) -> {
                        if (casErr != null) {
                            restoreCompletion.completeExceptionally(unwrap(casErr));
                            return;
                        }
                        if (Boolean.FALSE.equals(ok)) {
                            restoreCompletion.completeExceptionally(new IllegalStateException(
                                    "Initial snapshot CAS failed for target " + runtimeTarget + ": expected gen 0"));
                            return;
                        }
                        // Marshal publication through actual HostLifecycleBoundary
                        try {
                            hostBoundary.dispatchToHost(() -> {
                                runtimeConfigGeneration.set(1L);
                            }).whenComplete((ignored, publishErr) -> {
                                if (publishErr != null) {
                                    restoreCompletion.completeExceptionally(unwrap(publishErr));
                                } else {
                                    restoreCompletion.complete(null);
                                }
                            });
                        } catch (Throwable t) {
                            restoreCompletion.completeExceptionally(unwrap(t));
                        }
                    });
                } catch (Throwable t) {
                    restoreCompletion.completeExceptionally(unwrap(t));
                }
            }
        });
    }

    private ModuleDesiredStateSnapshot createSnapshotWithUpdatedDesiredState(ModuleId moduleId, ModuleDesiredState desiredState, long newConfigGen) {
        // Start from the restored authority so a module that is not registered in this runtime generation
        // (for example an independently disabled FEATURE not yet loaded) keeps its persisted desired state.
        Map<ModuleId, ModuleDesiredState> map = new LinkedHashMap<>(restoredDesiredStates);
        for (ModuleEntry e : stateRegistry.values()) {
            map.put(e.descriptor().id(), e.desiredState());
        }
        map.put(moduleId, desiredState);
        return ModuleDesiredStateSnapshot.now(runtimeTarget, newConfigGen, map);
    }

    private CompletionStage<ModuleLifecycleResult> preFlightCheck(ModuleLifecycleRequest request) {
        // 1. Runtime instance epoch validation
        if (!request.runtimeEpochId().equals(runtimeEpochId)) {
            return CompletableFuture.completedFuture(ModuleLifecycleResult.failure(
                    request.moduleId(),
                    request.operation(),
                    ModuleLifecycleState.DISCOVERED,
                    ModuleLifecycleState.DISCOVERED,
                    ModuleDesiredState.DISABLED,
                    0L,
                    0L,
                    ModuleFailure.of("stale_runtime_epoch", "Request runtime epoch " + request.runtimeEpochId() + " does not match coordinator epoch " + runtimeEpochId)
            ));
        }

        // 2. Authorize FIRST before looking up cached results or reserving in-flight state
        return authorizer.authorizeAsync(request).thenCompose(authorized -> {
            if (!authorized) {
                ModuleEntry entry = stateRegistry == null ? null : stateRegistry.get(request.moduleId());
                ModuleLifecycleState state = entry == null ? ModuleLifecycleState.DISCOVERED : entry.state();
                ModuleDesiredState desired = entry == null ? ModuleDesiredState.DISABLED : entry.desiredState();
                long gen = entry == null ? 0L : entry.lifecycleGeneration();
                long cfg = entry == null ? runtimeConfigGeneration.get() : entry.configGeneration();
                return CompletableFuture.completedFuture(ModuleLifecycleResult.failure(
                        request.moduleId(),
                        request.operation(),
                        state,
                        state,
                        desired,
                        gen,
                        cfg,
                        ModuleFailure.of("unauthorized", "Lifecycle request denied by authorizer for actor: " + request.actor())
                ));
            }

            // 3. Replay check & in-flight reservation
            ModuleEntry entry = stateRegistry == null ? null : stateRegistry.get(request.moduleId());
            ModuleLifecycleResult preCheck = idempotencyHandler.checkPreExecution(request, entry);
            if (preCheck != null) {
                return CompletableFuture.completedFuture(preCheck);
            }

            return CompletableFuture.completedFuture(null);
        });
    }

    private ModuleFailure checkFences(ModuleLifecycleRequest request, ModuleEntry entry) {
        if (request.isBootstrap()) {
            return null;
        }

        if (request.expectedConfigGeneration() != runtimeConfigGeneration.get()) {
            return ModuleFailure.of(
                    "stale_config_generation",
                    "Expected config generation " + request.expectedConfigGeneration() + " does not match current: " + runtimeConfigGeneration.get()
            );
        }

        if (request.expectedLifecycleGeneration() != entry.lifecycleGeneration()) {
            return ModuleFailure.of(
                    "stale_lifecycle_generation",
                    "Expected lifecycle generation " + request.expectedLifecycleGeneration() + " does not match current: " + entry.lifecycleGeneration()
            );
        }
        return null;
    }

    private ModuleLifecycleResult awaitCompletionNonBlocking(CompletionStage<ModuleLifecycleResult> stage, ModuleLifecycleRequest request) {
        CompletableFuture<ModuleLifecycleResult> future = stage.toCompletableFuture();
        if (future.isDone()) {
            return future.join();
        }

        // If called on the host thread, NEVER block the host thread to prevent deadlocks!
        if (configured && hostBoundary != null && hostBoundary.isHostThread()) {
            ModuleEntry entry = stateRegistry == null ? null : stateRegistry.get(request.moduleId());
            ModuleLifecycleState state = entry == null ? ModuleLifecycleState.DISCOVERED : entry.state();
            ModuleDesiredState desired = entry == null ? ModuleDesiredState.DISABLED : entry.desiredState();
            long gen = entry == null ? 0L : entry.lifecycleGeneration();
            long cfg = entry == null ? runtimeConfigGeneration.get() : entry.configGeneration();
            return ModuleLifecycleResult.failure(
                    request.moduleId(),
                    request.operation(),
                    state,
                    state,
                    desired,
                    gen,
                    cfg,
                    ModuleFailure.of("sync_host_blocking_forbidden", "Synchronous blocking on host thread forbidden; operation is asynchronous")
            );
        }

        return future.join();
    }

    private ModuleLifecycleResult aggregateCascade(
            ModuleEntry parent,
            ModuleLifecycleOperation operation,
            ModuleLifecycleResult parentResult,
            List<ModuleLifecycleResult> childResults,
            Throwable cascadeFailure,
            String failureCode
    ) {
        if (cascadeFailure != null) {
            return failureResult(parent, operation, ModuleFailure.from(failureCode, unwrap(cascadeFailure)));
        }
        List<String> failed = new ArrayList<>();
        for (ModuleLifecycleResult child : childResults == null ? List.<ModuleLifecycleResult>of() : childResults) {
            if (!child.succeeded()) {
                failed.add(child.moduleId() + " -> " + child.failure());
            }
        }
        if (failed.isEmpty()) {
            return parentResult;
        }
        return failureResult(parent, operation, ModuleFailure.of(failureCode,
                "SYSTEM " + parent.descriptor().id() + " changed but child FEATURES failed: " + String.join("; ", failed)));
    }

    private Set<ModuleId> activeDependentsOutsideSystem(ModuleEntry target) {
        ModuleId targetId = target.descriptor().id();
        Set<ModuleId> dependents = new LinkedHashSet<>();
        for (ModuleEntry candidate : stateRegistry.values()) {
            boolean childOfTarget = candidate.descriptor().parentSystemId().map(targetId::equals).orElse(false);
            if (!childOfTarget && candidate.state().isActive()
                    && candidate.descriptor().requiredDependencies().contains(targetId)) {
                dependents.add(candidate.descriptor().id());
            }
        }
        return dependents;
    }

    private Set<ModuleId> loadedDependents(ModuleEntry target) {
        ModuleId targetId = target.descriptor().id();
        Set<ModuleId> dependents = new LinkedHashSet<>();
        for (ModuleEntry candidate : stateRegistry.values()) {
            if (candidate.state().isLoaded() && candidate.descriptor().requiredDependencies().contains(targetId)) {
                dependents.add(candidate.descriptor().id());
            }
        }
        return dependents;
    }

    private void closeContextSafely(ModuleEntry entry) {
        if (entry.context() != null) {
            try {
                entry.context().close();
            } catch (Exception ex) {
                // Suppressed
            }
        }
    }

    private Throwable unwrap(Throwable t) {
        if (t instanceof CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private ModuleLifecycleResult unconfiguredResult(ModuleLifecycleRequest request) {
        return ModuleLifecycleResult.failure(
                request.moduleId(),
                request.operation(),
                ModuleLifecycleState.DISCOVERED,
                ModuleLifecycleState.DISCOVERED,
                ModuleDesiredState.DISABLED,
                0L,
                0L,
                ModuleFailure.of("unconfigured_lifecycle_coordinator", "Coordinator has not been configured with host boundary, authorizer, and snapshot authority")
        );
    }

    private ModuleLifecycleResult unknownModuleResult(ModuleLifecycleRequest request) {
        return ModuleLifecycleResult.failure(
                request.moduleId(),
                request.operation(),
                ModuleLifecycleState.DISCOVERED,
                ModuleLifecycleState.DISCOVERED,
                ModuleDesiredState.DISABLED,
                0L,
                0L,
                ModuleFailure.of("unknown_module", "Module not registered in this registry: " + request.moduleId())
        );
    }

    private ModuleLifecycleResult successResult(ModuleEntry entry, ModuleLifecycleOperation op, ModuleLifecycleState previousState, boolean changed) {
        return ModuleLifecycleResult.success(
                entry.descriptor().id(),
                op,
                previousState,
                entry.state(),
                entry.desiredState(),
                entry.lifecycleGeneration(),
                entry.configGeneration(),
                changed
        );
    }

    private ModuleLifecycleResult failureResult(ModuleEntry entry, ModuleLifecycleOperation op, ModuleFailure failure) {
        return ModuleLifecycleResult.failure(
                entry.descriptor().id(),
                op,
                entry.state(),
                entry.state(),
                entry.desiredState(),
                entry.lifecycleGeneration(),
                entry.configGeneration(),
                failure
        );
    }
}
