import java.util.zip.ZipFile

plugins {
    `java-library`
    `maven-publish`
}

group = "org.tavall"
version = "1.0.0"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    api("org.tavall:abstract-cache-system:1.0.0")
    api("org.tavall:tavall-di:1.0.0")
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

tasks.check {
    dependsOn(verifyJarContents)
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
