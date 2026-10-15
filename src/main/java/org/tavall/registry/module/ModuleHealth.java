package org.tavall.registry.module;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable report of a module's observed operational health.
 * <p>
 * <b>Architectural Role:</b> Diagnostic data object returned by module health probes and aggregated
 * across parent systems and runtime targets.
 *
 * @param status  discrete health status
 * @param summary human-readable summary of the health condition
 * @param details immutable map of contextual metric or diagnostic details
 */
public record ModuleHealth(
        ModuleHealthStatus status,
        String summary,
        Map<String, Object> details
) {

    /**
     * Validates and constructs an immutable {@link ModuleHealth} instance.
     *
     * @param status  discrete status; must not be null
     * @param summary condition summary; must not be blank
     * @param details diagnostic metadata map; null treated as empty
     * @throws NullPointerException     if {@code status} or {@code summary} is null
     * @throws IllegalArgumentException if {@code summary} is blank
     */
    public ModuleHealth {
        Objects.requireNonNull(status, "ModuleHealth status must not be null");
        Objects.requireNonNull(summary, "ModuleHealth summary must not be null");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("ModuleHealth summary must not be blank");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * Convenience factory for a nominal healthy status report.
     *
     * @param summary summary message
     * @return a healthy {@link ModuleHealth} report
     */
    public static ModuleHealth healthy(String summary) {
        return new ModuleHealth(ModuleHealthStatus.HEALTHY, summary, Map.of());
    }

    /**
     * Convenience factory for a degraded status report.
     *
     * @param summary summary message
     * @param details diagnostic map
     * @return a degraded {@link ModuleHealth} report
     */
    public static ModuleHealth degraded(String summary, Map<String, Object> details) {
        return new ModuleHealth(ModuleHealthStatus.DEGRADED, summary, details);
    }

    /**
     * Convenience factory for an unhealthy status report.
     *
     * @param summary summary message
     * @param details diagnostic map
     * @return an unhealthy {@link ModuleHealth} report
     */
    public static ModuleHealth unhealthy(String summary, Map<String, Object> details) {
        return new ModuleHealth(ModuleHealthStatus.UNHEALTHY, summary, details);
    }

    /**
     * Convenience factory for an unknown status report.
     *
     * @param summary summary message
     * @return an unknown {@link ModuleHealth} report
     */
    public static ModuleHealth unknown(String summary) {
        return new ModuleHealth(ModuleHealthStatus.UNKNOWN, summary, Map.of());
    }
}
