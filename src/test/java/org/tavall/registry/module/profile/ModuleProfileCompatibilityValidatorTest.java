package org.tavall.registry.module.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tavall.registry.module.ModuleDescriptor;
import org.tavall.registry.module.ModuleId;
import org.tavall.registry.module.ModulePrimaryType;
import org.tavall.registry.module.RuntimeTarget;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release-time module-profile compatibility of an artifact's module catalog against deployment profiles,
 * using the profiles from the universal lifecycle brief (Content deployments A and B).
 */
final class ModuleProfileCompatibilityValidatorTest {

    private static final RuntimeTarget TARGET = RuntimeTarget.of("content");
    private static final String SYSTEM = "auto-post-system";

    @TempDir
    Path directory;

    private final ModuleProfileCompatibilityValidator validator = new ModuleProfileCompatibilityValidator();

    @Test
    void catalogRoundTripsThroughAnArtifactResourceIncludingNestedBootJars() throws Exception {
        ModuleCatalog catalog = autoPostCatalog();
        Path plainJar = jar(directory.resolve("plain.jar"), ModuleCatalog.RESOURCE_DIRECTORY + "auto-post.properties", catalog.toResource().getBytes(StandardCharsets.UTF_8));
        assertEquals(catalog, ModuleCatalog.read(plainJar));

        Path bootJar = jar(directory.resolve("runtime.jar"), "BOOT-INF/lib/auto-post-system.jar", Files.readAllBytes(plainJar));
        assertEquals(catalog, ModuleCatalog.read(bootJar));
        assertTrue(ModuleCatalog.read(jar(directory.resolve("empty.jar"), "README.txt", new byte[0])).modules().isEmpty());
    }

    @Test
    void briefProfilesAAndBAreCompatibleWithTheSameArtifact() {
        ModuleCatalog catalog = autoPostCatalog();
        ModuleProfileCompatibility a = validator.validate(catalog, profile("content-a",
                Set.of(SYSTEM, "discovery", "video-watch", "creative", "publishing", "reporting"),
                Set.of("engagement", "inbound")));
        assertTrue(a.compatible(), a.problem());
        assertEquals(SYSTEM, a.loadOrder().get(0), "a SYSTEM activates before its FEATURES");

        ModuleProfileCompatibility b = validator.validate(catalog, profile("content-b",
                Set.of(SYSTEM, "creative", "reporting"),
                Set.of("discovery", "video-watch", "engagement", "inbound", "publishing")));
        assertTrue(b.compatible(), b.problem());
    }

    @Test
    void incompatibleProfilesAreRefusedWithAReasonInsteadOfBeingAdjusted() {
        ModuleCatalog catalog = autoPostCatalog();
        assertIncompatible(catalog, profile("missing-module", Set.of(SYSTEM, "telemetry"), Set.of()), "telemetry");
        assertIncompatible(catalog, profile("disabled-dependency", Set.of(SYSTEM, "inbound"), Set.of("engagement")), "engagement");
        assertIncompatible(catalog, profile("disabled-system", Set.of("discovery"), Set.of(SYSTEM)), SYSTEM);
        ModuleProfileDefinition unsupported = new ModuleProfileDefinition(9, "future", "DEVELOPMENT", 1, Set.of(SYSTEM), Set.of());
        assertIncompatible(catalog, unsupported, "schema");
    }

    @Test
    void profilesAreReadPerEnvironmentFromTheDeploymentConfiguration() throws Exception {
        Path file = directory.resolve(ModuleProfileDefinition.FILE_NAME);
        Files.writeString(file, """
                schemaVersion=1
                DEVELOPMENT.profile=content-a
                DEVELOPMENT.generation=3
                DEVELOPMENT.enabled=auto-post-system, discovery
                DEVELOPMENT.disabled=engagement
                """);
        ModuleProfileDefinition development = ModuleProfileDefinition.read(file, "development").orElseThrow();
        assertEquals("content-a", development.profileId());
        assertEquals(3, development.configurationGeneration());
        assertEquals(Set.of(SYSTEM, "discovery"), development.enabledModules());
        assertTrue(ModuleProfileDefinition.read(file, "STAGING").isEmpty(), "an environment without a profile declares none");
        assertTrue(ModuleProfileDefinition.read(directory.resolve("absent.properties"), "DEVELOPMENT").isEmpty());
    }

    private void assertIncompatible(ModuleCatalog catalog, ModuleProfileDefinition profile, String mentioned) {
        ModuleProfileCompatibility verdict = validator.validate(catalog, profile);
        assertFalse(verdict.compatible(), profile.profileId());
        assertNotNull(verdict.problem());
        assertTrue(verdict.problem().toLowerCase().contains(mentioned.toLowerCase()), verdict.problem());
    }

    private static ModuleProfileDefinition profile(String id, Set<String> enabled, Set<String> disabled) {
        return new ModuleProfileDefinition(1, id, "DEVELOPMENT", 1, enabled, disabled);
    }

    private static ModuleCatalog autoPostCatalog() {
        ModuleId system = ModuleId.of(SYSTEM);
        List<ModuleDescriptor> descriptors = new java.util.ArrayList<>();
        descriptors.add(ModuleDescriptor.builder(system, ModulePrimaryType.SYSTEM, TARGET).build());
        for (String feature : List.of("discovery", "video-watch", "creative", "engagement", "publishing", "reporting")) {
            descriptors.add(ModuleDescriptor.builder(ModuleId.of(feature), ModulePrimaryType.FEATURE, TARGET).parentSystemId(system).build());
        }
        descriptors.add(ModuleDescriptor.builder(ModuleId.of("inbound"), ModulePrimaryType.FEATURE, TARGET).parentSystemId(system)
                .addRequiredDependency(ModuleId.of("engagement")).build());
        ModuleCatalog catalog = ModuleCatalog.fromDescriptors(descriptors);
        try {
            assertEquals(catalog.modules(), ModuleCatalog.parse(new ByteArrayInputStream(catalog.toResource().getBytes(StandardCharsets.UTF_8))));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        return catalog;
    }

    private static Path jar(Path path, String entry, byte[] content) throws Exception {
        try (OutputStream file = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(file)) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(content);
            jar.closeEntry();
        }
        return path;
    }
}
