package org.tavall.registry.module;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * Paper-safe host lifecycle execution gateway and serialization boundary.
 * <p>
 * <b>Architectural Role:</b> Enforces that all lifecycle state transitions, context mutations,
 * and DI container modifications occur strictly within the designated host thread context
 * (e.g. Paper Bukkit primary thread, Node main loop, Pay gateway thread) under serialized
 * monitor ownership.
 * <p>
 * <b>Deadlock Prevention:</b> Distinguishes between asynchronous dispatch and host-thread execution.
 * Synchronous joins on the host thread while completions are queued to it are strictly prevented.
 */
public final class HostLifecycleBoundary {

    private final Executor hostGatewayExecutor;
    private final Predicate<Thread> hostThreadPredicate;
    private final Object monitor = new Object();

    /**
     * Constructs a {@link HostLifecycleBoundary}.
     *
     * @param hostGatewayExecutor executor dispatching tasks onto the host thread; must not be null
     * @param hostThreadPredicate predicate verifying that a thread is the designated host thread; must not be null
     */
    public HostLifecycleBoundary(
            Executor hostGatewayExecutor,
            Predicate<Thread> hostThreadPredicate
    ) {
        this.hostGatewayExecutor = Objects.requireNonNull(hostGatewayExecutor, "hostGatewayExecutor must not be null");
        this.hostThreadPredicate = Objects.requireNonNull(hostThreadPredicate, "hostThreadPredicate must not be null");
    }

    /**
     * Creates a test boundary operating on a specified designated thread with inline execution.
     *
     * @param designatedThread designated thread
     * @return test host boundary
     */
    public static HostLifecycleBoundary direct(Thread designatedThread) {
        Objects.requireNonNull(designatedThread, "designatedThread must not be null");
        return new HostLifecycleBoundary(
                Runnable::run,
                thread -> thread == designatedThread
        );
    }

    /**
     * Verifies that the current execution thread is the designated host thread.
     *
     * @return true if currently executing on host thread
     */
    public boolean isHostThread() {
        return hostThreadPredicate.test(Thread.currentThread());
    }

    /**
     * Asserts that execution is synchronized on the boundary monitor AND running on the designated host thread.
     *
     * @param operationName name of the operation being asserted
     * @throws IllegalStateException if monitor is not held or thread is not the designated host thread
     */
    public void assertHostBoundary(String operationName) {
        if (!Thread.holdsLock(monitor)) {
            throw new IllegalStateException("Host serialization violation: thread must hold boundary monitor for " + operationName);
        }
        if (!isHostThread()) {
            throw new IllegalStateException("Host serialization violation: thread is not designated host thread: "
                    + Thread.currentThread().getName() + " for " + operationName);
        }
    }

    /**
     * Asserts boundary without operation name.
     */
    public void assertHostBoundary() {
        assertHostBoundary("mutation");
    }

    /**
     * The mutual exclusion monitor for host-serialized mutations.
     *
     * @return monitor object
     */
    public Object monitor() {
        return monitor;
    }

    /**
     * Dispatches a task to the host gateway executor and executes it inside the boundary monitor on the host thread.
     *
     * @param task the task to execute on host thread
     * @return completion stage completing when task finishes
     */
    public CompletionStage<Void> dispatchToHost(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            hostGatewayExecutor.execute(() -> {
                try {
                    synchronized (monitor) {
                        assertHostBoundary("dispatchedTask");
                        task.run();
                    }
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    /**
     * Marshals an asynchronous completion stage callback back to the host boundary.
     *
     * @param action completion callback to run on host thread
     * @param <T>    result type
     * @return completion stage running callback on host boundary
     */
    public <T> CompletableFuture<T> marshalToHost(Runnable action, CompletableFuture<T> targetFuture) {
        try {
            hostGatewayExecutor.execute(() -> {
                try {
                    synchronized (monitor) {
                        assertHostBoundary("marshaledCompletion");
                        action.run();
                    }
                } catch (Throwable t) {
                    targetFuture.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            targetFuture.completeExceptionally(t);
        }
        return targetFuture;
    }
}
