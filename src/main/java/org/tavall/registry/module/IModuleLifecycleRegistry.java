package org.tavall.registry.module;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Universal in-process lifecycle registry and state coordinator contract for Tavall modules.
 * <p>
 * <b>Architectural Role:</b> Reusable typed lifecycle orchestration contract for Tavall runtimes
 * (Pay, Auto-Post, Minecraft, Web). Orchestrates declarative desired states, observed lifecycle transitions,
 * host DI container integration, reverse-dependency cascading, idempotency fencing, aggregate health observation,
 * and snapshot restoration.
 * <p>
 * <b>Host Serialization (Paper-Safe):</b> All lifecycle state transitions, context mutations, and DI container
 * modifications are serialized through the host lifecycle boundary and validated against the designated host thread.
 * <p>
 * <b>Deadlock Prevention:</b> Asynchronous lifecycle operations ({@code *Async}) return {@link CompletionStage}.
 * Synchronous convenience methods ({@code load}, {@code enable}, etc.) return immediately if already completed,
 * or fail-fast with a typed pending failure if called from the host thread to strictly prevent deadlocks.
 */
public interface IModuleLifecycleRegistry extends AutoCloseable {

    /**
     * Unique runtime controller boot instance identifier for epoch fencing.
     *
     * @return current boot instance epoch UUID
     */
    UUID runtimeEpochId();

    /**
     * Designated execution target host.
     *
     * @return target runtime environment
     */
    RuntimeTarget runtimeTarget();

    /**
     * Checks if this coordinator has been configured with an authoritative host boundary, authorizer, and snapshot authority.
     *
     * @return true if fully configured and active; false if fail-closed
     */
    boolean isConfigured();

    /**
     * Checks if this coordinator has completed initial snapshot restoration and is ready for operations.
     *
     * @return true if initialized and ready; false if initializing or initialization failed
     */
    boolean isReady();

    /**
     * Completion stage completing when initial snapshot restoration succeeds, or completing exceptionally if failed.
     *
     * @return completion stage for coordinator readiness
     */
    CompletionStage<Void> whenReady();

    /**
     * Registers a module descriptor and its per-generation participant supplier.
     *
     * @param descriptor          the module descriptor; must not be null
     * @param participantSupplier supplier creating new participant instances per generation; null if stateless
     */
    void registerModule(
            ModuleDescriptor descriptor,
            Supplier<IModuleLifecycleParticipant> participantSupplier
    );

    /**
     * Registers a module descriptor, its participant supplier, and a health assessment probe.
     *
     * @param descriptor          the module descriptor; must not be null
     * @param participantSupplier supplier creating new participant instances per generation; null if stateless
     * @param healthCheck         health assessment probe; null if none
     */
    void registerModule(
            ModuleDescriptor descriptor,
            Supplier<IModuleLifecycleParticipant> participantSupplier,
            IModuleHealthCheck healthCheck
    );

    /**
     * Unregisters a module and removes its descriptor from the registry.
     * <p>
     * The module must be in {@link ModuleLifecycleState#UNLOADED} or {@link ModuleLifecycleState#DISCOVERED}.
     *
     * @param moduleId the module to unregister; must not be null
     * @throws IllegalStateException if the module is active or loaded
     */
    void unregisterModule(ModuleId moduleId);

    /**
     * Synchronously loads a module into the registry, binding its DI dependencies.
     * Must not block the host thread if asynchronous completion is pending.
     *
     * @param request the load request; must not be null
     * @return the transition result
     */
    ModuleLifecycleResult load(ModuleLifecycleRequest request);

    /**
     * Asynchronously loads a module into the registry, binding its DI dependencies.
     *
     * @param request the load request; must not be null
     * @return a completion stage returning the transition result
     */
    CompletionStage<ModuleLifecycleResult> loadAsync(ModuleLifecycleRequest request);

    /**
     * Synchronously enables a module, activating its capabilities and starting its participant.
     * Must not block the host thread if asynchronous completion is pending.
     *
     * @param request the enable request; must not be null
     * @return the transition result
     */
    ModuleLifecycleResult enable(ModuleLifecycleRequest request);

    /**
     * Asynchronously enables a module, activating its capabilities and starting its participant.
     *
     * @param request the enable request; must not be null
     * @return a completion stage returning the transition result
     */
    CompletionStage<ModuleLifecycleResult> enableAsync(ModuleLifecycleRequest request);

    /**
     * Synchronously disables a module, quiescing and draining in-flight work safely.
     * Must not block the host thread if asynchronous completion is pending.
     *
     * @param request the disable request; must not be null
     * @return the transition result
     */
    ModuleLifecycleResult disable(ModuleLifecycleRequest request);

    /**
     * Asynchronously disables a module, quiescing and draining in-flight work safely.
     *
     * @param request the disable request; must not be null
     * @return a completion stage returning the transition result
     */
    CompletionStage<ModuleLifecycleResult> disableAsync(ModuleLifecycleRequest request);

    /**
     * Synchronously reloads a supported module, constructing a fresh generation without runtime restart.
     * Must not block the host thread if asynchronous completion is pending.
     *
     * @param request the reload request; must not be null
     * @return the transition result
     */
    ModuleLifecycleResult reload(ModuleLifecycleRequest request);

    /**
     * Asynchronously reloads a supported module, constructing a fresh generation without runtime restart.
     *
     * @param request the reload request; must not be null
     * @return a completion stage returning the transition result
     */
    CompletionStage<ModuleLifecycleResult> reloadAsync(ModuleLifecycleRequest request);

    /**
     * Synchronously unloads a module, releasing resources and detaching DI bindings.
     * Must not block the host thread if asynchronous completion is pending.
     *
     * @param request the unload request; must not be null
     * @return the transition result
     */
    ModuleLifecycleResult unload(ModuleLifecycleRequest request);

    /**
     * Asynchronously unloads a module, releasing resources and detaching DI bindings.
     *
     * @param request the unload request; must not be null
     * @return a completion stage returning the transition result
     */
    CompletionStage<ModuleLifecycleResult> unloadAsync(ModuleLifecycleRequest request);

    /**
     * Synchronously rolls back a module to a previously validated configuration state.
     * Must not block the host thread if asynchronous completion is pending.
     *
     * @param request the rollback request; must not be null
     * @return the transition result
     */
    ModuleLifecycleResult rollback(ModuleLifecycleRequest request);

    /**
     * Asynchronously rolls back a module to a previously validated configuration state.
     *
     * @param request the rollback request; must not be null
     * @return a completion stage returning the transition result
     */
    CompletionStage<ModuleLifecycleResult> rollbackAsync(ModuleLifecycleRequest request);

    /**
     * Finds the immutable snapshot for a registered module.
     *
     * @param moduleId the module ID; must not be null
     * @return an optional containing the module snapshot, or empty if not registered
     */
    Optional<ModuleSnapshot> findModule(ModuleId moduleId);

    /**
     * Lists all registered module snapshots sorted by module ID.
     *
     * @return immutable list of module snapshots
     */
    List<ModuleSnapshot> listModules();

    /**
     * Finds all child module snapshots belonging to the specified parent system.
     *
     * @param parentSystemId the parent system module ID; must not be null
     * @return immutable list of child snapshots
     */
    List<ModuleSnapshot> findByParentSystem(ModuleId parentSystemId);

    /**
     * Finds all module snapshots matching the specified primary module type.
     *
     * @param type the primary module type; must not be null
     * @return immutable list of matching snapshots
     */
    List<ModuleSnapshot> findByPrimaryType(ModulePrimaryType type);

    /**
     * Finds all module snapshots providing the specified capability token.
     *
     * @param capabilityId the capability ID; must not be null
     * @return immutable list of matching snapshots
     */
    List<ModuleSnapshot> findByCapability(CapabilityId capabilityId);

    /**
     * Checks whether a capability is currently available for execution (active or residual obligation).
     *
     * @param capabilityId the capability ID; must not be null
     * @return true if available
     */
    boolean isCapabilityAvailable(CapabilityId capabilityId);

    /**
     * Computes the aggregated operational health for the runtime, optionally scoped to a parent system.
     *
     * @param parentSystemId optional parent system ID filter; empty for global runtime health
     * @return the aggregated {@link ModuleHealth} report
     */
    ModuleHealth checkAggregateHealth(Optional<ModuleId> parentSystemId);

    /**
     * Re-applies the authoritative desired state after a runtime (re)start.
     * <p>
     * Every root module (SYSTEM or parentless module) whose restored desired state is
     * {@link ModuleDesiredState#ENABLED} is enabled in dependency order, and each SYSTEM cascade restores
     * only children whose desired state is ENABLED. Desired state and the configuration generation are not
     * mutated; disabled modules stay inactive and failures are reported in the returned results.
     *
     * @return completion stage yielding every root and child enable result produced by the reconciliation
     */
    CompletionStage<List<ModuleLifecycleResult>> reconcileDesiredStatesAsync();

    /**
     * Restores module desired states and executes transitions from a supplied configuration snapshot.
     *
     * @param snapshot the desired state snapshot to restore; must not be null
     */
    CompletionStage<Void> restoreFromSnapshotAsync(ModuleDesiredStateSnapshot snapshot);

    /**
     * Synchronous snapshot restoration for test inspection.
     */
    default void restoreFromSnapshot(ModuleDesiredStateSnapshot snapshot) {
        restoreFromSnapshotAsync(snapshot).toCompletableFuture().join();
    }

    /**
     * Captures a durable snapshot of all current module desired states and configuration generation.
     *
     * @return an immutable {@link ModuleDesiredStateSnapshot}
     */
    ModuleDesiredStateSnapshot createSnapshot();

    /**
     * Asynchronously shuts down all loaded modules in reverse topological dependency order.
     *
     * @return stage completing when all modules are cleanly closed
     */
    CompletionStage<Void> closeAsync();

    /**
     * Closes this registry, shutting down all active modules.
     */
    @Override
    void close();
}
