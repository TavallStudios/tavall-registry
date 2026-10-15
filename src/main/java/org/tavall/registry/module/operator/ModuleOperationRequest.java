package org.tavall.registry.module.operator;

import org.tavall.registry.module.ModuleLifecycleOperation;

import java.util.Objects;

/**
 * An operator's lifecycle decision about one module, fenced by the generations the operator observed.
 * Destructive operations also carry the confirmation token returned by the impact preview.
 *
 * @param moduleId                    target module
 * @param operation                   requested lifecycle operation
 * @param expectedConfigGeneration    configuration generation the operator observed; must be positive
 * @param expectedLifecycleGeneration lifecycle generation of the module the operator observed
 * @param confirmation                confirmation token for destructive operations, or null
 */
public record ModuleOperationRequest(
        String moduleId,
        ModuleLifecycleOperation operation,
        long expectedConfigGeneration,
        long expectedLifecycleGeneration,
        String confirmation
) {
    public ModuleOperationRequest {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(operation, "operation");
        if (moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId must not be blank");
        }
        if (expectedConfigGeneration <= 0L) {
            throw new IllegalArgumentException("expectedConfigGeneration must be positive");
        }
        if (expectedLifecycleGeneration < 0L) {
            throw new IllegalArgumentException("expectedLifecycleGeneration must not be negative");
        }
    }
}
