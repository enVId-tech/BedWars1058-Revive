import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.etran"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/public/")
}

dependencies {
    // Compiled against the oldest supported API (1.8.8) so nothing newer than 1.8 can sneak in.
    // Anything newer is reached through reflection in the compat layer.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
    // The BedWars1058 API jar is vendored in libs/ (GPL-3.0, from the public BedWars1058 repo).
    // The official Maven repo (repo.andrei1058.dev) sits behind a bot challenge, and the API's
    // POM can't be resolved from JitPack, so a flat file is the reliable option.
    compileOnly(files("libs/bedwars-api-24.9.jar"))

    implementation(kotlin("stdlib"))
}

kotlin {
    compilerOptions {
        // Java 8 bytecode so the same jar loads on 1.8 servers (Java 8) up to modern servers.
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks {
    withType<JavaCompile> {
        options.release.set(8)
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("BedWars1058-Revive-${project.version}.jar")
        // Keep our own copy of the Kotlin runtime so we never clash with other plugins.
        relocate("kotlin", "dev.etran.bedwarsrevive.libs.kotlin")
        relocate("org.jetbrains", "dev.etran.bedwarsrevive.libs.jetbrains")
        relocate("org.intellij", "dev.etran.bedwarsrevive.libs.intellij")
        minimize()
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        archiveClassifier.set("plain")
    }
}
