package org.tavall.registry.module;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of module desired states and configuration generation for a runtime target.
 * <p>
 * <b>Architectural Role:</b> Durable state representation used by deployment profiles, CD pipelines,
 * and restart restoration. Ensures explicitly disabled modules do not accidentally re-enable upon restart.
 *
 * @param target           the designated runtime target
 * @param configGeneration configuration generation sequence number
 * @param desiredStates    immutable map of module IDs to their desired states
 * @param timestamp        creation timestamp of this snapshot
 */
public record ModuleDesiredStateSnapshot(
        RuntimeTarget target,
        long configGeneration,
        Map<ModuleId, ModuleDesiredState> desiredStates,
        Instant timestamp
) {

    /**
     * Validates and constructs an immutable {@link ModuleDesiredStateSnapshot}.
     */
    public ModuleDesiredStateSnapshot {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (configGeneration < 0L) {
            throw new IllegalArgumentException("configGeneration must not be negative");
        }
        desiredStates = desiredStates == null ? Map.of() : Map.copyOf(desiredStates);
    }

    /**
     * Factory creating a snapshot at the current time.
     *
     * @param target           runtime target
     * @param configGeneration configuration generation
     * @param desiredStates    map of desired states
     * @return a new {@link ModuleDesiredStateSnapshot}
     */
    public static ModuleDesiredStateSnapshot now(
            RuntimeTarget target,
            long configGeneration,
            Map<ModuleId, ModuleDesiredState> desiredStates
    ) {
        return new ModuleDesiredStateSnapshot(target, configGeneration, desiredStates, Instant.now());
    }

    /**
     * Gets the desired state for the specified module, defaulting to DISABLED if absent.
     *
     * @param moduleId target module ID
     * @return desired state
     */
    public ModuleDesiredState getDesiredState(ModuleId moduleId) {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        return desiredStates.getOrDefault(moduleId, ModuleDesiredState.DISABLED);
    }
}
