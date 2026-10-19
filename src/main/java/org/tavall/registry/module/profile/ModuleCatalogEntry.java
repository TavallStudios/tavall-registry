package org.tavall.registry.module.profile;

import org.tavall.registry.module.ModuleDescriptor;
import org.tavall.registry.module.ModuleId;
import org.tavall.registry.module.ModulePrimaryType;

import java.util.List;
import java.util.Objects;

/**
 * One module an immutable runtime artifact contains, as release validation needs it: identity, primary type,
 * owning SYSTEM and required dependencies.
 *
 * @param moduleId             module identity
 * @param primaryType          canonical primary module type
 * @param parentSystemId       owning SYSTEM, or null for a root module
 * @param requiredDependencies modules that must be active first
 */
public record ModuleCatalogEntry(String moduleId, ModulePrimaryType primaryType, String parentSystemId,
                                 List<String> requiredDependencies) {
    public ModuleCatalogEntry {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(primaryType, "primaryType");
        if (moduleId.isBlank()) throw new IllegalArgumentException("moduleId must not be blank");
        if (primaryType == ModulePrimaryType.FEATURE && (parentSystemId == null || parentSystemId.isBlank())) {
            throw new IllegalArgumentException("FEATURE " + moduleId + " must name its parent SYSTEM");
        }
        requiredDependencies = requiredDependencies == null ? List.of() : List.copyOf(requiredDependencies);
    }

    /**
     * @param descriptor registered module descriptor
     * @return the catalog entry describing it
     */
    public static ModuleCatalogEntry from(ModuleDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return new ModuleCatalogEntry(descriptor.id().value(), descriptor.primaryType(),
                descriptor.parentSystemId().map(ModuleId::value).orElse(null),
                descriptor.requiredDependencies().stream().map(ModuleId::value).sorted().toList());
    }
}
