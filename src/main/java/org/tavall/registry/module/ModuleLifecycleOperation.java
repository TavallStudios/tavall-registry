package org.tavall.registry.module;

/**
 * Closed enumeration of lifecycle mutation operations supported by the universal lifecycle framework.
 * <p>
 * <b>Architectural Role:</b> Identifies requested operational transitions in {@link ModuleLifecycleRequest}.
 */
public enum ModuleLifecycleOperation {

    /**
     * Initializes module context, binds DI dependencies, and prepares the generation.
     */
    LOAD,

    /**
     * Activates the module and its participant, enabling capability dispatch.
     */
    ENABLE,

    /**
     * Quiesces and drains the module, halting new entry while honoring residual obligations.
     */
    DISABLE,

    /**
     * Atomically replaces the current module generation with a fresh generation without runtime restart.
     */
    RELOAD,

    /**
     * Tears down module resources, detaches DI bindings, and frees the generation.
     */
    UNLOAD,

    /**
     * Reverts to a previous compatible configuration or generation snapshot.
     */
    ROLLBACK
}
