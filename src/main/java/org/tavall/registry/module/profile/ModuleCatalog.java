package org.tavall.registry.module.profile;

import org.tavall.registry.module.ModuleDescriptor;
import org.tavall.registry.module.ModulePrimaryType;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The modules an immutable runtime artifact contains. Each SYSTEM ships one catalog resource under
 * {@value #RESOURCE_DIRECTORY}, so release validation can read an artifact's module set without starting it:
 * <pre>
 * modules=tavall-content-auto-post-system,tavall-content-auto-post-inbound-feature
 * tavall-content-auto-post-system.type=SYSTEM
 * tavall-content-auto-post-inbound-feature.type=FEATURE
 * tavall-content-auto-post-inbound-feature.parent=tavall-content-auto-post-system
 * tavall-content-auto-post-inbound-feature.requires=tavall-content-auto-post-engagement-feature
 * </pre>
 *
 * @param modules catalog entries, unique by module identity
 */
public record ModuleCatalog(List<ModuleCatalogEntry> modules) {

    /** Directory inside an artifact holding one catalog resource per SYSTEM. */
    public static final String RESOURCE_DIRECTORY = "META-INF/tavall/modules/";

    public ModuleCatalog {
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        Map<String, ModuleCatalogEntry> unique = new LinkedHashMap<>();
        for (ModuleCatalogEntry entry : modules) {
            if (unique.put(entry.moduleId(), entry) != null) {
                throw new IllegalArgumentException("Module " + entry.moduleId() + " is declared more than once");
            }
        }
    }

    /**
     * @param descriptors registered module descriptors
     * @return the catalog of those modules
     */
    public static ModuleCatalog fromDescriptors(Collection<ModuleDescriptor> descriptors) {
        return new ModuleCatalog(descriptors.stream().map(ModuleCatalogEntry::from).toList());
    }

    /**
     * Reads every catalog resource of a runtime artifact (a JAR such as a Spring Boot executable, including
     * its nested {@code BOOT-INF/lib} JARs) or of an exploded directory.
     *
     * @param artifact artifact JAR or directory
     * @return the merged catalog; empty when the artifact declares no modules
     */
    public static ModuleCatalog read(Path artifact) {
        Objects.requireNonNull(artifact, "artifact");
        List<ModuleCatalogEntry> entries = new ArrayList<>();
        try {
            if (Files.isDirectory(artifact)) {
                Path directory = artifact.resolve(RESOURCE_DIRECTORY);
                if (Files.isDirectory(directory)) {
                    try (var files = Files.list(directory)) {
                        for (Path file : files.filter(path -> path.toString().endsWith(".properties")).sorted().toList()) {
                            try (InputStream input = Files.newInputStream(file)) {
                                entries.addAll(parse(input));
                            }
                        }
                    }
                }
            } else {
                try (JarFile jar = new JarFile(artifact.toFile())) {
                    entries.addAll(readJar(jar));
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the module catalog of " + artifact, failure);
        }
        return new ModuleCatalog(entries);
    }

    private static List<ModuleCatalogEntry> readJar(JarFile jar) throws IOException {
        List<ModuleCatalogEntry> entries = new ArrayList<>();
        List<JarEntry> jarEntries = jar.stream().sorted(Comparator.comparing(JarEntry::getName)).toList();
        for (JarEntry entry : jarEntries) {
            String name = entry.getName();
            if (name.startsWith(RESOURCE_DIRECTORY) && name.endsWith(".properties")) {
                try (InputStream input = jar.getInputStream(entry)) {
                    entries.addAll(parse(input));
                }
            } else if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                Path nested = Files.createTempFile("tavall-module-catalog", ".jar");
                try {
                    try (InputStream input = jar.getInputStream(entry)) {
                        Files.copy(input, nested, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    try (JarFile nestedJar = new JarFile(nested.toFile())) {
                        for (JarEntry nestedEntry : nestedJar.stream().sorted(Comparator.comparing(JarEntry::getName)).toList()) {
                            if (nestedEntry.getName().startsWith(RESOURCE_DIRECTORY) && nestedEntry.getName().endsWith(".properties")) {
                                try (InputStream input = nestedJar.getInputStream(nestedEntry)) {
                                    entries.addAll(parse(input));
                                }
                            }
                        }
                    }
                } finally {
                    Files.deleteIfExists(nested);
                }
            }
        }
        return entries;
    }

    /**
     * @param input one catalog resource
     * @return its entries
     */
    public static List<ModuleCatalogEntry> parse(InputStream input) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        List<ModuleCatalogEntry> entries = new ArrayList<>();
        for (String moduleId : split(properties.getProperty("modules"))) {
            String type = properties.getProperty(moduleId + ".type");
            if (type == null) throw new IllegalArgumentException("Catalog module " + moduleId + " has no type");
            entries.add(new ModuleCatalogEntry(moduleId, ModulePrimaryType.valueOf(type.trim()),
                    Optional.ofNullable(properties.getProperty(moduleId + ".parent")).map(String::trim).filter(v -> !v.isEmpty()).orElse(null),
                    split(properties.getProperty(moduleId + ".requires"))));
        }
        return entries;
    }

    /** @return this catalog as one catalog resource */
    public String toResource() {
        StringBuilder text = new StringBuilder("# Tavall module catalog: the modules this artifact contains.\n");
        text.append("modules=").append(String.join(",", modules.stream().map(ModuleCatalogEntry::moduleId).toList())).append('\n');
        for (ModuleCatalogEntry entry : modules) {
            text.append(entry.moduleId()).append(".type=").append(entry.primaryType().name()).append('\n');
            if (entry.parentSystemId() != null) text.append(entry.moduleId()).append(".parent=").append(entry.parentSystemId()).append('\n');
            if (!entry.requiredDependencies().isEmpty()) {
                text.append(entry.moduleId()).append(".requires=").append(String.join(",", entry.requiredDependencies())).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * @param moduleId module identity
     * @return the entry, or empty when the artifact does not contain the module
     */
    public Optional<ModuleCatalogEntry> find(String moduleId) {
        return modules.stream().filter(entry -> entry.moduleId().equals(moduleId)).findFirst();
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
