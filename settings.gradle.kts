// Stonecutter turns this one checkout into a project tree with a node per supported
// (Minecraft version x mod loader) pair. Each node is a Gradle subproject under versions/,
// they all compile the SAME shared source in src/ -- the per-version differences live in
// `//?` comments inside that source (see stonecutter.gradle.kts) instead of in N git branches.
//
//   ./gradlew build                      builds the ACTIVE node only (fast, what you edit)
//   ./gradlew "Set active project to X"  switches the active node and rewrites src/ to its state
//   ./gradlew chiseledBuild              builds every node (what CI runs)
//
// Why nodes are named "<version>-<loader>": the loader is part of the name but NOT of the
// logical version, so `//? if >=26.1` still evaluates against 26.1 for the 26.1-neoforge node.
// Adding Fabric later means adding a `fabric` to the loader list below plus build.fabric.gradle.kts;
// nothing about the shared source has to move.

pluginManagement {
    // A plugins block cannot read a node's properties -- it is evaluated before the node's
    // gradle.properties is in scope -- so the loader toolchain versions are declared here, where
    // the root properties are readable, and the build scripts apply the plugins by id alone.
    plugins {
        id("fabric-loom") version providers.gradleProperty("fabric_loom_version").get()
    }

    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Fabric Loom. On Fabric's own Maven, not the plugin portal.
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        // ForgeGradle. Forge's Maven, likewise.
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        // Stonecutter itself. Not on Maven Central -- this is its home repository.
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        // moddev / NeoForge. maven.neoforged.net is unreachable from this network; bmclapi
        // proxies most of it and the workspace mirror carries the rest (see the root build).
        maven("https://bmclapi2.bangbang93.com/maven") { name = "BMCLAPI" }
        maven {
            name = "workspaceLocalMirror"
            url = uri(providers.gradleProperty("htgLocalMaven").getOrElse("${rootDir}/.recon/maven-local"))
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
        // Registering a node name that a loader owns; the logical version is the MC version
        // it builds against. See the header for why the loader is a suffix.
        fun neoforge(version: String) {
            version("$version-neoforge", version).buildscript("build.neoforge.gradle.kts")
        }

        // Fabric and Forge nodes take the same shape: the loader is a suffix on the node name and
        // selects the build script, so `//? if >=26.1` still evaluates against 26.1 whichever
        // loader the node builds for. That is what lets one source tree serve all three loaders.
        fun fabric(version: String) {
            version("$version-fabric", version).buildscript("build.fabric.gradle.kts")
        }

        fun forge(version: String) {
            version("$version-forge", version).buildscript("build.forge.gradle.kts")
        }

        // The MC versions this project supports. Ported nodes build; the rest are the
        // migration queue -- `./gradlew build` only touches the active one, so a half-ported
        // tree still builds the version you are working on. `chiseledBuild` needs them all.
        neoforge("1.21.1")
        neoforge("1.21.4")
        neoforge("1.21.6")
        neoforge("1.21.8")
        neoforge("1.21.11")
        neoforge("26.1")
        neoforge("26.2")
        neoforge("26.3")

        // Fabric is registered one node at a time as its loader glue lands, because a fabric node
        // whose source has no `//? if fabric` branches yet does not compile and would take
        // chiseledBuild down with it. The rest of the list is the same eleven versions the
        // NeoForge half covers plus the three older ones.
        fabric("1.21.1")
        fabric("1.21.4")
        fabric("1.21.6")
        fabric("1.21.8")

        // 1.21.11 and later are NOT registered yet: their Fabric API needs a newer Loom than
        // the one the rest of the tree uses, and a node that cannot configure takes every
        // other fabric task down with it -- Gradle configures all projects, so the failure is
        // not confined to the node that caused it. Their gradle.properties are in place.

        // The newest supported version. VCS-facing: it is the state src/ is kept in, so what
        // you read on disk is modern API and older versions are the commented-out branches.
        vcsVersion = "1.21.1-neoforge"
    }
}

rootProject.name = "HowToGo"
