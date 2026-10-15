package org.tavall.registry.module.profile;

/**
 * Checks that an immutable runtime artifact can satisfy a deployment's module profile before release.
 */
public interface IModuleProfileCompatibilityValidator {

    /**
     * @param catalog modules the artifact contains
     * @param profile desired module composition of the deployment
     * @return the verdict; never throws for an incompatible profile
     */
    ModuleProfileCompatibility validate(ModuleCatalog catalog, ModuleProfileDefinition profile);
}
