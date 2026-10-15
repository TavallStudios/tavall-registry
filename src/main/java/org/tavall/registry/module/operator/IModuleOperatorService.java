package org.tavall.registry.module.operator;

import org.tavall.registry.module.ModuleLifecycleOperation;

import java.util.List;
import java.util.Optional;

/**
 * Typed operator API over a runtime's module lifecycle registry. One implementation serves every operator
 * surface (the {@code tavall module} CLI through a runtime transport, a web dashboard, an MCP tool) so
 * mutation rules are never duplicated per surface.
 */
public interface IModuleOperatorService {

    /**
     * Lists the modules this operator surface may see.
     *
     * @return module views in registration order of their SYSTEM first
     */
    List<ModuleOperatorView> list();

    /**
     * Inspects one module.
     *
     * @param moduleId module identity
     * @return the view, or empty when the module is unknown or outside this surface's scope
     */
    Optional<ModuleOperatorView> inspect(String moduleId);

    /**
     * Previews an operation and returns the confirmation token a destructive operation needs.
     *
     * @param moduleId  module identity
     * @param operation operation to preview
     * @return impact preview
     * @throws IllegalArgumentException when the module is unknown or outside this surface's scope
     */
    ModuleOperationImpact impact(String moduleId, ModuleLifecycleOperation operation);

    /**
     * Executes an operator request as the authenticated actor. The registry still authorizes the actor and
     * enforces generation fences and dependency rules.
     *
     * @param actor   authenticated operator identity
     * @param request fenced operator request
     * @return outcome, including refusals
     */
    ModuleOperationOutcome execute(String actor, ModuleOperationRequest request);
}
