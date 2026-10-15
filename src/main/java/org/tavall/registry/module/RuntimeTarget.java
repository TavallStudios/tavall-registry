package org.tavall.registry.module;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable identifier representing a designated execution host or deployment environment target.
 * <p>
 * <b>Architectural Role:</b> Coordinates per-deployment module profiles, host lifecycle boundaries,
 * and durable configuration snapshots across distinct runtime targets (e.g. {@code web-commerce},
 * {@code content-autopost-node}, {@code mc-velocity}).
 * <p>
 * <b>Invariants:</b> Identifier string is non-blank and normalized to lowercase.
 *
 * @param identifier the non-blank normalized runtime target name
 */
public record RuntimeTarget(String identifier) implements Comparable<RuntimeTarget> {

    /**
     * Validates and normalizes the runtime target identifier.
     *
     * @param identifier raw runtime target name; must not be blank
     * @throws NullPointerException     if {@code identifier} is null
     * @throws IllegalArgumentException if {@code identifier} is blank
     */
    public RuntimeTarget {
        Objects.requireNonNull(identifier, "RuntimeTarget identifier must not be null");
        String trimmed = identifier.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("RuntimeTarget identifier must not be blank");
        }
        identifier = trimmed;
    }

    /**
     * Default application runtime target.
     */
    public static final RuntimeTarget APPLICATION = of("application");

    /**
     * Factory method creating a normalized {@link RuntimeTarget}.
     *
     * @param identifier raw target name
     * @return a new normalized {@link RuntimeTarget} instance
     */
    public static RuntimeTarget of(String identifier) {
        return new RuntimeTarget(identifier);
    }

    @Override
    public int compareTo(RuntimeTarget other) {
        return this.identifier.compareTo(other.identifier);
    }

    @Override
    public String toString() {
        return identifier;
    }
}
