package org.tavall.registry.module.profile;

import org.tavall.util.CustomEnum;
import org.tavall.dependency.module.DeploymentModuleProfile;
import org.tavall.dependency.module.EffectiveDeploymentModuleProfile;
import org.tavall.dependency.module.ModuleProfileResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link IModuleProfileCompatibilityValidator} on the canonical Tavall DI {@link ModuleProfileResolver}.
 * <p>
 * A FEATURE requires its parent SYSTEM in addition to its declared dependencies, so enabling a FEATURE of a
 * disabled or missing SYSTEM is incompatible. Unknown modules, enabled modules that need an explicitly
 * disabled dependency, conflicts and unsupported schema versions are incompatible as well; nothing is
 * silently enabled to make a profile fit.
 */
public final class ModuleProfileCompatibilityValidator implements IModuleProfileCompatibilityValidator {

    @Override
    public ModuleProfileCompatibility validate(ModuleCatalog catalog, ModuleProfileDefinition profile) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(profile, "profile");
        try {
            Set<CatalogModule> available = catalog.modules().stream()
                    .map(entry -> CatalogModule.of(entry.moduleId()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            DeploymentModuleProfile<CatalogModule, ProfileKey, EnvironmentKey> deploymentProfile = new DeploymentModuleProfile<>(
                    profile.schemaVersion(), ProfileKey.of(profile.profileId()), EnvironmentKey.of(profile.environment()),
                    profile.configurationGeneration(), modules(profile.enabledModules()), modules(profile.disabledModules()));
            EffectiveDeploymentModuleProfile<CatalogModule, ProfileKey, EnvironmentKey> effective =
                    new ModuleProfileResolver<CatalogModule, ProfileKey>().resolve(deploymentProfile, available,
                            module -> requirements(catalog, module));
            return new ModuleProfileCompatibility(true, profile.profileId(),
                    effective.loadOrder().stream().map(CatalogModule::name).toList(), null);
        } catch (IllegalArgumentException | IllegalStateException incompatible) {
            return new ModuleProfileCompatibility(false, profile.profileId(), List.of(), incompatible.getMessage());
        }
    }

    private static Collection<CatalogModule> requirements(ModuleCatalog catalog, CatalogModule module) {
        List<CatalogModule> required = new ArrayList<>();
        catalog.find(module.name()).ifPresent(entry -> {
            if (entry.parentSystemId() != null) required.add(CatalogModule.of(entry.parentSystemId()));
            entry.requiredDependencies().forEach(dependency -> required.add(CatalogModule.of(dependency)));
        });
        return required;
    }

    private static Set<CatalogModule> modules(Set<String> ids) {
        return ids.stream().map(CatalogModule::of).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Module identity as a canonical extensible enum value for the DI profile resolver. */
    static final class CatalogModule extends CustomEnum<CatalogModule> {
        private CatalogModule(String name) {
            super(name);
        }

        static CatalogModule of(String name) {
            return register(CatalogModule.class, name, CatalogModule::new);
        }
    }

    /** Profile identity as a canonical extensible enum value. */
    static final class ProfileKey extends CustomEnum<ProfileKey> {
        private ProfileKey(String name) {
            super(name);
        }

        static ProfileKey of(String name) {
            return register(ProfileKey.class, name, ProfileKey::new);
        }
    }

    /** Deployment environment as a canonical extensible enum value. */
    static final class EnvironmentKey extends CustomEnum<EnvironmentKey> {
        private EnvironmentKey(String name) {
            super(name);
        }

        static EnvironmentKey of(String name) {
            return register(EnvironmentKey.class, name, EnvironmentKey::new);
        }
    }
}
