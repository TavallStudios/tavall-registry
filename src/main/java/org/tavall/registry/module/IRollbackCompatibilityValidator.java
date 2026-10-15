package org.tavall.registry.module;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Validates candidate configuration snapshots for contract and schema compatibility before rollback mutation.
 * <p>
 * <b>Architectural Role:</b> Bridges rollback operations to the CD / configuration authority.
 * Ensures that attempting to roll back to a prior snapshot does not silently apply incompatible
 * descriptors, dropped capabilities, or invalid schema configurations.
 */
public interface IRollbackCompatibilityValidator {

    /**
     * Compatibility validation result.
     *
     * @param compatible whether candidate snapshot is compatible for rollback
     * @param reason     failure explanation if incompatible; null if compatible
     */
    record ValidationResult(boolean compatible, String reason) {

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult incompatible(String reason) {
            return new ValidationResult(false, Objects.requireNonNull(reason, "reason must not be null"));
        }
    }

    /**
     * Validates whether candidate snapshot is compatible with currently registered module descriptors.
     *
     * @param target                  target runtime host
     * @param currentConfigGeneration current active config generation
     * @param candidateSnapshot       candidate snapshot to roll back to
     * @param registeredDescriptors   currently registered module descriptors
     * @return completion stage yielding validation result
     */
    CompletionStage<ValidationResult> validateRollbackCompatibility(
            RuntimeTarget target,
            long currentConfigGeneration,
            ModuleDesiredStateSnapshot candidateSnapshot,
            Map<ModuleId, ModuleDescriptor> registeredDescriptors
    );

    /**
     * Permissive validator for integration tests where artifact validation is mocked.
     *
     * @return permissive validator instance
     */
    static IRollbackCompatibilityValidator permissive() {
        return (target, currentGen, candidate, descriptors) ->
                CompletableFuture.completedFuture(ValidationResult.valid());
    }
}
