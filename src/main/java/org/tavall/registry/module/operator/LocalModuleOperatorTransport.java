package org.tavall.registry.module.operator;

import org.tavall.registry.module.ModuleLifecycleOperation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Same-JVM transport for operator surfaces hosted inside the runtime itself (for example an MCP tool or a
 * web dashboard composed into the runtime). It calls the typed service directly; the host has already
 * authenticated {@code actor}.
 */
public final class LocalModuleOperatorTransport implements IModuleOperatorTransport {

    private final ModuleRuntimeTarget target;
    private final IModuleOperatorService service;
    private final String actor;

    public LocalModuleOperatorTransport(ModuleRuntimeTarget target, IModuleOperatorService service, String actor) {
        this.target = Objects.requireNonNull(target, "target");
        this.service = Objects.requireNonNull(service, "service");
        this.actor = Objects.requireNonNull(actor, "actor");
    }

    @Override
    public ModuleRuntimeTarget target() {
        return target;
    }

    @Override
    public List<ModuleOperatorView> list() {
        return service.list();
    }

    @Override
    public Optional<ModuleOperatorView> inspect(String moduleId) {
        return service.inspect(moduleId);
    }

    @Override
    public ModuleOperationImpact impact(String moduleId, ModuleLifecycleOperation operation) {
        return service.impact(moduleId, operation);
    }

    @Override
    public ModuleOperationOutcome execute(ModuleOperationRequest request) {
        return service.execute(actor, request);
    }
}
