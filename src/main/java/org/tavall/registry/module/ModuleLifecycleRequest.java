package org.tavall.registry.module;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable command request specifying an authorized module lifecycle mutation.
 * <p>
 * <b>Architectural Role:</b> Typed request boundary for operator, CD pipeline, and programmatic
 * module lifecycle operations. Encapsulates runtime epoch fencing, idempotency, and generation fences.
 * <p>
 * <b>Invariants:</b>
 * <ul>
 *   <li>Blank or absent actor identity is strictly rejected.</li>
 *   <li>Public mutations require matching {@code runtimeEpochId} to prevent stale pre-restart commands from executing on a new controller boot.</li>
 *   <li>Public mutations require explicit positive {@code expectedConfigGeneration} and non-negative {@code expectedLifecycleGeneration} fences.</li>
 *   <li>Internal bootstrap requests are unforgeable and require package-private {@link BootstrapToken}.</li>
 *   <li>The {@code force} flag never bypasses authorization, real required dependencies, or residual payment obligations.</li>
 *   <li>The {@code correlationId} deterministically binds the complete caller, runtime epoch, target, operation, and generation payload for replay deduplication.</li>
 * </ul>
 */
public final class ModuleLifecycleRequest {

    private final UUID runtimeEpochId;
    private final String actor;
    private final ModuleId moduleId;
    private final ModuleLifecycleOperation operation;
    private final long expectedConfigGeneration;
    private final long expectedLifecycleGeneration;
    private final boolean force;
    private final String correlationId;
    private final boolean isBootstrap;

    ModuleLifecycleRequest(
            UUID runtimeEpochId,
            String actor,
            ModuleId moduleId,
            ModuleLifecycleOperation operation,
            long expectedConfigGeneration,
            long expectedLifecycleGeneration,
            boolean force,
            String correlationId,
            boolean isBootstrap
    ) {
        this.runtimeEpochId = Objects.requireNonNull(runtimeEpochId, "runtimeEpochId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        String trimmedActor = actor.trim();
        if (trimmedActor.isEmpty()) {
            throw new IllegalArgumentException("actor identity must not be blank or anonymous");
        }
        this.actor = trimmedActor;
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
        this.operation = Objects.requireNonNull(operation, "operation must not be null");

        if (expectedConfigGeneration < 0L) {
            throw new IllegalArgumentException("expectedConfigGeneration must not be negative: " + expectedConfigGeneration);
        }
        if (expectedLifecycleGeneration < 0L) {
            throw new IllegalArgumentException("expectedLifecycleGeneration must not be negative: " + expectedLifecycleGeneration);
        }
        this.expectedConfigGeneration = expectedConfigGeneration;
        this.expectedLifecycleGeneration = expectedLifecycleGeneration;
        this.force = force;
        this.isBootstrap = isBootstrap;

        if (correlationId == null || correlationId.isBlank()) {
            this.correlationId = bindCorrelationToken(runtimeEpochId, trimmedActor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, force, null);
        } else {
            this.correlationId = correlationId;
        }
    }

    public UUID runtimeEpochId() {
        return runtimeEpochId;
    }

    public String actor() {
        return actor;
    }

    public ModuleId moduleId() {
        return moduleId;
    }

    public ModuleLifecycleOperation operation() {
        return operation;
    }

    public long expectedConfigGeneration() {
        return expectedConfigGeneration;
    }

    public long expectedLifecycleGeneration() {
        return expectedLifecycleGeneration;
    }

    public boolean force() {
        return force;
    }

    public String correlationId() {
        return correlationId;
    }

    public boolean isBootstrap() {
        return isBootstrap;
    }

    /**
     * Public factory creating a fenced mutation request with explicit configuration and lifecycle generation fences.
     */
    public static ModuleLifecycleRequest request(
            UUID runtimeEpochId,
            String actor,
            ModuleId moduleId,
            ModuleLifecycleOperation operation,
            long expectedConfigGeneration,
            long expectedLifecycleGeneration
    ) {
        if (expectedConfigGeneration <= 0L) {
            throw new IllegalArgumentException("Public mutation requests must provide a positive expectedConfigGeneration fence");
        }
        String correlation = bindCorrelationToken(runtimeEpochId, actor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, false, null);
        return new ModuleLifecycleRequest(runtimeEpochId, actor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, false, correlation, false);
    }

    /**
     * Public factory creating a fenced mutation request with an explicit client correlation token.
     */
    public static ModuleLifecycleRequest request(
            UUID runtimeEpochId,
            String actor,
            ModuleId moduleId,
            ModuleLifecycleOperation operation,
            long expectedConfigGeneration,
            long expectedLifecycleGeneration,
            String clientCorrelationToken
    ) {
        if (expectedConfigGeneration <= 0L) {
            throw new IllegalArgumentException("Public mutation requests must provide a positive expectedConfigGeneration fence");
        }
        String correlation = bindCorrelationToken(runtimeEpochId, actor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, false, clientCorrelationToken);
        return new ModuleLifecycleRequest(runtimeEpochId, actor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, false, correlation, false);
    }

    /**
     * Public factory creating a forced mutation request with explicit generation fences.
     */
    public static ModuleLifecycleRequest forced(
            UUID runtimeEpochId,
            String actor,
            ModuleId moduleId,
            ModuleLifecycleOperation operation,
            long expectedConfigGeneration,
            long expectedLifecycleGeneration,
            boolean force
    ) {
        if (expectedConfigGeneration <= 0L) {
            throw new IllegalArgumentException("Public forced mutation requests must provide a positive expectedConfigGeneration fence");
        }
        String correlation = bindCorrelationToken(runtimeEpochId, actor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, force, null);
        return new ModuleLifecycleRequest(runtimeEpochId, actor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, force, correlation, false);
    }

    /**
     * Public factory creating a forced mutation request with explicit generation fences and client correlation token.
     */
    public static ModuleLifecycleRequest forced(
            UUID runtimeEpochId,
            String actor,
            ModuleId moduleId,
            ModuleLifecycleOperation operation,
            long expectedConfigGeneration,
            long expectedLifecycleGeneration,
            boolean force,
            String clientCorrelationToken
    ) {
        if (expectedConfigGeneration <= 0L) {
            throw new IllegalArgumentException("Public forced mutation requests must provide a positive expectedConfigGeneration fence");
        }
        String correlation = bindCorrelationToken(runtimeEpochId, actor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, force, clientCorrelationToken);
        return new ModuleLifecycleRequest(runtimeEpochId, actor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, force, correlation, false);
    }

    /**
     * Internal framework bootstrap factory protected by unforgeable {@link BootstrapToken}.
     */
    static ModuleLifecycleRequest internalBootstrap(
            BootstrapToken token,
            UUID runtimeEpochId,
            String actor,
            ModuleId moduleId,
            ModuleLifecycleOperation operation
    ) {
        Objects.requireNonNull(token, "token must not be null");
        if (!token.matches(runtimeEpochId)) {
            throw new SecurityException("BootstrapToken scope does not match runtimeEpochId");
        }
        String correlation = bindCorrelationToken(runtimeEpochId, actor, moduleId, operation, 0L, 0L, false, "internal-boot");
        return new ModuleLifecycleRequest(runtimeEpochId, actor, moduleId, operation, 0L, 0L, false, correlation, true);
    }

    /**
     * Internal framework cascade factory protected by unforgeable {@link BootstrapToken}.
     */
    static ModuleLifecycleRequest internalCascade(
            BootstrapToken token,
            UUID runtimeEpochId,
            String actor,
            ModuleId moduleId,
            ModuleLifecycleOperation operation,
            long configGen,
            long lifecycleGen
    ) {
        Objects.requireNonNull(token, "token must not be null");
        if (!token.matches(runtimeEpochId)) {
            throw new SecurityException("BootstrapToken scope does not match runtimeEpochId");
        }
        String correlation = bindCorrelationToken(runtimeEpochId, actor, moduleId, operation, configGen, lifecycleGen, false, "cascade-" + UUID.randomUUID());
        return new ModuleLifecycleRequest(runtimeEpochId, actor, moduleId, operation, configGen, lifecycleGen, false, correlation, true);
    }

    private static String bindCorrelationToken(
            UUID runtimeEpochId,
            String actor,
            ModuleId moduleId,
            ModuleLifecycleOperation operation,
            long expectedConfigGen,
            long expectedLifecycleGen,
            boolean force,
            String clientToken
    ) {
        String base = runtimeEpochId + ":" + actor.trim() + ":" + moduleId.value() + ":" + operation.name()
                + ":cfg" + expectedConfigGen + ":gen" + expectedLifecycleGen + ":force" + force;
        return clientToken != null && !clientToken.isBlank() ? base + ":" + clientToken.trim() : base;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModuleLifecycleRequest that = (ModuleLifecycleRequest) o;
        return expectedConfigGeneration == that.expectedConfigGeneration
                && expectedLifecycleGeneration == that.expectedLifecycleGeneration
                && force == that.force
                && isBootstrap == that.isBootstrap
                && Objects.equals(runtimeEpochId, that.runtimeEpochId)
                && Objects.equals(actor, that.actor)
                && Objects.equals(moduleId, that.moduleId)
                && operation == that.operation
                && Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runtimeEpochId, actor, moduleId, operation, expectedConfigGeneration, expectedLifecycleGeneration, force, correlationId, isBootstrap);
    }

    @Override
    public String toString() {
        return "ModuleLifecycleRequest[" +
                "runtimeEpochId=" + runtimeEpochId +
                ", actor='" + actor + '\'' +
                ", moduleId=" + moduleId +
                ", operation=" + operation +
                ", expectedConfigGeneration=" + expectedConfigGeneration +
                ", expectedLifecycleGeneration=" + expectedLifecycleGeneration +
                ", force=" + force +
                ", correlationId='" + correlationId + '\'' +
                ", isBootstrap=" + isBootstrap +
                ']';
    }
}
