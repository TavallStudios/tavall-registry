package org.tavall.registry.module.operator;

import java.util.List;
import java.util.Map;

/**
 * Transport-neutral operator view of one installed module: its classification, observed and desired state,
 * generations, relationships and capability availability. Identifiers are plain strings so the same view
 * serializes unchanged for the CLI, a web dashboard or an MCP tool.
 *
 * @param moduleId              module identity
 * @param displayName           human-readable name
 * @param version               module version
 * @param primaryType           canonical primary module type
 * @param secondaryRoles        secondary responsibility roles
 * @param parentSystemId        parent SYSTEM, or null for a root module
 * @param state                 observed lifecycle state
 * @param desiredState          durable desired state
 * @param lifecycleGeneration   current lifecycle generation (fence for mutations)
 * @param configGeneration      runtime configuration generation (fence for mutations)
 * @param reloadSupported       whether the module may be reloaded independently
 * @param rollbackSupported     whether the module declares rollback eligibility
 * @param required              whether the runtime's health requires the module
 * @param requiredDependencies  modules that must be active first
 * @param dependents            registered modules that require this module
 * @param children              FEATURES of this SYSTEM
 * @param capabilities          provided capabilities and whether each is currently available
 * @param lastFailure           last failure as {@code code: message}, or null
 */
public record ModuleOperatorView(
        String moduleId,
        String displayName,
        String version,
        String primaryType,
        List<String> secondaryRoles,
        String parentSystemId,
        String state,
        String desiredState,
        long lifecycleGeneration,
        long configGeneration,
        boolean reloadSupported,
        boolean rollbackSupported,
        boolean required,
        List<String> requiredDependencies,
        List<String> dependents,
        List<String> children,
        Map<String, Boolean> capabilities,
        String lastFailure
) {
    public ModuleOperatorView {
        secondaryRoles = secondaryRoles == null ? List.of() : List.copyOf(secondaryRoles);
        requiredDependencies = requiredDependencies == null ? List.of() : List.copyOf(requiredDependencies);
        dependents = dependents == null ? List.of() : List.copyOf(dependents);
        children = children == null ? List.of() : List.copyOf(children);
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }
}
