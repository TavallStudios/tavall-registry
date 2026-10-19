package org.tavall.registry.module;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles request deduplication, replay idempotency, and in-flight conflict detection.
 * <p>
 * <b>Architectural Role:</b> Ensures that:
 * <ul>
 *   <li>Identical repeated requests (e.g. duplicate RELOAD or ENABLE) return cached results with {@code changed=false} and create ZERO extra generations.</li>
 *   <li>Concurrent conflicting operations targeting the same module reject before side effects.</li>
 * </ul>
 * <p>
 * <b>Security Invariant:</b> Replay lookup and in-flight reservation must only occur AFTER authorization
 * verification to prevent unauthorized cached-result disclosure and denial-of-service reservations.
 */
final class ReplayIdempotencyHandler {

    private final Map<String, ModuleLifecycleResult> completedOperations = new ConcurrentHashMap<>();
    // Each reservation is owned by the correlation that took it, so an internal sub-operation on the same
    // module (an unload drain, a rollback transition) cannot release the outer operation's reservation.
    private final Map<ModuleId, String> inFlightModules = new ConcurrentHashMap<>();

    /**
     * Checks if a request has already been completed or if a concurrent conflict exists.
     *
     * @param request the candidate request; must not be null
     * @param entry   the target module entry if present; null if not registered
     * @return non-null result if deduplicated or conflicted; null if request should proceed
     */
    synchronized ModuleLifecycleResult checkPreExecution(ModuleLifecycleRequest request, ModuleEntry entry) {
        Objects.requireNonNull(request, "request must not be null");

        // 1. Replay check: identical correlation token previously completed
        ModuleLifecycleResult cached = completedOperations.get(request.correlationId());
        if (cached != null) {
            // Return cached result with changed=false (zero extra generations released)
            return ModuleLifecycleResult.success(
                    cached.moduleId(),
                    cached.operation(),
                    cached.previousState(),
                    cached.currentState(),
                    cached.desiredState(),
                    cached.lifecycleGeneration(),
                    cached.configGeneration(),
                    false
            );
        }

        // 2. In-flight conflict check: another operation currently executing on this module
        if (inFlightModules.containsKey(request.moduleId())) {
            ModuleLifecycleState state = entry == null ? ModuleLifecycleState.DISCOVERED : entry.state();
            ModuleDesiredState desired = entry == null ? ModuleDesiredState.DISABLED : entry.desiredState();
            long gen = entry == null ? 0L : entry.lifecycleGeneration();
            long cfg = entry == null ? 0L : entry.configGeneration();
            return ModuleLifecycleResult.failure(
                    request.moduleId(),
                    request.operation(),
                    state,
                    state,
                    desired,
                    gen,
                    cfg,
                    ModuleFailure.of("conflict_in_flight", "Another operation is currently in progress for module: " + request.moduleId())
            );
        }

        inFlightModules.put(request.moduleId(), request.correlationId());
        return null;
    }

    /**
     * Completes an operation, clearing its in-flight state and caching its result for replay deduplication.
     *
     * @param request the completed request; must not be null
     * @param result  the outcome result; may be null on failure
     */
    synchronized void recordCompletion(ModuleLifecycleRequest request, ModuleLifecycleResult result) {
        inFlightModules.remove(request.moduleId(), request.correlationId());
        if (result != null && result.succeeded()) {
            completedOperations.put(request.correlationId(), result);
        }
    }

    /**
     * Aborts an in-flight operation without caching successful replay.
     *
     * @param request the request whose reservation is released; must not be null
     */
    synchronized void clearInFlight(ModuleLifecycleRequest request) {
        inFlightModules.remove(request.moduleId(), request.correlationId());
    }

    /**
     * Clears all cached completed operations.
     */
    synchronized void clear() {
        inFlightModules.clear();
        completedOperations.clear();
    }
}
