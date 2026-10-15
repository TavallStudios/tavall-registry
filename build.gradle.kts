import java.util.zip.ZipFile
import java.security.MessageDigest

plugins {
    `java-library`
    `maven-publish`
}

group = "org.tavall"
version = providers.gradleProperty("tavallRegistryVersion").orElse("1.3.0").get()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
    withJavadocJar()
}

repositories {
    val exactSourceRepository = providers.gradleProperty("tavallExactSourceRepository")
        .orElse(providers.environmentVariable("TAVALL_EXACT_SOURCE_REPOSITORY"))
        .orElse(providers.gradleProperty("tavallPublishRepository"))
        .orNull
    if (!exactSourceRepository.isNullOrBlank()) {
        maven {
            name = "TavallExactSource"
            url = uri(exactSourceRepository)
        }
    }
    val scopedBootstrap = providers.gradleProperty("tavallBootstrapRepository")
        .orElse(providers.environmentVariable("TAVALL_BOOTSTRAP_REPOSITORY"))
        .orNull
    if (!scopedBootstrap.isNullOrBlank()) {
        val repoDir = file(scopedBootstrap)
        if (repoDir.isDirectory) {
            maven(repoDir) {
                name = "tavallScopedBootstrap"
            }
        }
    }
    mavenCentral()
    val githubToken = providers.environmentVariable("GITHUB_TOKEN").orNull
    if (!githubToken.isNullOrBlank()) {
        listOf(
            "tavall-cloud",
            "tavall-logging",
            "tavall-concurrency",
            "tavall-reflection",
            "tavall-di",
            "tavall-eventbus",
            "tavall-cache",
            "tavall-database",
            "tavall-registry",
            "tavall-scheduler",
        ).forEach { repository ->
            maven("https://maven.pkg.github.com/TavallStudios/$repository") {
                name = "github${repository.replace("-", "")}"
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orElse("github").get()
                    password = githubToken
                }
            }
        }
    }
}

dependencies {
    api("org.tavall:abstract-cache-system:1.0.0")
    api("org.tavall:tavall-di:1.2.1")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyJarContents = tasks.register("verifyJarContents") {
    dependsOn(tasks.jar)
    val archive = tasks.jar.flatMap { it.archiveFile }
    inputs.file(archive)
    doLast {
        val forbidden = listOf("com/fasterxml/", "com/mongodb/", "org/postgresql/", "redis/clients/")
        ZipFile(archive.get().asFile).use { jar ->
            val embedded = jar.entries().asSequence().map { it.name }
                .firstOrNull { entry -> forbidden.any(entry::startsWith) }
            check(embedded == null) { "Third-party class embedded in first-party JAR: $embedded" }
        }
    }
}

val verifyDiArtifactDigest = tasks.register("verifyDiArtifactDigest") {
    description = "Verifies the SHA-256 of a published DI 1.2.1 artifact; exact-source composite builds are pinned by source digest instead"
    doLast {
        val expectedSha256 = "3a3440a03e8aaa80f28394ca63e0b283b13ee19d1bbe4b081ae4ef299f22b83c"
        val publishedDiJar = configurations.compileClasspath.get().files.firstOrNull {
            it.name == "tavall-di-1.2.1.jar" && it.invariantSeparatorsPath.contains("/modules-2/files-2.1/")
        }
        if (publishedDiJar != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            val actualHash = digest.digest(publishedDiJar.readBytes()).joinToString("") { "%02x".format(it) }
            check(actualHash.equals(expectedSha256, ignoreCase = true)) {
                "tavall-di:1.2.1 artifact SHA-256 digest mismatch! Expected $expectedSha256 but observed $actualHash"
            }
        }
    }
}

tasks.check {
    dependsOn(verifyJarContents)
    dependsOn(verifyDiArtifactDigest)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "tavall-registry"
        }
    }
    repositories {
        val token = providers.environmentVariable("GITHUB_TOKEN")
        if (token.isPresent) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/TavallStudios/tavall-registry")
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    password = token.get()
                }
            }
        }
    }
}
