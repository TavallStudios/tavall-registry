package org.tavall.registry.module;

import java.util.Objects;

/**
 * Immutable report of a completed or failed module lifecycle transition.
 * <p>
 * <b>Architectural Role:</b> Typed result boundary returning state transitions, active generation tokens,
 * idempotency flags, and detailed failure diagnostics to callers.
 *
 * @param moduleId            target module ID
 * @param operation           the attempted operation
 * @param previousState       observed state prior to the operation
 * @param currentState        observed state following the operation
 * @param desiredState        authoritative desired state following the operation
 * @param lifecycleGeneration generation sequence number of the module instance
 * @param configGeneration    configuration generation sequence number
 * @param changed             true if this request actually altered observed or desired state;
 *                            false if the operation was idempotent
 * @param failure             diagnostic failure details, or null if the operation succeeded
 */
public record ModuleLifecycleResult(
        ModuleId moduleId,
        ModuleLifecycleOperation operation,
        ModuleLifecycleState previousState,
        ModuleLifecycleState currentState,
        ModuleDesiredState desiredState,
        long lifecycleGeneration,
        long configGeneration,
        boolean changed,
        ModuleFailure failure
) {

    /**
     * Validates and constructs an immutable {@link ModuleLifecycleResult}.
     */
    public ModuleLifecycleResult {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(currentState, "currentState must not be null");
        Objects.requireNonNull(desiredState, "desiredState must not be null");
    }

    /**
     * Returns true if the operation succeeded and is not in an intermediate transitioning state.
     *
     * @return true if successful
     */
    public boolean succeeded() {
        return failure == null && !currentState.isTransitioning();
    }

    /**
     * Returns true if the operation failed.
     *
     * @return true if failed
     */
    public boolean isFailed() {
        return failure != null;
    }

    /**
     * Factory for constructing a successful mutation result.
     *
     * @param moduleId            target module ID
     * @param operation           attempted operation
     * @param previousState       prior state
     * @param currentState        current state
     * @param desiredState        desired state
     * @param lifecycleGeneration active generation
     * @param configGeneration    active config generation
     * @param changed             whether state changed
     * @return a successful {@link ModuleLifecycleResult}
     */
    public static ModuleLifecycleResult success(
            ModuleId moduleId,
            ModuleLifecycleOperation operation,
            ModuleLifecycleState previousState,
            ModuleLifecycleState currentState,
            ModuleDesiredState desiredState,
            long lifecycleGeneration,
            long configGeneration,
            boolean changed
    ) {
        return new ModuleLifecycleResult(
                moduleId,
                operation,
                previousState,
                currentState,
                desiredState,
                lifecycleGeneration,
                configGeneration,
                changed,
                null
        );
    }

    /**
     * Factory for constructing a failed mutation result.
     *
     * @param moduleId            target module ID
     * @param operation           attempted operation
     * @param previousState       prior state
     * @param currentState        current state
     * @param desiredState        desired state
     * @param lifecycleGeneration active generation
     * @param configGeneration    active config generation
     * @param failure             failure cause
     * @return a failed {@link ModuleLifecycleResult}
     */
    public static ModuleLifecycleResult failure(
            ModuleId moduleId,
            ModuleLifecycleOperation operation,
            ModuleLifecycleState previousState,
            ModuleLifecycleState currentState,
            ModuleDesiredState desiredState,
            long lifecycleGeneration,
            long configGeneration,
            ModuleFailure failure
    ) {
        return new ModuleLifecycleResult(
                moduleId,
                operation,
                previousState,
                currentState,
                desiredState,
                lifecycleGeneration,
                configGeneration,
                false,
                Objects.requireNonNull(failure, "failure")
        );
    }
}
