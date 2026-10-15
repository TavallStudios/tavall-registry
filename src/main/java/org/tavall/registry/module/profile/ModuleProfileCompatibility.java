package org.tavall.registry.module.profile;

import java.util.List;

/**
 * Release-validation verdict for one artifact and one deployment module profile.
 *
 * @param compatible whether the artifact can satisfy the profile
 * @param profileId  validated profile
 * @param loadOrder  dependency-first activation order of the enabled modules when compatible
 * @param problem    why the profile is incompatible, or null
 */
public record ModuleProfileCompatibility(boolean compatible, String profileId, List<String> loadOrder, String problem) {
    public ModuleProfileCompatibility {
        loadOrder = loadOrder == null ? List.of() : List.copyOf(loadOrder);
    }
}
