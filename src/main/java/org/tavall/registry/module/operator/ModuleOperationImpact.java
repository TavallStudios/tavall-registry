package org.tavall.registry.module.operator;

import org.tavall.registry.module.ModuleLifecycleOperation;

import java.util.List;

/**
 * Preview of what an operation would affect. A destructive operation (disable, unload, reload, rollback)
 * must be confirmed with {@link #confirmationToken()}, which is bound to the current configuration
 * generation, so a confirmation cannot be replayed after the runtime configuration changed.
 *
 * @param moduleId          target module
 * @param operation         previewed operation
 * @param destructive       whether the operation stops or replaces running work
 * @param affectedModules   the module plus the active FEATURES a SYSTEM operation cascades to
 * @param confirmationToken token to echo for a destructive operation; null when none is needed
 */
public record ModuleOperationImpact(
        String moduleId,
        ModuleLifecycleOperation operation,
        boolean destructive,
        List<String> affectedModules,
        String confirmationToken
) {
    public ModuleOperationImpact {
        affectedModules = affectedModules == null ? List.of() : List.copyOf(affectedModules);
    }
}
