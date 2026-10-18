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
    // Loom publishes a plugin marker for some versions and not others -- 1.10.5 has none on either
    // Fabric's Maven or the plugin portal, yet resolves; 1.14.10 has one on Fabric's Maven. Mapping
    // the id straight to the module removes the marker from the question entirely, which is what
    // makes it possible to test a version whose marker is missing. requested.version is null here
    // when the version comes from pluginManagement.plugins, so the property is read directly.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "fabric-loom") {
                useModule("net.fabricmc:fabric-loom:" + providers.gradleProperty("fabric_loom_version").get())
            }
        }
    }

    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Fabric Loom. On Fabric's own Maven, not the plugin portal.
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        // MinecraftForge's Maven. ModDevGradle needs it for its own artifacts.
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

        // Fabric and NeoForge nodes take the same shape: the loader is a suffix on the node name and
        // selects the build script, so `//? if >=26.1` still evaluates against 26.1 whichever
        // loader the node builds for. That is what lets one source tree serve all three loaders.
        fun fabric(version: String) {
            version("$version-fabric", version).buildscript("build.fabric.gradle.kts")
        }


        // The MC versions this project supports. Ported nodes build; the rest are the
        // migration queue -- `./gradlew build` only touches the active one, so a half-ported
        // tree still builds the version you are working on. `chiseledBuild` needs them all.
        neoforge("1.20.4")
        neoforge("1.20.6")
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
        fabric("1.20.1")
        fabric("1.20.4")
        fabric("1.20.6")
        fabric("1.21.1")
        fabric("1.21.4")
        fabric("1.21.6")
        fabric("1.21.8")
        fabric("1.21.11")
        fabric("26.1")
        fabric("26.2")
        fabric("26.3")
        //
        // The Minecraft jar from 26.1 on is not obfuscated -- 10208 of its 10682 classes are
        // already under net/minecraft/ -- and Mojang publishes no client_mappings for it, which is
        // why Loom's officialMojangMappings() finds nothing. But no mappings are needed: an identity
        // mapping is the correct description of that jar, and the NeoForm config for 26.1.2 has no
        // mappings step either. src/fabric/aw/identity-mappings.jar is that identity mapping and it
        // is accepted.
        //
        // What stops it is Fabric's own intermediary artifact for these versions:
        //
        //   inconsistent module metadata found. Descriptor: net.fabricmc:intermediary:0.0.0
        //   Errors: bad version: expected='26.1.2' found='0.0.0'
        //
        // The jar exists -- 572 bytes, an empty mapping set, which is right for an unobfuscated
        // jar -- but its POM declares version 0.0.0. That is a publication bug upstream, and it is
        // workaroundable from here: a corrected POM in the workspace mirror plus a repository
        // content filter so the good one is consulted first.

        // 26.x Fabric is NOT registered. Two walls, in order:
        //
        //  1. Loom requires the Gradle JVM itself to be the Java the Minecraft version needs
        //     ("Minecraft 26.1 requires Java 25 but Gradle is using 21"), which is why the tree
        //     is on Gradle 9.5.1 with Java 25 rather than 8.14.6. Gradle 9 is fine for
        //     everything this tree needs.
        //  2. Mojang publishes NO client_mappings or server_mappings for 26.1.2, 26.2 or 26.3
        //     -- both keys are absent from the version JSON. Loom's officialMojangMappings()
        //     has nothing to fetch, so a Fabric node cannot be given Mojang names at all.
        //
        // NeoForge is unaffected because ModDevGradle takes its names from the NeoForge
        // artifact, which carries them. Their gradle.properties are in place.


        // The newest supported version. VCS-facing: it is the state src/ is kept in, so what
        // you read on disk is modern API and older versions are the commented-out branches.
        vcsVersion = "1.21.1-neoforge"
    }
}

rootProject.name = "HowToGo"
