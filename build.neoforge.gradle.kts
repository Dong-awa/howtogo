// The NeoForge build script: applied once per neoforge node, with that node's
// versions/<name>/gradle.properties in scope. Everything here is either shared by all nodes
// or branched on sc.current (the node's Minecraft version).
//
// Ported from the pre-Stonecutter build.gradle with the version-specific parts hoisted into
// the per-node properties, so the offline-mirror wiring and the mcphone escape hatch keep
// working exactly as before.

import java.util.zip.ZipFile
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

plugins {
    id("net.neoforged.moddev") version "2.0.148"
}

val minecraftVersion = sc.current.version
val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

// Read the per-node properties ONCE, here, where property() resolves against this node's
// versions/<name>/gradle.properties. Task configuration blocks below are evaluated lazily and
// for every node, and a property() call inside one does not reliably resolve against the node
// that owns the task -- it fails with "unknown property 'deps.neo_loader'" while plain script
// scope reads the same file fine.
val neoForgeVersion = property("deps.neo_loader") as String
val xaeroCoordinate = property("deps.xaero") as String
val modVersion = rootProject.property("mod_version") as String

version = "$modVersion+$minecraftVersion"
group = rootProject.property("maven_group") as String
base.archivesName = "${rootProject.property("archives_name") as String}-neoforge-$minecraftVersion"

java.toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)

neoForge {
    version = neoForgeVersion

    // NeoFormRuntime downloads its own tool jars from maven.neoforged.net, which is unreachable
    // on this network. Pinning the version keeps it on the copy the workspace mirror serves.
    neoFormRuntime {
        version = rootProject.property("nfrt_version") as String
    }

    runs {
        register("client") {
            client()
            systemProperty("forge.logging.markers", "REGISTRIES")
            // The in-game self-test rig (tools/user-test/run-user-test.ps1) drives these.
            systemProperty("howtogo.selftest", rootProject.findProperty("htgSelftest")?.toString() ?: "")
            systemProperty("howtogo.selftest.quit", rootProject.findProperty("htgSelftestQuit")?.toString() ?: "")
            if (rootProject.hasProperty("htgQuickPlay")) {
                programArgument("--quickPlaySingleplayer")
                programArgument(rootProject.property("htgQuickPlay").toString())
            }
        }
    }

    mods {
        register("howtogo") {
            sourceSet(sourceSets.main.get())
        }
    }
}

// The shared source is split in two so the portable half cannot drift into the loader half:
// src/common is pure Java (road network, routing, transit model, reflective bridges) with no
// Minecraft, loader or Xaero import at all, and src/main is everything that has to know which
// Minecraft and which loader it is running on. Stonecutter owns src/main -- that is the tree
// it toggles between versions -- while src/common is the same bytes for every node and is
// therefore attached as-is.
sourceSets {
    main {
        java.srcDir(rootProject.file("src/common/java"))
    }
}

// The mcphone compat package compiles against the (private, not on Modrinth) mcphone mod's API
// jar. When that jar is absent from libs/, exclude the compat sources and their ServiceLoader
// registration instead of failing: the classes are only ever loaded when mcphone itself is
// installed, so an install without the jar loses nothing.
val mcphoneApiJar = fileTree(rootProject.file("libs")) { include("*.jar") }.find { f ->
    try {
        // ZipFile, not java.util.zip.ZipFile: in a Kotlin build script `java` is the Java
        // plugin extension and shadows the package root.
        ZipFile(f).getEntry("com/november/mcphone/api/client/app/IPhoneApp.class") != null
    } catch (ignored: Exception) {
        false
    }
}

if (mcphoneApiJar == null) {
    sourceSets.main.get().java.exclude("**/compat/mcphone/**")
    sourceSets.main.get().resources.exclude("META-INF/services/com.november.mcphone.api.client.app.IPhoneApp")
}

repositories {
    // moddev injects two maven.neoforged.net repositories at apply time and sorts them first.
    // That host is unreachable here and stalls every resolution until timeout, so drop them;
    // what they served is in the workspace mirror or on bmclapi below.
    val deadRepos = repositories.filter { r ->
        val u = (r as? MavenArtifactRepository)?.url?.toString()
        u != null && u.contains("maven.neoforged.net")
    }
    deadRepos.forEach { repositories.remove(it) }

    // The workspace-local mirror carries the NeoForm toolchain (neoform-runtime, neoform,
    // mergetool, AutoRenamingTool, ...) and the minecraft-dependencies metadata, some of which
    // ship no POM-only fallback: module metadata is all this repo stores for them.
    // Worktrees pass -PhtgLocalMaven=<absolute path> to share the main checkout's mirror.
    maven {
        name = "workspaceLocalMirror"
        url = uri(rootProject.findProperty("htgLocalMaven")?.toString() ?: "${rootProject.rootDir}/.recon/maven-local")
        metadataSources { gradleMetadata() }
    }
    // bmclapi proxies Maven Central (and parts of the NeoForged repo), which is what satisfies
    // the parent POMs the Mojang library POMs reference -- Mojang's host serves only the leaves.
    maven { name = "bmclapi"; url = uri("https://bmclapi2.bangbang93.com/maven") }
    maven { name = "mojangLibraries"; url = uri("https://libraries.minecraft.net/") }
    maven {
        name = "Modrinth"
        url = uri("https://api.modrinth.com/maven")
        content { includeGroup("maven.modrinth") }
    }
    flatDir { dirs(rootProject.file("libs")) }
}

dependencies {
    // Xaero's World Map is closed-source and on no Maven repo: compile against the shipped jar.
    // The coordinate is per-node; the matching jar must also sit in libs/ for the fileTree below.
    compileOnly(xaeroCoordinate)
    compileOnly(fileTree(rootProject.file("libs")) { include("*.jar") })
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = mapOf(
        "minecraft_version" to minecraftVersion,
        "neoforge_version" to neoForgeVersion,
        "mod_version" to modVersion,
    )
    inputs.properties(replaceProperties)
    // MIT requires the notice to travel with every copy, and the copy people receive is the jar.
    from(rootProject.file("LICENSE")) { into("META-INF") }
    filesMatching(listOf("META-INF/neoforge.mods.toml")) { expand(replaceProperties) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Offline-mirror escape hatch: a node may declare dependencySubstitutions as a comma-separated
// list of "group:artifact[:version]->group:artifact[:version]" pairs, used when neither the
// blocked official repo nor bmclapi has the exact version but the mirror holds a sibling.
// Implemented with eachDependency (NOT dependencySubstitution): moddev resolves some of its
// neoFormRuntimeDependencies* configurations eagerly during evaluation, where static
// substitution rules never take effect while eachDependency callbacks do.
val substitutions = (findProperty("dependencySubstitutions") ?: "").toString()
    .split(",").map { it.trim() }.filter { it.contains("->") }
if (substitutions.isNotEmpty()) {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            substitutions.forEach { raw ->
                val (from, to) = raw.split("->").map { it.trim() }
                val fromParts = from.split(":")
                val fromVersion = fromParts.getOrNull(2)
                if (requested.group == fromParts[0] && requested.name == fromParts[1] &&
                    (fromVersion == null || requested.version == fromVersion)) {
                    val toParts = to.split(":")
                    useTarget(if (toParts.size > 2) to else "${toParts[0]}:${toParts[1]}:${requested.version}")
                }
            }
        }
    }
}
