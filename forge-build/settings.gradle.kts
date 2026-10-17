// The Forge build: a second Gradle build, on Gradle 8.14.6, that produces the three Forge nodes.
//
// WHY THIS IS SEPARATE FROM THE MAIN TREE
//
// ForgeGradle 6 does not work on Gradle 9. It applies -- its version check is skippable and its
// dependencies resolve -- but it registers its task groups and no tasks inside them, so nothing
// produces the mapped Minecraft artifact and compileClasspath cannot resolve. Meanwhile the main
// tree cannot stay on Gradle 8: Loom requires the Gradle JVM itself to be the Java the Minecraft
// version needs, the 26.x nodes need Java 25, and Gradle 8 cannot run on Java 25.
//
// So the two requirements are on opposite sides of a Gradle version and this build is the Forge
// side of it. It is a separate Gradle invocation over the same src/ -- forge-build/src is a
// junction to ../src -- so there is still exactly one copy of the source and one set of `//?`
// markers. Only the Gradle version and the registered nodes differ.
//
//   cd forge-build && ./gradlew build

pluginManagement {
    // A plugins block cannot read a node's properties, so the plugin version is declared here,
    // where the root properties are readable. Same arrangement as the main tree.
    plugins {
        id("net.minecraftforge.gradle") version providers.gradleProperty("forge_gradle_version").get()
    }

    repositories {
        mavenCentral()
        gradlePluginPortal()
        // ForgeGradle. Forge's Maven.
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        // Stonecutter itself. Not on Maven Central -- this is its home repository.
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        maven {
            name = "workspaceLocalMirror"
            url = uri(providers.gradleProperty("htgLocalMaven").getOrElse("${rootDir}/../.recon/maven-local"))
        }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.7.11"
    // Lets Gradle find the Zulu JDKs under D:\java, which auto-detection misses.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // Only Forge nodes here. The loader is a suffix on the node name and selects the build
        // script, exactly as in the main tree, so the same `//?` conditions evaluate the same way.
        fun forge(version: String) {
            version("$version-forge", version).buildscript("../build.forge.gradle.kts")
        }

        // The Forge-supported versions. 1.20.1 is the oldest and the one the original standalone
        // Forge branch targeted; 1.20.4 and 1.20.6 came later.
        forge("1.20.1")
        forge("1.20.4")
        forge("1.20.6")

        vcsVersion = "1.20.1-forge"
    }
}
