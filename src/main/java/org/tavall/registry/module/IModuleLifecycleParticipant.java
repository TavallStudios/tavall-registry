package org.tavall.registry.module;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Lifecycle participant contract implemented by modules requiring asynchronous initialization,
 * quiescing, draining, or teardown.
 * <p>
 * <b>Architectural Role:</b> Bridges module-specific runtime logic into the universal lifecycle
 * manager. Replaces ad-hoc bootstrap and teardown routines with a typed four-phase lifecycle:
 * start, quiesce, drain, close.
 * <p>
 * <b>Lifecycle & Concurrency:</b> Operations return {@link CompletionStage} instances. The lifecycle
 * registry marshals stage completions back to the host lifecycle boundary thread before modifying
 * module state or unbinding DI resources.
 */
public interface IModuleLifecycleParticipant {

    /**
     * Invoked when the module is loading into memory.
     * <p>
     * The participant can register preliminary state and preparation hooks.
     *
     * @param context the module execution context
     * @return stage that completes when loading is complete
     */
    default CompletionStage<Void> onLoadAsync(IModuleContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invoked when the module is enabling.
     * <p>
     * The participant binds DI dependencies, starts listeners, and readies runtime handlers.
     *
     * @param context the module execution context
     * @return stage that completes when startup is complete
     */
    default CompletionStage<Void> onStartAsync(IModuleContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invoked when the module begins disabling.
     * <p>
     * The participant halts acceptance of new work while continuing to process in-flight operations.
     *
     * @param context the module execution context
     * @return stage that completes when quiescing has begun
     */
    default CompletionStage<Void> onQuiesceAsync(IModuleContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invoked after quiescing to drain, checkpoint, or safely cancel in-flight operations.
     *
     * @param context the module execution context
     * @return stage that completes when all in-flight work has drained
     */
    default CompletionStage<Void> onDrainAsync(IModuleContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invoked during module unloading or final teardown to release external handles and connections.
     *
     * @param context the module execution context
     * @return stage that completes when closing is finished
     */
    default CompletionStage<Void> onCloseAsync(IModuleContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Declares the residual capability obligation policy for this participant.
     * <p>
     * Crucial for systems like Tavall Pay where disabling new transaction initiation must not
     * discard pending settlement, refunds, dispute handling, or webhook reconciliation.
     *
     * @return the residual obligation policy; never null
     */
    default ResidualObligationPolicy residualObligationPolicy() {
        return ResidualObligationPolicy.none();
    }
}
