// The NeoForge build script: applied once per neoforge node, with that node's
// versions/<name>/gradle.properties in scope. Everything here is either shared by all nodes
// or branched on sc.current (the node's Minecraft version).
//
// Ported from the pre-Stonecutter build.gradle with the version-specific parts hoisted into
// the per-node properties, so the offline-mirror wiring and the mcphone escape hatch keep
// working exactly as before.

import java.util.zip.ZipFile
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

// Declares this node's loader for the //? if neoforge conditions in the shared source. Set here
// rather than in stonecutter.gradle.kts because that block is shared by every node.
// Declares the loader this node builds for, for the `//? if <loader>` conditions in the shared
// source. All three are declared on every node, not just the true one: a condition naming a
// constant that was never declared is an error ("Unresolved constant") rather than a false, so a
// neoforge node still has to be told it is not fabric.
//
// Set here rather than in stonecutter.gradle.kts because that block is shared by every node, and a
// constant set there would be true on all of them -- which would compile the NeoForge half of
// every loader condition into the fabric jars.
stonecutter.const("neoforge", true)
stonecutter.const("fabric", false)
stonecutter.const("forge", false)

plugins {
    id("net.neoforged.moddev") version "2.0.148"
}

// Offline-mirror escape hatch: a node may declare dependencySubstitutions as a comma-separated
// list of "group:artifact[:version]->group:artifact[:version]" pairs, used when neither the
// blocked official repo nor its CDN has the exact version but the mirror holds a sibling.
// Implemented with eachDependency (NOT dependencySubstitution): moddev resolves some of its
// neoFormRuntimeDependencies* configurations eagerly during evaluation, where static
// substitution rules never take effect while eachDependency callbacks do.
//
// This has to sit ABOVE the neoForge { } block, not at the bottom of the file. ModDevGradle
// resolves the neoFormRuntime tool coordinates while that block is being configured, so a rule
// registered after it is registered too late -- the build silently keeps the pinned tool and the
// substitution looks like it did nothing.
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

// Stonecutter 0.7 names this accessor `stonecutter` rather than `sc`, and has no `parsed`
// property -- version tests go through eval(version, predicate) instead of a >= on a parsed
// value. Same two questions, older spelling.
val minecraftVersion = stonecutter.current.version
val requiredJava = when {
    stonecutter.eval(minecraftVersion, ">=26.1") -> JavaVersion.VERSION_25
    // 1.20.5 is the boundary, not 1.21: 1.20.5 and 1.20.6 are Java 21, and 1.20.4 and earlier are
    // Java 17. The Fabric script carries the same three-way split, and the reason it is computed
    // rather than written down is that one script serves every node.
    stonecutter.eval(minecraftVersion, ">=1.20.5") -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

// Read the per-node properties ONCE, here, where property() resolves against this node's
// versions/<name>/gradle.properties. Task configuration blocks below are evaluated lazily and
// for every node, and a property() call inside one does not reliably resolve against the node
// that owns the task -- it fails with "unknown property 'deps.neo_loader'" while plain script
// scope reads the same file fine.
val neoForgeVersion = property("deps.neo_loader") as String
val xaeroCoordinate = property("deps.xaero") as String
// Empty on the nodes whose Xaero generation predates the XaeroLib split. Read here for the same
// reason as the two above, and reused by both the dependency block and the metadata below.
val xaerolibJar = findProperty("deps.xaerolib")?.toString().orEmpty()
val modVersion = rootProject.property("mod_version") as String

// The NeoForge and Minecraft ranges this jar declares in its metadata.
//
// These have to be computed rather than written into the template, because one template serves every
// node and a hardcoded range is wrong on all but one of them. It was hardcoded to the 1.21.1 values
// for a while, which made every node's jar claim to be 1.21.1 -- the 26.2 jar refused to load with
// "requires minecraft 1.21.1 or above, and below 1.21.2; currently 26.2", which reads like a version
// mix-up in the build and is really just a stale literal in a resource file.
//
// The upper bound is the next minor release, and it is closed deliberately: an open-ended range
// claims compatibility with every later NeoForge, none of which this has been run against. A wrong
// upper bound is worse than a missing feature, because it lets a player load the mod on a version it
// was never tested on and then report the breakage as a bug.
//
// Worked out from the node's own versions rather than from a table, so that adding a node needs no
// edit here. The two names for a release differ by a leading "1.": Minecraft calls it 1.21.11 where
// NeoForge calls it 21.11, and both call 26.2 by the same name. So the Minecraft range is the version
// with its last part incremented, and the NeoForge range is that same base after dropping the "1.".
fun nextVersion(version: String): String {
    val parts = version.split(".")
    return (parts.dropLast(1) + (parts.last().toInt() + 1).toString()).joinToString(".")
}

val mcRange = "[$minecraftVersion,${nextVersion(minecraftVersion)})"
val neoBase = if (minecraftVersion.startsWith("1.")) {
    minecraftVersion.removePrefix("1.")
} else {
    minecraftVersion
}
val neoRange = "[$neoBase.0,${nextVersion(neoBase)})"

// The loader and Xaero entries in the metadata. Neither can be a literal in the template, for the
// same reason the two ranges above cannot: one template serves every node.
//
// javafml's major tracks FancyModLoader's and moves at every NeoForge generation -- 20.4 ships FML
// 2.0.17, 20.6 ships 3.0.45, 21.1 ships 4.0.45, 21.4 ships 6.0.18, 21.6/21.8 ship 9.x and 26.x ships
// 11.x/12.x. The old "[4,)" literal sat above the 20.4 and 20.6 generations, which then rejected the
// jar with "Missing language javafml version [4,), found 3.0.45" before the mod was constructed --
// a failure that reads like a corrupt install and is really one stale number. A lower bound of 1
// admits every generation; what pins compatibility is neoRange and mcRange, which are exact.
val loaderRange = "[1,)"

// Xaero's World Map split into World Map + XaeroLib at 1.40.0. The 1.39.x generation, which is all
// 1.20.6 and 1.21.6 ever got, ships no XaeroLib at all, so a required xaerolib entry there is a
// demand no player can satisfy -- FML answers it with "Mod howtogo requires xaerolib 1.1.0 or above,
// currently xaerolib is not installed" and never starts the game. Those two nodes declare
// deps.xaero_range=[1.39.0,) in their own gradle.properties, and the dependency type is read off
// that range: a 1.39.x world map means optional, anything from 1.40.0 on means required (all of
// those bundle XaeroLib as a jar-in-jar, whether or not this node compiles against the API).
val xaeroRange = findProperty("deps.xaero_range")?.toString() ?: "[1.40.0,)"
val xaerolibType = if (xaeroRange.startsWith("[1.39")) "optional" else "required"

// NeoForge 20.4 still ships FML 2, whose mod-file reader knows only META-INF/mods.toml; the
// neoforge.mods.toml name arrives with 20.5 (its jar has no reference to the newer name at all). A
// 20.4 jar carrying just the newer one is counted as one invalid mod file and silently left out of
// the mod list, which is exactly what the 1.20.4-neoforge test reported: "the mod list does not show
// it, no feature works", with nothing in latest.log naming a file. The processResources block below
// renames the expanded copy on those nodes only.
val legacyModsTomlName = !stonecutter.eval(minecraftVersion, ">=1.20.5")

version = "$modVersion+$minecraftVersion"
group = rootProject.property("maven_group") as String
base.archivesName = "${rootProject.property("archives_name") as String}-neoforge-$minecraftVersion"

java.toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)

neoForge {
    // HudDraw.QUADS is built from RenderStateShard's constants, and five of them are protected
    // in NeoForge's 1.20.6 jar while being public in vanilla's. The access transformer widens
    // exactly those five, and is a no-op on 1.21.x where they are already public.
    accessTransformers.from(rootProject.file("src/main/resources/META-INF/accesstransformer.cfg"))
    version = neoForgeVersion

    // NeoFormRuntime downloads its own tool jars from maven.neoforged.net, which is unreachable
    // on this network. Pinning the version keeps it on the copy the workspace mirror serves.
    // findProperty, not rootProject.property: a node may override the version in its own
    // gradle.properties (26.3 does, see there), and the root value is the fallback.
    neoFormRuntime {
        version = findProperty("nfrt_version") as String
    }

    runs {
        register("client") {
            client()
            // The dev test environment -- mods, configs, saves and Xaero's own data -- lives in the
            // ROOT run/ directory, which is where tools/user-test/run-user-test.ps1 looks for it and
            // where docs/bumping-versions.md says to swap the test mods. Only the active node uses
            // it: those mods are one Minecraft version's, so loading them on another node is a
            // guaranteed mod-loading crash. Every other node keeps its own versions/<node>/run,
            // empty until someone fills it.
            if (stonecutter.current.isActive) {
                gameDirectory.set(rootProject.layout.projectDirectory.dir("run"))
            }
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
// it toggles between versions -- while src/common is the same bytes for every node.
//
// The editor is why this is not a plain srcDir on every node. Gradle exports each node's source
// directories to IntelliJ as content roots, and one directory registered by twenty-one modules is
// de-duplicated down to a single module's -- which left the ACTIVE node, the only one whose module
// the editor resolves against, without the shared classes. So the active node points at the real
// directory and every other node compiles a copy under its own build directory, which is unique
// per node and therefore never de-duplicated. Same sources and same output as before.
sourceSets {
    main {
        if (stonecutter.current.isActive) {
            java.srcDir(rootProject.file("src/common/java"))
        } else {
            val commonCopy = layout.buildDirectory.dir("generated/common/java")
            val syncCommon = tasks.register<Sync>("syncCommonSources") {
                from(rootProject.file("src/common/java"))
                into(commonCopy)
            }
            java.srcDir(commonCopy)
            tasks.named<JavaCompile>("compileJava") { dependsOn(syncCommon) }
        }
    }
}

// The mcphone compat package compiles against the (private, not on Modrinth) mcphone mod's API
// jar. When no jar for this node is in libs/, exclude the compat sources and their ServiceLoader
// registration instead of failing: the classes are only ever loaded when mcphone itself is
// installed, so an install without the jar loses nothing.
//
// The jar is selected by the node's own Minecraft version, not by "any mcphone jar in libs/".
// One jar is built against one Minecraft generation and neither half of that is portable:
//
//   * its class files are compiled for the Java that generation needs, so the 1.21.1 jar
//     (class file 65.0, Java 21) cannot even be read by the 1.20.4 node's Java 17 compiler;
//   * the API it implements moved. ResourceLocation.fromNamespaceAndPath does not exist before
//     1.21, and 1.21.11 renamed the class itself to Identifier, so on those nodes the page's
//     overrides of IPhoneApp no longer match.
//
// With a 1.21.1 jar in libs/ the 1.21.1 node therefore compiles the integration and every other
// node compiles without it, which is what a build of the whole tree needs. Dropping in a jar for
// another version enables the integration on that node automatically.
val mcphoneApiJar = fileTree(rootProject.file("libs")) {
    include("mcphone-*-$minecraftVersion-neoforge*.jar")
}.find { f ->
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

// The Xaero's Minimap navigation overlay compiles against the minimap's element-render API. That is
// an abstract class to subclass, not an interface to reflect over, so this integration cannot be
// reached the way MTR, Create and RoadWeaver are: the overlay's own sources can only be compiled on
// a node whose libs/ holds a minimap jar for that node's Minecraft generation.
//
// So, like the mcphone integration just above, the jar's presence decides what is compiled: with a
// jar the package is compiled against it (see the dependency block), without one it is excluded from
// the source set and the node builds unchanged. What such a node loses is exactly one feature -- the
// map_display_mode=xaero_minimap overlay -- and not silently: the display-mode router is an
// always-compiled reflective bridge that notices the missing class and falls back to the in-HUD map
// with one log line explaining why.
//
// Deliberately NOT a declared or hard dependency, unlike Xaero's World Map, which every road and
// route already renders through; the minimap is only an alternative place to draw the same route,
// and requiring it would force every player to install a second Xaero mod for a mode they may never
// turn on.
//
// Selected by this node's Minecraft version for the same two reasons the mcphone jar is (see above),
// and recognised by the class the overlay must subclass rather than by file name alone, so a stray
// jar from another generation is treated as absent instead of failing javac with a version mismatch.
val minimapApiJar = fileTree(rootProject.file("libs")) {
    include("xaerominimap-neoforge-$minecraftVersion-*.jar")
}.find { f ->
    try {
        // ZipFile, not java.util.zip.ZipFile: in a Kotlin build script `java` is the Java
        // plugin extension and shadows the package root.
        ZipFile(f).getEntry("xaero/hud/minimap/element/render/MinimapElementRenderer.class") != null
    } catch (ignored: Exception) {
        false
    }
}

if (minimapApiJar == null) {
    sourceSets.main.get().java.exclude("**/xaero/minimap/**")
}

repositories {
    // moddev injects two maven.neoforged.net repositories at apply time and sorts them first.
    // That host is unreachable here and stalls every resolution until timeout, so drop them;
    // what they served is in the workspace mirror or on the CDN below.
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
    // Maven Central, through a mirror first and Central itself as the fallback. Mojang's host
    // serves only the leaf POMs; the parent POMs those reference, and third-party artifacts such
    // as the log4j version range the 1.20.x NeoForge build declares, live on Central. bmclapi used
    // to proxy this and answers 403 now, so nothing else here covers that group.
    maven { name = "mavenCentralMirror"; url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
    maven {
        name = "NeoForged"
        url = uri("https://neoforged.forgecdn.net/releases")
        // See the mojangLibraries note: dynamic versions are looked up in every repository, and a
        // TLS failure in any of them aborts the resolution. This host serves no log4j.
        content { excludeGroup("org.apache.logging.log4j") }
    }
    maven {
        name = "mojangLibraries"
        url = uri("https://libraries.minecraft.net/")
        // Dynamic-version lookups consult every repository, and this host's TLS handshake fails
        // intermittently from this network -- which then aborts the whole resolution even though a
        // mirror already had the artifact. Excluding a group it never serves keeps those lookups
        // off it; the log4j range the 1.20.x NeoForge artifacts declare is the one that hit this.
        content { excludeGroup("org.apache.logging.log4j") }
    }
    maven {
        name = "NeoForgedMojangMeta"
        url = uri("https://neoforged.forgecdn.net/mojang-meta")
        content {
            includeModule("net.neoforged", "minecraft-dependencies")
        }
    }
    maven {
        name = "Modrinth"
        url = uri("https://api.modrinth.com/maven")
        content { includeGroup("maven.modrinth") }
    }
    flatDir { dirs(rootProject.file("libs")) }

    // LAST on purpose. Every node that already builds resolves its NeoForm toolchain from the
    // mirror above, and putting this CDN first changed what 26.3 resolved -- it stopped on
    // "Node action for recompile failed" with a HolderSet patch error, having built before the
    // CDN existed. At the end it only serves what nothing else has, which is the 1.20.x NeoForm.
    // NeoForge's Maven, reached through its CDN. maven.neoforged.net is unreachable from here, but
    // neoforged.forgecdn.net/releases serves the same tree -- verified against the NeoForm metadata
    // and against neoform-1.20.4-20240627.114801.zip directly. This is what the 1.20.x nodes needed:
    // the workspace mirror carries NeoForm for 1.21.x and 26.x only, and Maven Central has no
    // neoform group at all, so without this the 1.20.x nodes cannot build their Minecraft artifacts.
    maven("https://neoforged.forgecdn.net/releases/") {
        name = "NeoForgeCDN"
        // Same reason as the two repositories above: no log4j here, and a dynamic-version lookup
        // must not be sent to a host whose TLS handshake this network drops intermittently.
        content { excludeGroup("org.apache.logging.log4j") }
    }

}

dependencies {
    // Xaero's World Map is closed-source and on no Maven repo: compile against a shipped jar.
    // Every Xaero jar in libs/ belongs to exactly one Minecraft version, and their APIs are NOT
    // interchangeable across the eras -- a 1.21.11 XaeroLib names net.minecraft.render.MultiBufferSource
    // in its signatures, a class 26.x no longer has, so putting the whole directory on one node's
    // classpath makes javac fail on a type the node's own code never mentions. Select this node's
    // jars by name: "<version>" appears in both the world map and the XaeroLib file name.
    //
    // The jar in libs/ is the primary source and, for the 26.x nodes, the only one: this network
    // cannot fetch their Modrinth coordinates. The coordinate stays as the fallback for a checkout
    // that has not supplied a jar.
    val libs = rootProject.file("libs")
    val xaeroJar = fileTree(libs) { include("xaero-$minecraftVersion-*.jar") }
    if (xaeroJar.isEmpty) {
        compileOnly(xaeroCoordinate)
    } else {
        compileOnly(xaeroJar)
    }
    if (xaerolibJar.isNotEmpty()) {
        compileOnly(fileTree(libs) { include(xaerolibJar) })
    }
    // The Xaero's Minimap overlay's API jar, when this node supplied one: see the note above the
    // exclusion for why it is optional, compile-only and selected by name. XaeroLib, whose types the
    // minimap jar's signatures mention, is not added here -- the deps.xaerolib wiring above already
    // adds this node's extracted jar, and a second copy of the same classes says nothing new.
    minimapApiJar?.let { compileOnly(files(it)) }
    // The mcphone integration, when its jar has been supplied. Deliberately not version-gated:
    // the exclusion above keys off the jar's presence, which is the only thing that matters.
    compileOnly(fileTree(libs) { include("mcphone-*.jar") })
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = mapOf(
        "minecraft_version" to minecraftVersion,
        "neoforge_version" to neoForgeVersion,
        "mod_version" to modVersion,
        // The dependency ranges and the loader/Xaero entries the mod declares. Computed above rather
        // than written into the template, because the template is shared by every node and a literal
        // there is right on exactly one of them -- which is how the 1.20.6 and 1.20.4 nodes came to
        // reject their own jars over a loader version and a metadata file name.
        "minecraft_range" to mcRange,
        "neoforge_range" to neoRange,
        "loader_range" to loaderRange,
        "xaero_range" to xaeroRange,
        "xaerolib_type" to xaerolibType,
    )
    inputs.properties(replaceProperties)
    // MIT requires the notice to travel with every copy, and the copy people receive is the jar.
    from(rootProject.file("LICENSE")) { into("META-INF") }
    filesMatching(listOf("META-INF/neoforge.mods.toml")) { expand(replaceProperties) }
    // FML 2 reads META-INF/mods.toml and would ignore this jar otherwise; see legacyModsTomlName.
    // Applies to the whole copy and filters on the file name, rather than sitting inside the
    // filesMatching spec above: doing it there renamed the wrong entry (the LICENSE) on the one node
    // that takes this branch, which only showed up by running the task and listing what it produced.
    eachFile {
        if (legacyModsTomlName && name == "neoforge.mods.toml") {
            path = "META-INF/mods.toml"
        }
    }

    // The data pack, assembled for this node. Two independent things about it changed under this
    // tree, at two different versions, and neither can be expressed inside the JSON:
    //
    //   1.20.1 .. 1.20.6   data/howtogo/recipes/     + advancements/
    //   1.21.1 and later   data/howtogo/recipe/      + advancement/
    //
    //   1.20.1 .. 1.21.1   "I": {"item": "minecraft:iron_ingot"}
    //   1.21.4 and later   "I": "minecraft:iron_ingot"
    //
    // The ingredient one is a codec swap, not a spelling: 1.21.1's Ingredient parses
    // either(TagKey, Item) and rejects a bare string, 1.21.4's uses HolderSetCodec and rejects the
    // object. Shipping either form to the other era is a parse failure at world load, which is how
    // this surfaced -- "Failed to parse howtogo:navigator from pack howtogo", every recipe in the
    // registry erroring out, and the world never finishing its load.
    //
    // 1.20.5 also renamed the result's "item" to "id", which is why there are three recipe files
    // and not two. Stonecutter does not touch resources -- a //? in the JSON comes out of
    // stonecutterGenerate verbatim -- so the choice is made here and the files live outside
    // src/main/resources, where nothing copies them by accident.
    val recipeEra = when {
        stonecutter.eval(minecraftVersion, ">=1.21.4") -> "recipe-modern"
        stonecutter.eval(minecraftVersion, ">=1.20.5") -> "recipe-mid"
        else -> "recipe-legacy"
    }
    val singular = stonecutter.eval(minecraftVersion, ">=1.21.1")
    from(rootProject.file("src/main/datapack/$recipeEra")) {
        into("data/howtogo/" + if (singular) "recipe" else "recipes")
    }
    from(rootProject.file("src/main/datapack/advancement")) {
        into("data/howtogo/" + if (singular) "advancement/recipes" else "advancements/recipes")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Gradle 9 fails the build on this rather than warning: createMinecraftArtifacts reads
// stonecutterGenerate's output -- it compiles the node's generated sources -- without declaring
// the dependency, so the two could run in either order. Declaring it is the fix the message asks
// for, and it is true: the artifacts are built from the generated tree.
tasks.matching { it.name == "createMinecraftArtifacts" }.configureEach {
    dependsOn(tasks.matching { it.name == "stonecutterGenerate" })
}
