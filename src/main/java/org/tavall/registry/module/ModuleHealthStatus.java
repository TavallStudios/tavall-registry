package org.tavall.registry.module;

/**
 * Closed enumeration representing the discrete operational health statuses of a module.
 * <p>
 * <b>Architectural Role:</b> Differentiates between optional feature degradation and critical
 * system-level operational outage.
 */
public enum ModuleHealthStatus {

    /**
     * Module is completely healthy and operating within nominal parameters.
     */
    HEALTHY,

    /**
     * Module is operating with reduced performance, non-critical sub-feature failures, or non-blocking issues.
     */
    DEGRADED,

    /**
     * Module has experienced a severe failure or can no longer satisfy its required capabilities.
     */
    UNHEALTHY,

    /**
     * Module health has not been assessed or cannot be determined.
     */
    UNKNOWN
}
