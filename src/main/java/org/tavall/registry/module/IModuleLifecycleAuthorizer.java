package org.tavall.registry.module;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Authorization contract for module lifecycle mutation requests.
 * <p>
 * <b>Architectural Role:</b> Intercepts lifecycle requests before state mutation, enforcing
 * permissions, operator authorizations, and safety guards (e.g. preventing unverified production
 * disables or unauthorized reloads).
 * <p>
 * <b>Concurrency:</b> Authorizer callbacks execute asynchronously and return a {@link CompletionStage}.
 */
@FunctionalInterface
public interface IModuleLifecycleAuthorizer {

    /**
     * Determines whether the given lifecycle request is authorized.
     *
     * @param request the proposed mutation request; must not be null
     * @return a stage completing with {@code true} if authorized, or {@code false} if denied
     */
    CompletionStage<Boolean> authorizeAsync(ModuleLifecycleRequest request);

    /**
     * Default authorizer allowing all requests.
     *
     * @return a permissive authorizer
     */
    static IModuleLifecycleAuthorizer permissive() {
        return request -> CompletableFuture.completedFuture(true);
    }
}
