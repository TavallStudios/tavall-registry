package org.tavall.registry.module.profile;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Typed, versioned desired module composition of one deployment environment. Deployment configuration keeps
 * it next to the service's CD template in {@value #FILE_NAME}:
 * <pre>
 * schemaVersion=1
 * DEVELOPMENT.profile=content-development-full
 * DEVELOPMENT.generation=1
 * DEVELOPMENT.enabled=tavall-content-auto-post-system,tavall-content-auto-post-discovery-feature
 * DEVELOPMENT.disabled=tavall-content-auto-post-engagement-feature
 * </pre>
 * Artifact identity names the bytes, deployment identity the target, the profile the desired composition.
 *
 * @param schemaVersion           profile schema version
 * @param profileId               profile identity
 * @param environment             deployment environment, upper case
 * @param configurationGeneration profile configuration generation
 * @param enabledModules          modules this deployment must activate
 * @param disabledModules         modules this deployment must keep inactive
 */
public record ModuleProfileDefinition(long schemaVersion, String profileId, String environment,
                                      long configurationGeneration, Set<String> enabledModules,
                                      Set<String> disabledModules) {

    /** File name of the module profiles next to a service's CD template. */
    public static final String FILE_NAME = "module-profiles.properties";

    public ModuleProfileDefinition {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(environment, "environment");
        if (profileId.isBlank() || environment.isBlank()) throw new IllegalArgumentException("profile and environment are required");
        environment = environment.trim().toUpperCase(Locale.ROOT);
        enabledModules = Set.copyOf(new LinkedHashSet<>(enabledModules == null ? Set.of() : enabledModules));
        disabledModules = Set.copyOf(new LinkedHashSet<>(disabledModules == null ? Set.of() : disabledModules));
    }

    /**
     * Reads the profile of one environment from a module profiles file.
     *
     * @param file        module profiles file
     * @param environment deployment environment
     * @return the profile; empty when the file or the environment entry does not exist
     */
    public static Optional<ModuleProfileDefinition> read(Path file, String environment) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(environment, "environment");
        if (!Files.isRegularFile(file)) return Optional.empty();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read module profiles " + file, failure);
        }
        String prefix = environment.trim().toUpperCase(Locale.ROOT) + ".";
        String profile = properties.getProperty(prefix + "profile");
        if (profile == null || profile.isBlank()) return Optional.empty();
        return Optional.of(new ModuleProfileDefinition(
                Long.parseLong(properties.getProperty("schemaVersion", "1").trim()),
                profile.trim(), environment,
                Long.parseLong(properties.getProperty(prefix + "generation", "0").trim()),
                split(properties.getProperty(prefix + "enabled")),
                split(properties.getProperty(prefix + "disabled"))));
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
