package org.tavall.registry.module;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable canonical identifier for a registered module.
 * <p>
 * <b>Architectural Role:</b> Type-safe domain key for module indexing, dependency declarations,
 * and lifecycle requests within the Tavall module architecture. Replaces untyped string identifiers.
 * <p>
 * <b>Invariants:</b> Module identifiers must be non-blank, lowercase, and match the kebab-case pattern
 * {@code [a-z0-9]+(?:-[a-z0-9]+)*}. Leading and trailing whitespace is stripped upon construction.
 *
 * @param value the validated canonical kebab-case module identifier string
 */
public record ModuleId(String value) implements Comparable<ModuleId> {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    /**
     * Validates and normalizes the module identifier.
     *
     * @param value raw module identifier string; must match kebab-case pattern
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is blank or does not conform to kebab-case
     */
    public ModuleId {
        Objects.requireNonNull(value, "ModuleId value must not be null");
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("ModuleId value must not be empty or blank");
        }
        if (!PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Invalid ModuleId format: '" + value + "'. Must match pattern: " + PATTERN.pattern()
            );
        }
        value = trimmed;
    }

    /**
     * Factory method creating a validated {@link ModuleId}.
     *
     * @param value raw module identifier string
     * @return a new validated {@link ModuleId} instance
     */
    public static ModuleId of(String value) {
        return new ModuleId(value);
    }

    @Override
    public int compareTo(ModuleId other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
