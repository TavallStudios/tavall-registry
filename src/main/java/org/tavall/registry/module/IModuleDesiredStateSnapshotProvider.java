package org.tavall.registry.module;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Storage provider interface for loading and persisting {@link ModuleDesiredStateSnapshot} instances.
 * <p>
 * <b>Architectural Role:</b> Bridges the in-process module lifecycle registry to the external
 * authoritative configuration store (e.g. CD deployment profile, Redis/Postgres configuration authority).
 * <p>
 * <b>Invariants:</b>
 * <ul>
 *   <li>Mutations must be persisted via compare-and-set (CAS) against expected configuration generation.</li>
 *   <li>Store I/O must execute asynchronously off the host/Paper thread.</li>
 *   <li>Implementations must state via {@link #isProductionPersistent()} whether they provide genuine durable persistence.</li>
 * </ul>
 */
public interface IModuleDesiredStateSnapshotProvider {

    /**
     * Loads the latest authoritative desired state snapshot for the given runtime target asynchronously.
     *
     * @param target the runtime target; must not be null
     * @return completion stage returning optional snapshot if present
     */
    CompletionStage<Optional<ModuleDesiredStateSnapshot>> loadSnapshotAsync(RuntimeTarget target);

    /**
     * Atomically compares and sets (CAS) the desired state snapshot for the target runtime.
     * <p>
     * Guarantees that concurrent configuration updates cannot clobber intermediate desired states.
     *
     * @param target                   the runtime target; must not be null
     * @param expectedConfigGeneration expected current config generation (0 if creating initial snapshot)
     * @param newSnapshot              the new snapshot to commit; must not be null
     * @return completion stage returning true if successfully updated; false on generation conflict
     */
    CompletionStage<Boolean> casSnapshotAsync(
            RuntimeTarget target,
            long expectedConfigGeneration,
            ModuleDesiredStateSnapshot newSnapshot
    );

    /**
     * Synchronous convenience accessor for test double inspection. Must NOT be invoked on host thread in production.
     */
    default Optional<ModuleDesiredStateSnapshot> loadSnapshot(RuntimeTarget target) {
        return loadSnapshotAsync(target).toCompletableFuture().join();
    }

    /**
     * Synchronous convenience save for test double inspection. Must NOT be invoked on host thread in production.
     */
    default void saveSnapshot(RuntimeTarget target, ModuleDesiredStateSnapshot snapshot) {
        casSnapshotAsync(target, snapshot.configGeneration() - 1, snapshot).toCompletableFuture().join();
    }

    /**
     * Returns true if this provider guarantees durable, persistent storage across process restarts.
     *
     * @return true for production persistence, false for in-memory or transient test doubles
     */
    boolean isProductionPersistent();
}
