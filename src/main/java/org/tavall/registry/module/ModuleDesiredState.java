package org.tavall.registry.module;

/**
 * Closed enumeration representing the declared desired activation state for a module.
 * <p>
 * <b>Architectural Role:</b> Decouples declarative intent (persisted in configuration snapshots
 * or module profiles) from observed runtime execution states.
 * <p>
 * <b>Invariants:</b> A module whose desired state is {@link #DISABLED} must not be activated
 * during system-level cascades or runtime restarts, preserving independent operator choices.
 */
public enum ModuleDesiredState {

    /**
     * The module is intended to be active and operational.
     */
    ENABLED,

    /**
     * The module is explicitly disabled by configuration or operator intent.
     */
    DISABLED
}
