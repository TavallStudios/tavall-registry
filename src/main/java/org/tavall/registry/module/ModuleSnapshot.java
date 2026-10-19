package org.tavall.registry.module;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable public snapshot of module state, configuration, and capabilities.
 * <p>
 * <b>Architectural Role:</b> Decouples the public query surface of {@link IModuleLifecycleRegistry}
 * from mutable internal {@link ModuleEntry} state. Callers inspect module metadata, health,
 * and generations without access to internal mutation handles.
 *
 * @param id                      unique module identifier; not null
 * @param descriptor              module descriptor; not null
 * @param state                   observed runtime lifecycle state; not null
 * @param desiredState            declarative desired state; not null
 * @param lifecycleGeneration     positive generation sequence number
 * @param configGeneration        configuration generation sequence number
 * @param residualPolicy          residual obligation policy; not null
 * @param health                  latest aggregate health status; not null
 * @param lastFailure             last recorded failure; null if healthy
 */
public record ModuleSnapshot(
        ModuleId id,
        ModuleDescriptor descriptor,
        ModuleLifecycleState state,
        ModuleDesiredState desiredState,
        long lifecycleGeneration,
        long configGeneration,
        ResidualObligationPolicy residualPolicy,
        ModuleHealth health,
        ModuleFailure lastFailure
) {

    public ModuleSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(desiredState, "desiredState must not be null");
        Objects.requireNonNull(residualPolicy, "residualPolicy must not be null");
        Objects.requireNonNull(health, "health must not be null");
    }

    /**
     * Checks if the module is in an active running state ({@link ModuleLifecycleState#ENABLED}).
     *
     * @return true if currently enabled
     */
    public boolean isActive() {
        return state.isActive();
    }

    /**
     * Checks if the module has bound DI dependencies ({@link ModuleLifecycleState#LOADED} or {@link ModuleLifecycleState#ENABLED}).
     *
     * @return true if loaded or enabled
     */
    public boolean isLoaded() {
        return state.isLoaded();
    }

    /**
     * Optional accessor for the last recorded lifecycle failure.
     *
     * @return optional containing the last failure if present
     */
    public Optional<ModuleFailure> failure() {
        return Optional.ofNullable(lastFailure);
    }
}
