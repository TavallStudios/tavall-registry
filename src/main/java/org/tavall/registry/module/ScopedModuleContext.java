package org.tavall.registry.module;

import org.tavall.dependency.GenerationOwnedDependencyAccess;
import org.tavall.dependency.maps.interfaces.IDependencyMap;
import org.tavall.dependency.metadata.interfaces.IDependencyMetaData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generation-scoped execution and dependency context for a loaded module instance.
 * <p>
 * <b>Architectural Role:</b> Bridges the module generation to the single canonical host {@link IDependencyMap}
 * without creating a second or per-feature DI container. Enforces strict generation metadata ownership
 * and host-serialized mutation (Paper-safe).
 * <p>
 * <b>Host Serialization (Paper-Safe):</b> All mutations (dependency registration, replacement, removal,
 * resource management, quiesce, and teardown) must execute on the owning host lifecycle boundary thread.
 * Bypassing host boundary checks is strictly rejected.
 * <p>
 * <b>Exact Generation Ownership:</b> Binds instances directly into the host map. Tracks exact bound instances
 * so that context closure or removal NEVER removes new replacements or unrelated module tokens. Retained handles
 * from this generation fail closed upon unload or replacement via {@link GenerationOwnedDependencyAccess}.
 */
public final class ScopedModuleContext implements IModuleContext {

    private final ModuleDescriptor descriptor;
    private final long generation;
    private final IDependencyMap dependencyMap;
    private final Runnable hostBoundaryAssertion;

    // Exact instance tracking for generation ownership
    private final Map<Class<?>, Object> boundInstancesByToken = new LinkedHashMap<>();
    private final Deque<OwnedResource> resources = new ArrayDeque<>();

    private boolean closed;
    private boolean quiescing;
    private ResidualObligationPolicy residualPolicy = ResidualObligationPolicy.none();

    /**
     * Constructs a new {@link ScopedModuleContext}.
     *
     * @param descriptor            module descriptor; must not be null
     * @param generation            positive generation sequence number
     * @param dependencyMap         host DI container; must not be null
     * @param hostBoundaryAssertion assertion asserting execution on host lifecycle thread; must not be null
     */
    public ScopedModuleContext(
            ModuleDescriptor descriptor,
            long generation,
            IDependencyMap dependencyMap,
            Runnable hostBoundaryAssertion
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive: " + generation);
        }
        this.generation = generation;
        this.dependencyMap = Objects.requireNonNull(dependencyMap, "dependencyMap");
        this.hostBoundaryAssertion = Objects.requireNonNull(hostBoundaryAssertion, "hostBoundaryAssertion");
    }

    private void assertHostBoundary(String operation) {
        try {
            hostBoundaryAssertion.run();
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Host serialization violation during " + operation + " for module " + descriptor.id() + ": " + failure.getMessage(),
                    failure
            );
        }
    }

    @Override
    public ModuleDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public IDependencyMap dependencyMap() {
        return dependencyMap;
    }

    @Override
    public <T> T registerDependency(Class<T> type, T instance) {
        assertHostBoundary("registerDependency");
        requireOpen();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(instance, "instance");

        // Reject token collision with another module's existing registration
        if (dependencyMap.isInstanceRegistered(type)) {
            IDependencyMetaData<?, ?> existingMeta = dependencyMap.findMetaData(type);
            Object existingInstance = existingMeta == null ? null : existingMeta.getDependencyInstance();
            if (existingInstance != null && existingInstance != instance) {
                throw new IllegalStateException(
                        "Registration collision: token " + type.getName() + " is already registered by instance "
                                + existingInstance.getClass().getName() + "; module " + descriptor.id() + " cannot overwrite borrowed token"
                );
            }
        }

        T registered = dependencyMap.registerInstance(type, instance);
        boundInstancesByToken.put(type, registered);
        return registered;
    }

    @Override
    public <T> T replaceDependency(Class<T> type, T replacement) {
        assertHostBoundary("replaceDependency");
        requireOpen();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(replacement, "replacement");

        // Reject borrowed token mutations: can only replace what this generation context registered
        if (!boundInstancesByToken.containsKey(type)) {
            throw new IllegalStateException(
                    "Cannot replace dependency token " + type.getName() + " not registered by this generation context for module "
                            + descriptor.id()
            );
        }

        T replaced = dependencyMap.replaceInstance(type, () -> replacement);
        boundInstancesByToken.put(type, replaced);
        return replaced;
    }

    @Override
    public void removeDependency(Class<?> type) {
        assertHostBoundary("removeDependency");
        Objects.requireNonNull(type, "type");

        if (!boundInstancesByToken.containsKey(type)) {
            return;
        }

        Object ourInstance = boundInstancesByToken.remove(type);
        IDependencyMetaData<?, ?> meta = dependencyMap.findMetaData(type);

        // Exact metadata ownership check: NEVER remove new replacement or unrelated module tokens!
        if (meta != null && meta.getDependencyInstance() == ourInstance) {
            dependencyMap.removeDependency(type);
        } else if (ourInstance instanceof GenerationOwnedDependencyAccess owned
                && owned.isDependencyGenerationOwnedBy(dependencyMap)) {
            owned.unbindDependencyGeneration(dependencyMap);
        }
    }

    @Override
    public <T> T findDependency(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return dependencyMap.findInstance(type);
    }

    @Override
    public void registerResource(String description, AutoCloseable resource) {
        assertHostBoundary("registerResource");
        requireOpen();
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(resource, "resource");
        if (description.isBlank()) {
            throw new IllegalArgumentException("resource description must not be blank");
        }
        resources.addLast(new OwnedResource(description.trim(), resource));
    }

    @Override
    public List<String> registeredResourceDescriptions() {
        assertHostBoundary("registeredResourceDescriptions");
        List<String> list = new ArrayList<>(resources.size());
        for (OwnedResource res : resources) {
            list.add(res.description());
        }
        return List.copyOf(list);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isQuiescing() {
        return quiescing;
    }

    /**
     * Updates the quiescing state under host serialization.
     *
     * @param quiescing true if entering quiescing phase
     */
    public void setQuiescing(boolean quiescing) {
        assertHostBoundary("setQuiescing");
        this.quiescing = quiescing;
    }

    /**
     * Sets the active residual obligation policy under host serialization.
     *
     * @param residualPolicy the policy to apply during quiescing and disabled states
     */
    public void setResidualPolicy(ResidualObligationPolicy residualPolicy) {
        assertHostBoundary("setResidualPolicy");
        this.residualPolicy = Objects.requireNonNull(residualPolicy, "residualPolicy");
    }

    @Override
    public boolean allowsCapability(CapabilityId capabilityId) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        if (closed) {
            return false;
        }
        if (quiescing) {
            return residualPolicy.allowsResidualCapability(capabilityId);
        }
        return descriptor.providedCapabilities().contains(capabilityId);
    }

    @Override
    public void close() throws Exception {
        assertHostBoundary("close");
        if (closed) {
            return;
        }
        closed = true;
        Exception aggregateFailure = null;

        // 1. Release owned resources in LIFO order
        while (!resources.isEmpty()) {
            OwnedResource resource = resources.removeLast();
            try {
                resource.closeable().close();
            } catch (Exception ex) {
                if (aggregateFailure == null) {
                    aggregateFailure = new Exception("Failed to close one or more resources for module " + descriptor.id());
                }
                aggregateFailure.addSuppressed(new IllegalStateException("Resource cleanup failed: " + resource.description(), ex));
            }
        }

        // 2. Detach our exact registered instances from host dependency map
        List<Map.Entry<Class<?>, Object>> entries = new ArrayList<>(boundInstancesByToken.entrySet());
        boundInstancesByToken.clear();

        for (Map.Entry<Class<?>, Object> entry : entries) {
            Class<?> type = entry.getKey();
            Object ourInstance = entry.getValue();

            IDependencyMetaData<?, ?> meta = dependencyMap.findMetaData(type);
            // Invariant: ONLY remove from host map if the map still holds our exact instance!
            // If already replaced by a newer generation or owned by another module, do NOT touch map!
            if (meta != null && meta.getDependencyInstance() == ourInstance) {
                try {
                    dependencyMap.removeDependency(type);
                } catch (RuntimeException ex) {
                    if (aggregateFailure == null) {
                        aggregateFailure = new Exception("Failed to remove dependency " + type.getName() + " for module " + descriptor.id());
                    }
                    aggregateFailure.addSuppressed(ex);
                }
            } else if (ourInstance instanceof GenerationOwnedDependencyAccess owned
                    && owned.isDependencyGenerationOwnedBy(dependencyMap)) {
                // Ensure detached even if displaced
                try {
                    owned.unbindDependencyGeneration(dependencyMap);
                } catch (RuntimeException ex) {
                    if (aggregateFailure == null) {
                        aggregateFailure = new Exception("Failed to unbind generation for " + type.getName(), ex);
                    }
                    aggregateFailure.addSuppressed(ex);
                }
            }
        }

        if (aggregateFailure != null) {
            throw aggregateFailure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Module context is closed for module " + descriptor.id());
        }
    }

    private record OwnedResource(String description, AutoCloseable closeable) {
    }
}
