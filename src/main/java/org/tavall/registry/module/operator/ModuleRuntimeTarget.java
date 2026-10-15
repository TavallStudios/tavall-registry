package org.tavall.registry.module.operator;

import java.util.Map;
import java.util.Objects;

/**
 * A named runtime whose modules an operator controls, and how to reach it. Settings carry transport
 * configuration such as an endpoint or the name of an environment variable holding a credential; they never
 * carry secret values themselves.
 *
 * @param name      operator-facing target name, for example {@code content-development}
 * @param transport transport type implemented by the owning runtime, for example {@code content-studio-http}
 * @param settings  transport settings
 */
public record ModuleRuntimeTarget(String name, String transport, Map<String, String> settings) {
    public ModuleRuntimeTarget {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(transport, "transport");
        if (name.isBlank() || transport.isBlank()) {
            throw new IllegalArgumentException("Runtime target name and transport are required");
        }
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    /**
     * @param key setting name
     * @return the setting value
     * @throws IllegalArgumentException when the setting is missing
     */
    public String requireSetting(String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Runtime target " + name + " is missing setting " + key);
        }
        return value;
    }
}
