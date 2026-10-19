package org.tavall.registry.module.operator;

import org.tavall.registry.module.ModuleLifecycleOperation;

/**
 * Result of an operator lifecycle request. A refused or unconfirmed request reports a machine-readable
 * {@code failureCode} such as {@code unauthorized}, {@code stale_config_generation},
 * {@code required_by_active_dependents} or {@code confirmation_required}.
 *
 * @param moduleId            target module
 * @param operation           requested operation
 * @param succeeded           whether the transition completed
 * @param state               observed state after the request
 * @param desiredState        desired state after the request
 * @param lifecycleGeneration lifecycle generation after the request
 * @param configGeneration    configuration generation after the request
 * @param failureCode         failure code, or null
 * @param failureMessage      failure description, or null
 * @param impact              impact preview the request was evaluated against
 */
public record ModuleOperationOutcome(
        String moduleId,
        ModuleLifecycleOperation operation,
        boolean succeeded,
        String state,
        String desiredState,
        long lifecycleGeneration,
        long configGeneration,
        String failureCode,
        String failureMessage,
        ModuleOperationImpact impact
) {
}
