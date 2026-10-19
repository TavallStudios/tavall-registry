package org.tavall.registry.module.operator;

import org.tavall.registry.module.ModuleLifecycleOperation;

import java.util.List;
import java.util.Optional;

/**
 * Client side of {@link IModuleOperatorService} for one named runtime target. The runtime that owns the
 * modules owns its transport and authentication; the operator identity is established by the transport,
 * never supplied by the caller.
 */
public interface IModuleOperatorTransport extends AutoCloseable {

    /** @return runtime target this transport is bound to */
    ModuleRuntimeTarget target();

    /** @return module views visible to the authenticated operator */
    List<ModuleOperatorView> list();

    /**
     * @param moduleId module identity
     * @return the module view, or empty when unknown
     */
    Optional<ModuleOperatorView> inspect(String moduleId);

    /**
     * @param moduleId  module identity
     * @param operation operation to preview
     * @return impact preview with any confirmation token
     */
    ModuleOperationImpact impact(String moduleId, ModuleLifecycleOperation operation);

    /**
     * @param request fenced operator request
     * @return outcome, including refusals
     */
    ModuleOperationOutcome execute(ModuleOperationRequest request);

    @Override
    default void close() {
    }
}
