package org.tavall.registry.module;

import java.util.Objects;
import java.util.Set;

/**
 * Declares capability servicing policy when a module generation enters quiescing or disabled states.
 * <p>
 * <b>Architectural Role:</b> Crucial safety policy ensuring that disabling new transaction entry
 * (e.g. Inbound payments or creative drafting) does not discard pending settlement obligations,
 * verified webhooks, dispute handling, refunds, or recovery procedures.
 * <p>
 * <b>Invariants:</b> New initiation is denied by default when disabled; only declared residual
 * capabilities are permitted through the lifecycle boundary.
 *
 * @param allowsNewInitiation  whether new work initiation is permitted; typically {@code false} when disabled
 * @param residualCapabilities the set of capabilities that remain obligated and serviceable while disabled
 */
public record ResidualObligationPolicy(
        boolean allowsNewInitiation,
        Set<CapabilityId> residualCapabilities
) {

    /**
     * Validates and creates an immutable {@link ResidualObligationPolicy}.
     *
     * @param allowsNewInitiation  whether new initiation is allowed
     * @param residualCapabilities set of residual capabilities; null treated as empty
     */
    public ResidualObligationPolicy {
        residualCapabilities = residualCapabilities == null ? Set.of() : Set.copyOf(residualCapabilities);
    }

    /**
     * Returns true if the designated capability is permitted under residual obligations.
     *
     * @param capabilityId the capability token to check; must not be null
     * @return true if permitted while disabled
     */
    public boolean allowsResidualCapability(CapabilityId capabilityId) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        return residualCapabilities.contains(capabilityId);
    }

    /**
     * Alias for {@link #allowsResidualCapability(CapabilityId)}.
     */
    public boolean isResidual(CapabilityId capabilityId) {
        return allowsResidualCapability(capabilityId);
    }

    /**
     * Returns an empty policy denying all new initiation and declaring no residual capabilities.
     *
     * @return a strict no-residual-obligation policy
     */
    public static ResidualObligationPolicy none() {
        return new ResidualObligationPolicy(false, Set.of());
    }

    /**
     * Constructs a policy authorizing specific residual capabilities while denying new initiation.
     *
     * @param capabilities capabilities that remain obligated
     * @return a residual obligation policy allowing the specified capabilities
     */
    public static ResidualObligationPolicy of(CapabilityId... capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        return new ResidualObligationPolicy(false, Set.of(capabilities));
    }

    /**
     * Constructs a policy authorizing a set of residual capabilities while denying new initiation.
     *
     * @param capabilities set of capabilities that remain obligated
     * @return a residual obligation policy allowing the specified capabilities
     */
    public static ResidualObligationPolicy of(Set<CapabilityId> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        return new ResidualObligationPolicy(false, capabilities);
    }
}
