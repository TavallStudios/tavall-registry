package org.tavall.registry.module;

import java.util.concurrent.CompletableFuture;

/** Test-only allow-all policies. Production composition must supply real authorization and rollback authority. */
final class LifecycleTestPolicies {
    private LifecycleTestPolicies() {
    }

    static IModuleLifecycleAuthorizer allowAll() {
        return request -> CompletableFuture.completedFuture(true);
    }

    static IRollbackCompatibilityValidator acceptAllRollbacks() {
        return (target, currentGen, candidate, descriptors) ->
                CompletableFuture.completedFuture(IRollbackCompatibilityValidator.ValidationResult.valid());
    }
}
