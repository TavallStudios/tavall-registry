package org.tavall.registry.module;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Mutable state entry tracking one installed module inside {@link ModuleLifecycleRegistry}.
 * <p>
 * <b>Architectural Role:</b> Internal registry record encapsulating module descriptor, active participant,
 * observed lifecycle state, desired state, generation fences, health probe, and generation context.
 * <p>
 * <b>Lifecycle &amp; Concurrency:</b> Access and mutation are serialized on the host lifecycle boundary thread
 * owned by the registry.
 */
public final class ModuleEntry {

    private ModuleDescriptor descriptor;
    private Supplier<IModuleLifecycleParticipant> participantSupplier;
    private IModuleLifecycleParticipant activeParticipant;
    private ModuleLifecycleState state = ModuleLifecycleState.DISCOVERED;
    private ModuleDesiredState desiredState = ModuleDesiredState.ENABLED;
    private long lifecycleGeneration = 0L;
    private long configGeneration = 1L;
    private Instant loadedAt;
    private Instant lastTransitionAt = Instant.now();
    private ModuleFailure lastFailure;
    private ScopedModuleContext context;
    private IModuleHealthCheck healthCheck;

    /**
     * Constructs a new {@link ModuleEntry} for the given descriptor and participant supplier.
     *
     * @param descriptor          the module descriptor; must not be null
     * @param participantSupplier supplier creating new participant instances per generation; null if none
     * @param healthCheck         health assessment probe; null if none
     */
    public ModuleEntry(
            ModuleDescriptor descriptor,
            Supplier<IModuleLifecycleParticipant> participantSupplier,
            IModuleHealthCheck healthCheck
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.participantSupplier = participantSupplier;
        this.healthCheck = healthCheck;
    }

    public synchronized ModuleDescriptor descriptor() {
        return descriptor;
    }

    public synchronized void setDescriptor(ModuleDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    public synchronized Supplier<IModuleLifecycleParticipant> participantSupplier() {
        return participantSupplier;
    }

    public synchronized void setParticipantSupplier(Supplier<IModuleLifecycleParticipant> participantSupplier) {
        this.participantSupplier = participantSupplier;
    }

    public synchronized IModuleLifecycleParticipant activeParticipant() {
        return activeParticipant;
    }

    public synchronized void setActiveParticipant(IModuleLifecycleParticipant activeParticipant) {
        this.activeParticipant = activeParticipant;
    }

    public synchronized ModuleLifecycleState state() {
        return state;
    }

    public synchronized void transition(ModuleLifecycleState nextState) {
        this.state = Objects.requireNonNull(nextState, "nextState");
        this.lastTransitionAt = Instant.now();
    }

    public synchronized ModuleDesiredState desiredState() {
        return desiredState;
    }

    public synchronized void setDesiredState(ModuleDesiredState desiredState) {
        this.desiredState = Objects.requireNonNull(desiredState, "desiredState");
    }

    public synchronized long lifecycleGeneration() {
        return lifecycleGeneration;
    }

    public synchronized void setLifecycleGeneration(long lifecycleGeneration) {
        this.lifecycleGeneration = lifecycleGeneration;
    }

    public synchronized long configGeneration() {
        return configGeneration;
    }

    public synchronized void setConfigGeneration(long configGeneration) {
        this.configGeneration = configGeneration;
    }

    public synchronized Instant loadedAt() {
        return loadedAt;
    }

    public synchronized void setLoadedAt(Instant loadedAt) {
        this.loadedAt = loadedAt;
    }

    public synchronized Instant lastTransitionAt() {
        return lastTransitionAt;
    }

    public synchronized ModuleFailure lastFailure() {
        return lastFailure;
    }

    public synchronized void setLastFailure(ModuleFailure lastFailure) {
        this.lastFailure = lastFailure;
    }

    public synchronized ScopedModuleContext context() {
        return context;
    }

    public synchronized void setContext(ScopedModuleContext context) {
        this.context = context;
    }

    public synchronized IModuleHealthCheck healthCheck() {
        return healthCheck;
    }

    public synchronized void setHealthCheck(IModuleHealthCheck healthCheck) {
        this.healthCheck = healthCheck;
    }

    /**
     * Clears all generation-owned participant and context references.
     */
    public synchronized void clearGenerationReferences() {
        this.activeParticipant = null;
        this.context = null;
        this.loadedAt = null;
    }

    /**
     * Creates a new participant instance from the registered supplier.
     *
     * @return new participant instance, or null if stateless
     */
    public synchronized IModuleLifecycleParticipant createParticipantInstance() {
        return participantSupplier == null ? null : participantSupplier.get();
    }

    /**
     * Evaluates the operational health of this module entry.
     *
     * @return current health report
     */
    public synchronized ModuleHealth assessHealth() {
        if (state == ModuleLifecycleState.FAILED) {
            String summary = lastFailure != null
                    ? "Module in FAILED state: " + lastFailure.message()
                    : "Module in FAILED state";
            return ModuleHealth.unhealthy(summary, java.util.Map.of());
        }
        if (healthCheck != null) {
            try {
                return healthCheck.checkHealth();
            } catch (Throwable t) {
                return ModuleHealth.unhealthy("Health probe failed: " + t.getMessage(), java.util.Map.of());
            }
        }
        if (lastFailure != null) {
            return ModuleHealth.degraded("Module failure recorded: " + lastFailure.message(), java.util.Map.of());
        }
        return state.isActive()
                ? ModuleHealth.healthy("Module operational")
                : ModuleHealth.unknown("Module state: " + state);
    }
}
