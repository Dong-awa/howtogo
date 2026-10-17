// The Forge build script: applied once per forge node, with that node's
// versions/<name>/gradle.properties in scope.
//
// Forge is the third loader and the oldest of the three targets. It shares the same src/ as the
// other two -- src/common is loader-free, src/main knows which Minecraft and which loader it runs
// on -- so the work here is the loader plumbing and nothing else.
//
// Two things about ForgeGradle 6 are worth knowing before reading further:
//
// 1. It refuses Gradle 9 by design. The check is skippable (net.minecraftforge.gradle.check.gradle,
//    set false in the root gradle.properties) and it is skipped here because the whole tree is on
//    Gradle 9.5.1 -- Loom requires the Gradle JVM itself to be the Java 26.x needs, and Gradle 8
//    cannot run on Java 25. Whether the rest of ForgeGradle actually works on Gradle 9 is what the
//    first forge node is here to find out.
//
// 2. Its plugin block cannot read a node's properties, so the plugin version is declared in
//    settings.gradle.kts pluginManagement, the same arrangement Loom uses.

plugins {
    id("net.minecraftforge.gradle")
    id("java")
}

// Declares this node's loader for the //? if <loader> conditions in the shared source. All three are
// declared on every node because a condition naming an undeclared constant is an error, not a false.
stonecutter.const("neoforge", false)
stonecutter.const("fabric", false)
stonecutter.const("forge", true)

val minecraftVersion = stonecutter.current.version
val forgeVersion = property("deps.forge") as String
val modVersion = rootProject.property("mod_version") as String

// 1.20.5 is the boundary, not 1.21: 1.20.5 and 1.20.6 are Java 21, and 1.20.4 and earlier are
// Java 17. Same three-way split as the other two scripts, for the same reason.
val requiredJava = when {
    stonecutter.eval(minecraftVersion, ">=26.1") -> JavaVersion.VERSION_25
    stonecutter.eval(minecraftVersion, ">=1.20.5") -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
    withSourcesJar()
}

minecraft {
    mappings("official", minecraftVersion)
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
    // Mojang's own library host: com.mojang:logging and friends live here and nowhere else.
    // ForgeGradle asks for them while setting up the Minecraft artifacts, and the error when this
    // repository is missing names the artifact rather than the host, which is why it reads as a
    // missing jar.
    maven("https://libraries.minecraft.net/") { name = "MojangLibraries" }
    // Xaero's World Map and XaeroLib, which are closed-source and on Modrinth's Maven.
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
    maven {
        name = "workspaceLocalMirror"
        url = uri(providers.gradleProperty("htgLocalMaven").getOrElse("${rootProject}/.recon/maven-local"))
    }
}

dependencies {
    // The userdev artifact. Naming the classifier explicitly changes nothing -- ForgeGradle appends
    // it either way and the cache ends up with the same single generated inject_src.jar -- but it
    // is written out here because "the classifier was missing" is the first thing the failure
    // suggests and it has been ruled out.
    minecraft("net.minecraftforge:forge:$forgeVersion")
}

// The shared tree, exactly as the other two loaders take it.
sourceSets {
    main {
        java.srcDir(rootProject.file("src/common/java"))
    }
}

// No MCPhone integration on Forge either: the phone mod is NeoForge-only.
sourceSets.main.get().java.exclude("**/compat/mcphone/**")

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "100000"))
}
