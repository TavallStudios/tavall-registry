package org.tavall.registry.module;

/**
 * Functional probe evaluating live module health.
 * <p>
 * <b>Architectural Role:</b> Executed periodically or on-demand by registries and operators
 * to assess whether a module is operating within nominal parameters.
 * <p>
 * <b>Concurrency:</b> Implementations must be thread-safe and non-blocking or bounded in duration.
 */
@FunctionalInterface
public interface IModuleHealthCheck {

    /**
     * Executes the health assessment probe.
     *
     * @return the observed {@link ModuleHealth} report; must not be null
     */
    ModuleHealth checkHealth();
}
