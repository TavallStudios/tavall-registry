package org.tavall.registry.module;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable token identifying a functional capability provided or consumed by a module.
 * <p>
 * <b>Architectural Role:</b> Expresses module contracts, residual obligation policies (such as
 * payment webhook processing or settlement during disable), and dependency resolution tokens
 * without tightly coupling consumers to concrete class hierarchies.
 * <p>
 * <b>Invariants:</b> Identifier string is non-blank and normalized to lowercase.
 *
 * @param value the normalized capability identifier string
 */
public record CapabilityId(String value) implements Comparable<CapabilityId> {

    /**
     * Validates and normalizes the capability identifier token.
     *
     * @param value raw capability identifier; must not be blank
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public CapabilityId {
        Objects.requireNonNull(value, "CapabilityId value must not be null");
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("CapabilityId value must not be blank");
        }
        value = trimmed;
    }

    /**
     * Factory method creating a normalized {@link CapabilityId}.
     *
     * @param value raw capability identifier
     * @return a new normalized {@link CapabilityId} instance
     */
    public static CapabilityId of(String value) {
        return new CapabilityId(value);
    }

    @Override
    public int compareTo(CapabilityId other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
