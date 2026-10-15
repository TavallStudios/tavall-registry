package org.tavall.registry.module;

/**
 * Closed enumeration representing the observable runtime lifecycle state of an installed module.
 * <p>
 * <b>Architectural Role:</b> Tracks the observed operational phase of each module generation
 * managed by {@link IModuleLifecycleRegistry}.
 * <p>
 * <b>Lifecycle &amp; Concurrency:</b> Transitions between states are strictly serialized on the host
 * lifecycle boundary thread. Retained handles from previous generations cannot participate in
 * active operations once transition to an unbinding state occurs.
 */
public enum ModuleLifecycleState {

    /**
     * Module descriptor has been registered, but its generation has not been initialized.
     */
    DISCOVERED,

    /**
     * Module generation is constructing its context and binding DI dependencies.
     */
    LOADING,

    /**
     * Generation has loaded and bound dependencies, but is not yet accepting operations.
     */
    LOADED,

    /**
     * Module lifecycle participant is starting up.
     */
    ENABLING,

    /**
     * Module is fully active and servicing its declared capabilities.
     */
    ENABLED,

    /**
     * Module is quiescing new work and draining pending operations.
     */
    DISABLING,

    /**
     * Module is disabled; new entries are denied, but residual obligated capabilities may be serviced.
     */
    DISABLED,

    /**
     * Module generation is being reloaded and replaced with a new instance.
     */
    RELOADING,

    /**
     * Module generation is disposing resources and unbinding DI dependencies.
     */
    UNLOADING,

    /**
     * Module generation has completely detached and released all resources.
     */
    UNLOADED,

    /**
     * Module has encountered an unhandled error during transition and failed closed.
     */
    FAILED;

    /**
     * Returns true if the module is operational or in the process of becoming active.
     *
     * @return true if state is {@link #ENABLED} or {@link #ENABLING}
     */
    public boolean isActive() {
        return this == ENABLED || this == ENABLING;
    }

    /**
     * Returns true if the module currently maintains an active generation context.
     *
     * @return true if loaded and not completely unloaded or failed
     */
    public boolean isLoaded() {
        return this != DISCOVERED && this != UNLOADED && this != FAILED;
    }

    /**
     * Returns true if an asynchronous lifecycle transition is currently in progress.
     *
     * @return true if in a transitioning state
     */
    public boolean isTransitioning() {
        return this == LOADING
                || this == ENABLING
                || this == DISABLING
                || this == RELOADING
                || this == UNLOADING;
    }
}
