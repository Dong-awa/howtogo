// The Fabric build script: applied once per fabric node, with that node's
// versions/<name>/gradle.properties in scope. Everything here is either shared by all fabric
// nodes or branched on the node's Minecraft version.
//
// Ported from the standalone 1.20.1-fabric branch, whose build.gradle did the same job for a
// single version. The differences are the ones that make it a Stonecutter node rather than a
// checkout: the versions come from the node instead of gradle.properties, and the source comes
// from the shared tree instead of a src/ of its own.
//
// Why Loom rather than anything else: it is the only Fabric toolchain, and it is also the only
// one of the three this tree uses that runs on both Gradle 8 and Gradle 9. It was the Forge
// side that forced Gradle 8 on the whole tree; see MIGRATION.md.

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Declares this node's loader for the //? if fabric conditions in the shared source.
// Declares the loader this node builds for, for the `//? if <loader>` conditions in the shared
// source. All three are declared on every node, not just the true one: a condition naming a
// constant that was never declared is an error ("Unresolved constant") rather than a false, so a
// neoforge node still has to be told it is not fabric.
//
// Set here rather than in stonecutter.gradle.kts because that block is shared by every node, and a
// constant set there would be true on all of them -- which would compile the NeoForge half of
// every loader condition into the fabric jars.
stonecutter.const("neoforge", false)
stonecutter.const("fabric", true)
stonecutter.const("forge", false)

plugins {
    id("fabric-loom")
    id("java")
}

val minecraftVersion = stonecutter.current.version

// Loom's own version is NOT read here: a plugins block is evaluated before this file's
// properties are in scope, so it is declared in settings.gradle.kts pluginManagement instead.
val loaderVersion = property("deps.fabric_loader") as String
val fabricApiVersion = property("deps.fabric_api") as String
// The per-node Modrinth coordinate is the fallback when libs/ has no matching Xaero jar.
val xaeroCoordinate = property("deps.xaero") as String
// Fabric VersionPredicate syntax, not Maven range syntax. The 1.39.x nodes override this.
val xaeroRange = findProperty("deps.xaero_range")?.toString() ?: ">=1.40.0"
val modVersion = rootProject.property("mod_version") as String

version = "$modVersion+$minecraftVersion"
group = rootProject.property("maven_group") as String
base.archivesName = "${rootProject.property("archives_name") as String}-fabric-$minecraftVersion"

// 1.20.x is a Java 17 game, 1.21.x is 21, and 26.x is 25. Same table as the NeoForge script,
// and the same reason it is computed rather than written down: one build script serves them all.
val requiredJava = when {
    stonecutter.eval(minecraftVersion, ">=26.1") -> JavaVersion.VERSION_25
    stonecutter.eval(minecraftVersion, ">=1.20.5") -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}
java.toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)

repositories {
    // Maven Central through a mirror first, for the same network reason as the NeoForge script:
    // Loom also pulls natives and mapping artifacts through Central, and the direct host's TLS
    // handshake is intermittent here.
    maven("https://maven.aliyun.com/repository/central") { name = "mavenCentralMirror" }
    mavenCentral()
    // Corrected copies of artifacts whose UPSTREAM publication is broken, consulted before the
    // repository that publishes them. Restricted by content to the one module it fixes, so it
    // cannot shadow anything else, and Gradle falls through to the next repository for any version
    // this one does not carry -- which is why the nodes whose intermediary is published correctly
    // are unaffected.
    //
    // What it fixes: from 26.1 on, Fabric's intermediary POM declares version 0.0.0, and Gradle
    // refuses it -- "inconsistent module metadata found. Descriptor: net.fabricmc:intermediary:
    // 0.0.0, bad version: expected='26.1.2' found='0.0.0'". The JAR is fine; it is 572 bytes of
    // empty mapping set, which is exactly right because those Minecraft jars are not obfuscated.
    maven {
        name = "correctedArtifacts"
        url = uri(providers.gradleProperty("htgLocalMaven").getOrElse("${rootProject}/.recon/maven-local"))
        content { includeModule("net.fabricmc", "intermediary") }
    }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    // Xaero's own developer Maven. The shipped jar works too and is what libs/ holds.
    maven("https://chocolateminecraft.com/maven") { name = "ChocolateMinecraft" }
    // Fallback for checkouts without a matching libs/ Xaero jar. Loom remaps this
    // Modrinth artifact exactly as it remaps the local jar.
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
    flatDir { dirs(rootProject.file("libs")) }
}

// The Minecraft artifact version, hoisted out of the dependency block below so the minimap gate can
// select its jar by the same version. The node's NAME is the logical version; the version it
// compiles against can be a patch of it -- the 26.1 node's is 26.1.2 -- and a NeoForge node gets
// this for free, because ModDevGradle derives the Minecraft version from the NeoForge version it is
// given; a Fabric node has to be told, so deps.minecraft_version overrides it where the two differ.
val minecraftArtifactVersion = findProperty("deps.minecraft_version")?.toString() ?: minecraftVersion

// The Xaero's Minimap navigation overlay compiles against the minimap's element-render API. That is
// an abstract class to subclass, not an interface to reflect over, so this integration cannot be
// reached the way MTR, Create and RoadWeaver are: the overlay's own sources can only be compiled on
// a node whose libs/ holds a minimap jar for that node's Minecraft generation.
//
// So the jar's presence decides what is compiled, exactly as it does for the NeoForge nodes: with a
// jar the package is compiled against it (see the dependency block), without one it is excluded from
// the source set and the node builds unchanged. What such a node loses is exactly one feature -- the
// map_display_mode=xaero_minimap overlay -- and not silently: the display-mode router is an
// always-compiled reflective bridge that notices the missing class and falls back to the in-HUD map
// with one log line explaining why.
//
// Deliberately NOT a declared or hard dependency, unlike Xaero's World Map, which every road and
// route already renders through; the minimap is only an alternative place to draw the same route.
//
// Selected by this node's Minecraft version -- the file name carries it -- and recognised by the
// class the overlay must subclass rather than by file name alone, so a stray jar from another
// generation is treated as absent instead of failing javac with a version mismatch. Xaero names its
// Fabric jars two ways; only the loader-first one is the minimap's spelling, so only it is accepted.
val minimapApiJar = fileTree(rootProject.file("libs")) {
    include("xaerominimap-fabric-$minecraftArtifactVersion-*.jar")
}.find { f ->
    try {
        // ZipFile, not java.util.zip.ZipFile: in a Kotlin build script `java` is the Java plugin
        // extension and shadows the package root.
        ZipFile(f).getEntry("xaero/hud/minimap/element/render/MinimapElementRenderer.class") != null
    } catch (ignored: Exception) {
        false
    }
}

if (minimapApiJar == null) {
    sourceSets.main.get().java.exclude("**/xaero/minimap/**")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftArtifactVersion")
    // Official Mojang mappings, NOT Yarn. The whole codebase is written against net.minecraft.*
    // official names -- 33k lines of it, shared with the NeoForge and Forge nodes -- so moving to
    // Yarn would be a rename pass with no functional gain, and would be the one thing that made
    // the loaders unable to share a source tree at all. Loom remaps Fabric API and mod jars to
    // these names for us.
    // From 26.1 the Minecraft jar is NOT obfuscated -- 10208 of its 10682 classes already live under
// net/minecraft/ -- and Mojang publishes no client_mappings for those versions at all. That is not
// an obstacle, it is the reason no mappings are needed: the NeoForm config for 26.1.2 has no
// mappings step either, it decompiles and patches directly. So an identity mapping is the correct
// description of those jars rather than a workaround for a missing file.
//
// The identity mappings are a Tiny v2 header with no entries, inside a jar because that is what
// Loom reads mappings from. It says the names are already right.
if (stonecutter.eval(minecraftVersion, ">=26.1")) {
    mappings(files(rootProject.file("src/fabric/aw/identity-mappings.jar")))
} else {
    mappings(loom.officialMojangMappings())
}

    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion") {
    // fabric-content-registries-v0 ships javadoc in a namespace Loom 1.10.5 cannot read, and its
    // presence fails the whole "setup Minecraft" step on 1.21.11. This mod uses none of it: the
    // Fabric API surface it needs is key bindings, lifecycle events, rendering, screen events,
    // client commands and item groups. Excluded rather than pinned to an older API, because the
    // exclusion names the one thing that is in the way instead of moving everything back.
    exclude(group = "net.fabricmc.fabric-api", module = "fabric-content-registries-v0")
}

    // Xaero's World Map, as the FABRIC build of it -- the NeoForge jar is a different artifact
    // and its classes will not load here. Only the world map is a compile input from the
    // always-compiled source: MTR, Create and Xaero's Minimap are reached by reflection from it, so
    // they are runtime mods and deliberately absent. Xaero's Minimap's one non-reflective piece --
    // the route overlay, which has to subclass its element renderer -- is compiled only when this
    // node supplied its jar, as the gate above says.
    //
    // Loom remaps this jar to the official names on the way in, which is why the file name does
    // not have to say so; see libs/README.md.
    // From 26.2 on Xaero ships in two parts and the second one -- XaeroLib, which owns the
    // rendering API this mod compiles against -- is a separate jar in libs/ rather than being
    // bundled. Named by property so the nodes that do not need it say nothing; the NeoForge
    // script does the same.
    val xaerolib = findProperty("deps.xaerolib")?.toString().orEmpty()
    if (xaerolib.isNotEmpty()) {
        modCompileOnly(fileTree(rootProject.file("libs")) { include(xaerolib) })
    }
    val xaeroJar = fileTree(rootProject.file("libs")) {
        // Xaero names its Fabric jars two ways: "xaeroworldmap-fabric-<mc>-<ver>.jar" from
        // 1.21.8 on, and "XaerosWorldMap_<ver>_Fabric_<mc>.jar" before that. Both are accepted
        // rather than renaming the download.
        include("xaeroworldmap-fabric-$minecraftArtifactVersion-*.jar",
                "XaerosWorldMap_*_Fabric_$minecraftArtifactVersion.jar")
    }
    if (xaeroJar.isEmpty) {
        // No local jar: resolve the node's Modrinth coordinate. Loom remaps it for us.
        modCompileOnly(xaeroCoordinate)
    } else {
        modCompileOnly(xaeroJar)
    }

    // The Xaero's Minimap overlay's API jar, when this node supplied one: see the note above the
    // exclusion for why it is optional, compile-only and selected by name. XaeroLib, whose types the
    // minimap jar's signatures mention, is not added here -- the deps.xaerolib wiring just above
    // already adds this node's extracted jar, and a second copy of the same classes says nothing new.
    minimapApiJar?.let { modCompileOnly(files(it)) }
}

// ---------------------------------------------------------------------------------------------
// 26.x: the runtime Fabric API is the release jar, not the one Loom remaps
//
// On every other node Loom's dev jars of Fabric API are the right thing to run against: Loom remaps
// them to the mapping target namespace, which is the namespace the dev client runs in.
//
// The 26.x nodes break that equivalence, and nothing in this project can repair it from here. Their
// Minecraft jars are not obfuscated, so the identity mappings this build uses record no entries at
// all -- and fabric-loader decides the dev runtime namespace from the mappings it is handed:
// `MappingConfiguration.computeRuntimeNamespace` answers `named` only when the mapping set is
// non-empty and the game is running in a development environment, and `official` otherwise. An empty
// identity mapping is the second case, so the client runs in `official` while Loom rewrote every dev
// jar's class tweaker header to the mapping target, `named`. The loader then refuses to start:
//
//   ClassTweakerFormatException: Namespace (named) does not match current runtime namespace (official)
//
// The failing files belong to Fabric API's own modules (fabric-transitive-access-wideners-v1 on 26.1,
// fabric-renderer-indigo on 26.3, and whoever else the release decides to widen), so this mod's own
// access widener cannot fix it -- that one is already delivered with an `official` header below.
//
// What does work is the shape a real launcher has: the PRODUCTION Fabric API in the run directory's
// mods/ folder, where fabric-loader treats it as an ordinary third-party mod and remaps it -- class
// tweakers included -- to the namespace the runtime is actually in. So the dev-remapped API stays as
// the compile input and is excluded from the runtime classpath, and the release jar is copied into
// run/mods before the client starts. Verified end to end on all three 26.x nodes: the audit runs in
// build/audit passed their in-game self-test (9-10 checks each) with exactly this arrangement.
val productionFabricApi by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    // The release jar bundles its modules inside the jar, so nothing has to come along with it.
    isTransitive = false
}
dependencies {
    add(productionFabricApi.name, "net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}
val copyProductionFabricApi = tasks.register<Copy>("copyProductionFabricApi") {
    group = "fabric"
    description = "Puts the release Fabric API into this node's run/mods, where a 26.x client needs it."
    // Only the 26.x nodes run it: everywhere else the dev jar is correct and a second copy of the mod
    // in run/mods would be a duplicate mod id.
    onlyIf { stonecutter.eval(minecraftVersion, ">=26.1") }
    val runMods = layout.projectDirectory.dir("run/mods").asFile
    // One fabric-api jar only. The run directory is machine-local and may already hold a copy -- from
    // the release the user downloaded by hand, or from an earlier wiring of this task -- and the loader
    // refuses to start with two copies of the same mod id.
    doFirst {
        runMods.mkdirs()
        runMods.listFiles { f -> f.isFile && f.name.startsWith("fabric-api") && f.name.endsWith(".jar") }
            ?.forEach { stale ->
                if (stale.delete()) {
                    logger.lifecycle("run/mods: replaced ${stale.name} with the release Fabric API")
                }
            }
    }
    from(productionFabricApi) {
        include("*.jar")
        rename { "fabric-api-$fabricApiVersion.jar" }
    }
    into(runMods)
}

// The two halves of that decision, applied to the runtime classpath: the dev-remapped modules go,
// and the release jar reaches the run directory. Excluding them by group is what the audit runs used
// (build/audit/fabric-prodapi-generic.gradle); the group is Loom's, not Fabric's.
if (stonecutter.eval(minecraftVersion, ">=26.1")) {
    configurations.configureEach {
        if (name.startsWith("modRuntimeClasspath")) {
            exclude(group = "remapped.net.fabricmc.fabric-api")
        }
    }
}

// The access widener is chosen per version generation, because an entry naming a member that does
// not exist on the node being built is a build failure rather than a no-op:
//
//   legacy (< 1.21.6)  RenderType.CompositeState is protected there, and javac enforces that flag,
//                      so the HUD's own render type cannot be built without widening it.
//   modern (>= 1.21.6) GuiGraphics.submitGuiElementRenderState, which is NeoForge-added and that
//                      HudBatchLegacy and HudBatchNewer both need.
//
// The two do not overlap, so two files cover the range. The chosen one is renamed to
// howtogo.accesswidener, which is the name fabric.mod.json asks for; the templates themselves are
// excluded from the jar.
// Three generations, not two: the 26.x nodes need the same members as modern, but their jars are
// unobfuscated and the runtime namespace is official; the post-processing below rewrites the
// delivered copy to an official header for Fabric Loader.
val awGeneration = when {
    stonecutter.eval(minecraftVersion, ">=26.1") -> "modern-intermediary"
    stonecutter.eval(minecraftVersion, ">=1.21.6") -> "modern"
    else -> "legacy"
}
// The templates live outside the resources directory on purpose. Inside it, an exclude meant to keep
// them out of the jar also matches the copy this adds back, because the exclude is tested against the
// source name before the rename -- which silently removed the access widener from the jar entirely.
val awTemplate = rootProject.file("src/fabric/aw/$awGeneration.accesswidener")
// Loom needs a `named` header to remap the 26.x widener, but the runtime namespace is
// `official`. Keep the Loom input named and rewrite only the delivered/dev copies.

// Computed OUTSIDE the loom block on purpose. Written inline it did not compile:
// "Unresolved reference 'not' for operator '!'" -- the loom extension shadows `stonecutter`
// inside its own scope, so the negation has to happen here where the plugin's is in view.

// Loom needs the widener header to be `named` while it remaps, but 26.x runs in the
// official namespace. Keep the build input named and rewrite only the delivered/dev copies.
fun patchOfficialAccessWidener(file: File) {
    if (!file.isFile) return
    val tmp = File(file.parentFile, file.name + ".aw")
    ZipFile(file).use { zin ->
        ZipOutputStream(tmp.outputStream()).use { out ->
            val entries = zin.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val bytes = zin.getInputStream(entry).use { it.readBytes() }
                val patched = if (entry.name == "howtogo.accesswidener") {
                    String(bytes, Charsets.UTF_8)
                        .replaceFirst("accessWidener v2 named", "accessWidener v2 official")
                        .toByteArray(Charsets.UTF_8)
                } else {
                    bytes
                }
                val copy = ZipEntry(entry.name)
                copy.time = entry.time
                out.putNextEntry(copy)
                out.write(patched)
                out.closeEntry()
            }
        }
    }
    tmp.copyTo(file, overwrite = true)
    tmp.delete()
}
val needsAccessWidener = true

loom {
    // No access widener on 26.x. Loom refuses to apply one there at all:
    //
    //   Cannot remap access widener from namespace 'official'. Expected: 'intermediary'
    //
    // which is what an identity mapping does to the remap -- those jars are not obfuscated, so
    // the two namespaces are the same and there is nothing to remap from. It fails before the
    // file's contents are read, so an empty widener fails identically.
    if (needsAccessWidener) {
        accessWidenerPath = awTemplate
    }
    // The in-game test rig opens a world for the player so the mod's own screens can be tried without
    // walking the menus first -- the same switch build.neoforge.gradle.kts offers, because the rig is
    // the same script for every node. Loom's run config is where the argument has to go: Gradle's
    // --args would replace the launcher's own arguments rather than add to them.
    if (rootProject.hasProperty("htgQuickPlay")) {
        runs.named("client") {
            programArgs("--quickPlaySingleplayer", rootProject.property("htgQuickPlay").toString())
        }
    }
}

// The shared tree, exactly as the NeoForge nodes take it: src/common is loader-free and
// version-free, src/main knows which Minecraft and which loader it runs on. Stonecutter has
// already turned the `//?` markers in src/main into this node's state by the time javac sees it.
// Loader metadata lives outside src/main on purpose. fabric.mod.json, the mixin config and the
// access widener are read by Fabric and by nothing else, so putting them in the shared resources
// would ship them inside the NeoForge and Forge jars as well -- harmless, but it invites the
// question of which loader a jar is for when the answer should be obvious from its contents.
//
// Both shared directories are copied into the node's own build directory unless this is the
// active node, for the same IntelliJ content-root reason spelled out in build.neoforge.gradle.kts:
// a directory registered by every node's module is de-duplicated down to one module by the IDE,
// which leaves the active node without it. Copies are unique per node, so nothing is de-duplicated.
sourceSets {
    main {
        if (stonecutter.current.isActive) {
            java.srcDir(rootProject.file("src/common/java"))
            resources.srcDir(rootProject.file("src/fabric/resources"))
        } else {
            val commonCopy = layout.buildDirectory.dir("generated/common/java")
            val syncCommon = tasks.register<Sync>("syncCommonSources") {
                from(rootProject.file("src/common/java"))
                into(commonCopy)
            }
            java.srcDir(commonCopy)
            tasks.named<JavaCompile>("compileJava") { dependsOn(syncCommon) }

            val fabricResourcesCopy = layout.buildDirectory.dir("generated/fabric-resources")
            val syncFabricResources = tasks.register<Sync>("syncFabricResources") {
                from(rootProject.file("src/fabric/resources"))
                into(fabricResourcesCopy)
            }
            resources.srcDir(fabricResourcesCopy)
            tasks.named<ProcessResources>("processResources") { dependsOn(syncFabricResources) }
        }
    }
}

// No MCPhone integration on Fabric. The phone mod is NeoForge-only, so the two classes that
// implement the "问道导航" app have nothing to register against here, and the Fabric branch this
// tree absorbed never had them at all. Excluded rather than conditioned: there is no version or
// loader combination on which a fabric node would want them, so a condition would be a branch
// that is false everywhere.
//
// The NeoForge script excludes these too, but conditionally -- only when the phone API jar is
// absent from libs/, because a NeoForge user may supply it. Fabric has no such case.
// The NeoForge mod metadata lives in src/main/resources, which every node shares, so the fabric
// jars would otherwise carry a neoforge.mods.toml. Fabric ignores it, but a jar should say what it
// is for: the loader metadata belongs to the loader. The reverse cannot happen -- fabric.mod.json
// and the mixin config are in src/fabric/resources, which no NeoForge node compiles.
sourceSets.main.get().java.exclude("**/compat/mcphone/**")
sourceSets.main.get().resources.exclude("META-INF/services/com.november.mcphone.api.client.app.IPhoneApp")

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // javac stops at 100 errors by default, which hides the shape of a port. The NeoForge script
    // raises it for the same reason.
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "100000"))
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = mapOf(
        "version" to version.toString(),
        "minecraft_version" to minecraftVersion,
        "java_version" to requiredJava.majorVersion.toString(),
        "xaero_range" to xaeroRange,
        // The accessWidener key is templated because 26.x must not declare one at all. Loom reads
        // this key independently of accessWidenerPath, so leaving it out of the loom block was not
        // enough -- the widener was still being applied, and still failing the same remap.
        // An empty string leaves a blank line, which JSON allows.
        "access_widener_entry" to if (needsAccessWidener) {
            "\"accessWidener\": \"howtogo.accesswidener\","
        } else {
            ""
        },
        // The ranges this jar declares. The bounds are the same policy as the NeoForge script's --
        // one Minecraft release, upper bound closed on purpose -- but the SYNTAX is not, and that
        // difference is the whole reason this is not shared code.
        //
        // NeoForge takes Maven range notation: "[1.21.11,1.21.12)". Fabric's VersionPredicate takes
        // space-separated comparators: ">=1.21.11 <1.21.12". Written the Maven way, Fabric does not
        // reject the jar -- it evaluates the predicate to FALSE for every game version, so the mod is
        // reported as incompatible with the very release it was built for. Measured against
        // fabric-loader's own VersionPredicate: "[1.20.1,1.20.2)" is false for 1.20.1, and
        // "[1.21.11,1.21.12)" is false for 1.21.11. The comparator form is true for the named version
        // and false for the one before and the one after, which is what the range is for.
        "minecraft_range" to run {
            val parts = minecraftVersion.split(".")
            val next = (parts.dropLast(1) + (parts.last().toInt() + 1).toString()).joinToString(".")
            ">=$minecraftVersion <$next"
        },
    )
    // The NeoForge mod metadata lives in src/main/resources, which every node shares, so without this
    // the fabric jars carry a neoforge.mods.toml. Fabric ignores it, but a jar should say what it is
    // for. Excluded on the task rather than on the source set: the source-set exclusion was written
    // first and had no effect, because the default resources directory is wired into this task before
    // that filter is consulted.
    exclude("META-INF/neoforge.mods.toml")
    // The access widener templates are build inputs, not jar contents: the chosen one is added below
    // under the name fabric.mod.json asks for, and neither template belongs in the jar.
    from(awTemplate) { rename { "howtogo.accesswidener" } }
    inputs.properties(replaceProperties)
    filesMatching(listOf("fabric.mod.json")) { expand(replaceProperties) }
    // MIT requires the notice to travel with every copy, and the copy people receive is the jar.
    from(rootProject.file("LICENSE")) { into("META-INF") }

    // No data-pack copy here: src/main/datapack does not exist; the jars deliberately contain no data/**.
}

if (stonecutter.eval(minecraftVersion, ">=26.1")) {
    tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        doLast { patchOfficialAccessWidener(archiveFile.get().asFile) }
    }
    tasks.named("runClient") {
        // The release Fabric API in run/mods is what the runtime loads on this node; see the
        // productionFabricApi block above for why the dev-remapped modules cannot be used.
        dependsOn(copyProductionFabricApi)
        doFirst {
            val aw = file("build/resources/main/howtogo.accesswidener")
            if (aw.isFile) {
                aw.writeText(aw.readText().replaceFirst("accessWidener v2 named", "accessWidener v2 official"))
            }
        }
    }
}
