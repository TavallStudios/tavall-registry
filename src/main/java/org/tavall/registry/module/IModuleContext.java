package org.tavall.registry.module;

import org.tavall.dependency.maps.interfaces.IDependencyMap;

import java.util.List;

/**
 * Generation-scoped execution and dependency context provided to a module participant.
 * <p>
 * <b>Architectural Role:</b> Bridges the module generation to the canonical host {@link IDependencyMap}
 * without creating a second or per-feature DI container. Tracks generation-owned resources and enforces
 * capability access policies (including residual obligations during quiesce/drain).
 * <p>
 * <b>Lifecycle &amp; Concurrency:</b> Thread-safe. When the generation is unloaded or replaced, this context
 * disposes all registered resources in LIFO order and removes its registered dependencies from the host DI map.
 */
public interface IModuleContext extends AutoCloseable {

    /**
     * Returns the module descriptor defining this module generation.
     *
     * @return the descriptor; never null
     */
    ModuleDescriptor descriptor();

    /**
     * Returns the monotonic generation sequence number of this module instance.
     *
     * @return the generation number (positive integer)
     */
    long generation();

    /**
     * Returns the underlying canonical host {@link IDependencyMap}.
     * <p>
     * <b>Note:</b> There is only one DI container per host/runtime. Modules bind directly
     * into this shared map using generation-aware metadata.
     *
     * @return the host dependency map; never null
     */
    IDependencyMap dependencyMap();

    /**
     * Registers a typed dependency instance into the host dependency map under this generation.
     * <p>
     * If the instance implements {@link org.tavall.dependency.GenerationOwnedDependencyAccess},
     * the host dependency map binds it to the generation. Upon unload or replacement, the map
     * automatically detaches the instance so retained handles fail closed.
     *
     * @param <T>      the contract/interface type
     * @param type     the contract class token; must not be null
     * @param instance the implementation instance; must not be null
     * @return the registered instance
     */
    <T> T registerDependency(Class<T> type, T instance);

    /**
     * Replaces an existing typed dependency binding in the host dependency map with a new instance.
     * <p>
     * Automatically detaches the displaced instance if generation-owned, causing stale references
     * to fail closed.
     *
     * @param <T>         the contract/interface type
     * @param type        the contract class token; must not be null
     * @param replacement the new implementation instance; must not be null
     * @return the newly bound replacement instance
     */
    <T> T replaceDependency(Class<T> type, T replacement);

    /**
     * Removes a previously registered typed dependency from the host dependency map.
     *
     * @param type the dependency type token to remove
     */
    void removeDependency(Class<?> type);

    /**
     * Resolves a dependency from the host dependency map.
     *
     * @param <T>  the dependency type
     * @param type the contract class token; must not be null
     * @return the resolved instance, or null if not registered
     */
    <T> T findDependency(Class<T> type);

    /**
     * Registers an {@link AutoCloseable} resource owned by this generation for LIFO teardown.
     *
     * @param description human-readable diagnostic description of the resource; must not be blank
     * @param resource    the closeable resource; must not be null
     */
    void registerResource(String description, AutoCloseable resource);

    /**
     * Returns an immutable list of descriptions of all currently registered resources.
     *
     * @return list of resource descriptions
     */
    List<String> registeredResourceDescriptions();

    /**
     * Returns true if this module context has been closed or unmounted.
     *
     * @return true if closed
     */
    boolean isClosed();

    /**
     * Returns true if this module is currently in the process of quiescing and draining in-flight work.
     *
     * @return true if quiescing
     */
    boolean isQuiescing();

    /**
     * Checks whether the specified capability is currently permitted to be executed by this module,
     * taking into account active versus quiescing/disabled state and residual obligation policies.
     *
     * @param capabilityId the capability token to check; must not be null
     * @return true if permitted
     */
    boolean allowsCapability(CapabilityId capabilityId);
}
