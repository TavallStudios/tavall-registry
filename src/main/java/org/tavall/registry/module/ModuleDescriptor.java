package org.tavall.registry.module;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable metadata descriptor for a module.
 * <p>
 * <b>Architectural Role:</b> Static specification of module identity, primary responsibility,
 * secondary roles, parent-system fan-in linkage, dependency boundaries, provided capabilities,
 * and reload/rollback eligibility.
 * <p>
 * <b>Invariants:</b> Required and optional dependency sets must be strictly disjoint.
 * A module cannot declare itself as its own parent system.
 * {@link ModulePrimaryType#FEATURE} modules MUST declare a {@code parentSystemId}.
 * {@link ModulePrimaryType#SYSTEM} modules MUST NOT declare a {@code parentSystemId}.
 * {@code reloadSupported} and {@code rollbackSupported} default to {@code false} to prevent
 * unverified hot-reloads and rollbacks from claiming safety.
 *
 * @param id                   canonical typed module identifier
 * @param displayName          human-readable descriptive name
 * @param version              semantic version string
 * @param primaryType          canonical primary module classification
 * @param secondaryRoles       immutable set of secondary responsibility roles
 * @param runtimeTarget        designated target host runtime
 * @param parentSystemId       optional parent SYSTEM identifier (mandatory for FEATURE)
 * @param requiredDependencies set of mandatory dependency module IDs
 * @param optionalDependencies set of optional dependency module IDs
 * @param providedCapabilities set of capability tokens exposed by this module
 * @param requiredCapabilities set of capability tokens required from other modules
 * @param reloadSupported      whether live in-process reload into a new generation is supported
 * @param rollbackSupported    whether live configuration/generation rollback is supported
 * @param required             whether this module is required (vs optional) for target health
 */
public record ModuleDescriptor(
        ModuleId id,
        String displayName,
        String version,
        ModulePrimaryType primaryType,
        Set<ModuleSecondaryRole> secondaryRoles,
        RuntimeTarget runtimeTarget,
        Optional<ModuleId> parentSystemId,
        Set<ModuleId> requiredDependencies,
        Set<ModuleId> optionalDependencies,
        Set<CapabilityId> providedCapabilities,
        Set<CapabilityId> requiredCapabilities,
        boolean reloadSupported,
        boolean rollbackSupported,
        boolean required
) {

    /**
     * Validates and constructs an immutable {@link ModuleDescriptor}.
     */
    public ModuleDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(primaryType, "primaryType must not be null");
        Objects.requireNonNull(runtimeTarget, "runtimeTarget must not be null");
        Objects.requireNonNull(parentSystemId, "parentSystemId must not be null");

        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }

        secondaryRoles = secondaryRoles == null ? Set.of() : Set.copyOf(secondaryRoles);
        requiredDependencies = requiredDependencies == null ? Set.of() : Set.copyOf(requiredDependencies);
        optionalDependencies = optionalDependencies == null ? Set.of() : Set.copyOf(optionalDependencies);
        providedCapabilities = providedCapabilities == null ? Set.of() : Set.copyOf(providedCapabilities);
        requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);

        if (!Collections.disjoint(requiredDependencies, optionalDependencies)) {
            Set<ModuleId> overlap = new HashSet<>(requiredDependencies);
            overlap.retainAll(optionalDependencies);
            throw new IllegalArgumentException(
                    "Required and optional dependencies must be disjoint for module " + id + ": overlap=" + overlap
            );
        }

        if (parentSystemId.isPresent() && parentSystemId.get().equals(id)) {
            throw new IllegalArgumentException("Module cannot be its own parent system: " + id);
        }

        if (primaryType == ModulePrimaryType.FEATURE && parentSystemId.isEmpty()) {
            throw new IllegalArgumentException("FEATURE module " + id + " must declare a parentSystemId");
        }

        if (primaryType == ModulePrimaryType.SYSTEM && parentSystemId.isPresent()) {
            throw new IllegalArgumentException("SYSTEM module " + id + " cannot declare a parentSystemId");
        }
    }

    /**
     * Returns a new fluent builder for constructing a {@link ModuleDescriptor}.
     *
     * @param id            canonical module identifier; must not be null
     * @param primaryType   primary module type; must not be null
     * @param runtimeTarget runtime target; must not be null
     * @return a new {@link Builder} instance
     */
    public static Builder builder(ModuleId id, ModulePrimaryType primaryType, RuntimeTarget runtimeTarget) {
        return new Builder(id, primaryType, runtimeTarget);
    }

    /**
     * Fluent builder for {@link ModuleDescriptor}.
     */
    public static final class Builder {
        private final ModuleId id;
        private final ModulePrimaryType primaryType;
        private final RuntimeTarget runtimeTarget;
        private String displayName;
        private String version = "1.0.0";
        private final Set<ModuleSecondaryRole> secondaryRoles = new LinkedHashSet<>();
        private ModuleId parentSystemId;
        private final Set<ModuleId> requiredDependencies = new LinkedHashSet<>();
        private final Set<ModuleId> optionalDependencies = new LinkedHashSet<>();
        private final Set<CapabilityId> providedCapabilities = new LinkedHashSet<>();
        private final Set<CapabilityId> requiredCapabilities = new LinkedHashSet<>();
        private boolean reloadSupported = false; // Default unsupported; require explicit proven eligibility
        private boolean rollbackSupported = false; // Default unsupported; require explicit proven eligibility
        private boolean required = true;

        private Builder(ModuleId id, ModulePrimaryType primaryType, RuntimeTarget runtimeTarget) {
            this.id = Objects.requireNonNull(id, "id");
            this.primaryType = Objects.requireNonNull(primaryType, "primaryType");
            this.runtimeTarget = Objects.requireNonNull(runtimeTarget, "runtimeTarget");
            this.displayName = id.value();
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder addSecondaryRole(ModuleSecondaryRole role) {
            this.secondaryRoles.add(Objects.requireNonNull(role, "role"));
            return this;
        }

        public Builder parentSystemId(ModuleId parentSystemId) {
            this.parentSystemId = parentSystemId;
            return this;
        }

        public Builder addRequiredDependency(ModuleId dependency) {
            this.requiredDependencies.add(Objects.requireNonNull(dependency, "dependency"));
            return this;
        }

        public Builder addOptionalDependency(ModuleId dependency) {
            this.optionalDependencies.add(Objects.requireNonNull(dependency, "dependency"));
            return this;
        }

        public Builder addProvidedCapability(CapabilityId capability) {
            this.providedCapabilities.add(Objects.requireNonNull(capability, "capability"));
            return this;
        }

        public Builder addRequiredCapability(CapabilityId capability) {
            this.requiredCapabilities.add(Objects.requireNonNull(capability, "capability"));
            return this;
        }

        public Builder reloadSupported(boolean reloadSupported) {
            this.reloadSupported = reloadSupported;
            return this;
        }

        public Builder rollbackSupported(boolean rollbackSupported) {
            this.rollbackSupported = rollbackSupported;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public ModuleDescriptor build() {
            return new ModuleDescriptor(
                    id,
                    displayName,
                    version,
                    primaryType,
                    secondaryRoles,
                    runtimeTarget,
                    Optional.ofNullable(parentSystemId),
                    requiredDependencies,
                    optionalDependencies,
                    providedCapabilities,
                    requiredCapabilities,
                    reloadSupported,
                    rollbackSupported,
                    required
            );
        }
    }
}
