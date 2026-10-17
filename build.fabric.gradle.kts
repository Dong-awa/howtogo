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
    flatDir { dirs(rootProject.file("libs")) }
}

dependencies {
    // The node's NAME is the logical version; the Minecraft version it compiles against can be a
// patch of it -- the 26.1 node's is 26.1.2. A NeoForge node gets this for free, because
// ModDevGradle derives the Minecraft version from the NeoForge version it is given; a Fabric node
// has to be told, so deps.minecraft_version overrides it where the two differ.
val minecraftArtifactVersion = findProperty("deps.minecraft_version")?.toString() ?: minecraftVersion
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
    // and its classes will not load here. Only the world map is a compile input: MTR, Create and
    // Xaero's Minimap are reached by reflection, so they are runtime mods and deliberately absent.
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
    modCompileOnly(fileTree(rootProject.file("libs")) { // Xaero names its Fabric jars two ways: "xaeroworldmap-fabric-<mc>-<ver>.jar" from 1.21.8 on, and
    // "XaerosWorldMap_<ver>_Fabric_<mc>.jar" before that. Both are accepted rather than renaming the
    // download, because a local file whose name is not the one upstream publishes is a trap for
    // whoever has to replace it.
    include("xaeroworldmap-fabric-$minecraftArtifactVersion-*.jar",
            "XaerosWorldMap_*_Fabric_$minecraftArtifactVersion.jar") })
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
// Three generations, not two: the 26.x nodes need the same members as modern but declared in the
// intermediary namespace, because their jars are unobfuscated and Loom will not remap a "named"
// widener onto an identity mapping.
val awGeneration = when {
    stonecutter.eval(minecraftVersion, ">=26.1") -> "modern-intermediary"
    stonecutter.eval(minecraftVersion, ">=1.21.6") -> "modern"
    else -> "legacy"
}
// The templates live outside the resources directory on purpose. Inside it, an exclude meant to keep
// them out of the jar also matches the copy this adds back, because the exclude is tested against the
// source name before the rename -- which silently removed the access widener from the jar entirely.
val awTemplate = rootProject.file("src/fabric/aw/$awGeneration.accesswidener")
// Computed OUTSIDE the loom block on purpose. Written inline it did not compile:
// "Unresolved reference 'not' for operator '!'" -- the loom extension shadows `stonecutter`
// inside its own scope, so the negation has to happen here where the plugin's is in view.
val needsAccessWidener = !stonecutter.eval(minecraftVersion, ">=26.1")

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
}

// The shared tree, exactly as the NeoForge nodes take it: src/common is loader-free and
// version-free, src/main knows which Minecraft and which loader it runs on. Stonecutter has
// already turned the `//?` markers in src/main into this node's state by the time javac sees it.
// Loader metadata lives outside src/main on purpose. fabric.mod.json, the mixin config and the
// access widener are read by Fabric and by nothing else, so putting them in the shared resources
// would ship them inside the NeoForge and Forge jars as well -- harmless, but it invites the
// question of which loader a jar is for when the answer should be obvious from its contents.
sourceSets {
    main {
        java.srcDir(rootProject.file("src/common/java"))
        resources.srcDir(rootProject.file("src/fabric/resources"))
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
        // The accessWidener key is templated because 26.x must not declare one at all. Loom reads
        // this key independently of accessWidenerPath, so leaving it out of the loom block was not
        // enough -- the widener was still being applied, and still failing the same remap.
        // An empty string leaves a blank line, which JSON allows.
        "access_widener_entry" to if (needsAccessWidener) {
            "\"accessWidener\": \"howtogo.accesswidener\","
        } else {
            ""
        },
        // The ranges this jar declares, computed the same way the NeoForge script computes its
        // own -- a literal in a shared resource file is right on exactly one node. See that
        // script for the full reasoning; the short version is that the upper bound is closed on
        // purpose, because an open-ended range claims compatibility with releases nothing here
        // has been run against.
        "minecraft_range" to run {
            val parts = minecraftVersion.split(".")
            val next = (parts.dropLast(1) + (parts.last().toInt() + 1).toString()).joinToString(".")
            "[$minecraftVersion,$next)"
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
}
