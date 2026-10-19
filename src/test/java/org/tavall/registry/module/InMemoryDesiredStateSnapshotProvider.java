package org.tavall.registry.module;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory test double adapter for {@link IModuleDesiredStateSnapshotProvider}.
 * <p>
 * <b>Architectural Role:</b> Test fixture simulating asynchronous configuration snapshot loading and
 * atomic compare-and-set (CAS) updates during integration tests without external infrastructure dependencies.
 * <p>
 * <b>Fixture Isolation:</b> Backed by an explicit {@link InMemorySnapshotStore}. Independent instances
 * with separate stores never cross-contaminate. When multiple adapter instances share a store to simulate
 * controller recreation or clustering, CAS operations are atomic and synchronized across all adapters.
 */
public final class InMemoryDesiredStateSnapshotProvider implements IModuleDesiredStateSnapshotProvider {

    /**
     * Explicit in-memory backing storage shared across adapter instances for simulated controller recreation.
     */
    public static final class InMemorySnapshotStore {
        private final Map<RuntimeTarget, ModuleDesiredStateSnapshot> storage = new ConcurrentHashMap<>();

        public synchronized Optional<ModuleDesiredStateSnapshot> load(RuntimeTarget target) {
            Objects.requireNonNull(target, "target must not be null");
            return Optional.ofNullable(storage.get(target));
        }

        public synchronized boolean cas(RuntimeTarget target, long expectedConfigGeneration, ModuleDesiredStateSnapshot newSnapshot) {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(newSnapshot, "newSnapshot must not be null");

            ModuleDesiredStateSnapshot current = storage.get(target);
            long currentGen = current == null ? 0L : current.configGeneration();

            if (currentGen != expectedConfigGeneration) {
                return false;
            }

            storage.put(target, newSnapshot);
            return true;
        }

        public synchronized void save(RuntimeTarget target, ModuleDesiredStateSnapshot snapshot) {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            storage.put(target, snapshot);
        }

        public synchronized void clear() {
            storage.clear();
        }

        public synchronized int size() {
            return storage.size();
        }
    }

    private final InMemorySnapshotStore store;

    /**
     * Constructs an in-memory snapshot provider with its own isolated backing store.
     */
    public InMemoryDesiredStateSnapshotProvider() {
        this(new InMemorySnapshotStore());
    }

    /**
     * Constructs an in-memory snapshot provider backed by the specified shared store.
     *
     * @param store the shared backing store; must not be null
     */
    public InMemoryDesiredStateSnapshotProvider(InMemorySnapshotStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public InMemorySnapshotStore store() {
        return store;
    }

    @Override
    public CompletionStage<Optional<ModuleDesiredStateSnapshot>> loadSnapshotAsync(RuntimeTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        return CompletableFuture.completedFuture(store.load(target));
    }

    @Override
    public CompletionStage<Boolean> casSnapshotAsync(
            RuntimeTarget target,
            long expectedConfigGeneration,
            ModuleDesiredStateSnapshot newSnapshot
    ) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(newSnapshot, "newSnapshot must not be null");
        boolean result = store.cas(target, expectedConfigGeneration, newSnapshot);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void saveSnapshot(RuntimeTarget target, ModuleDesiredStateSnapshot snapshot) {
        store.save(target, snapshot);
    }

    @Override
    public boolean isProductionPersistent() {
        return false;
    }

    public void clear() {
        store.clear();
    }
}
