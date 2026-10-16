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
    stonecutter.eval(minecraftVersion, ">=1.21.1") -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}
java.toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    // Xaero's own developer Maven. The shipped jar works too and is what libs/ holds.
    maven("https://chocolateminecraft.com/maven") { name = "ChocolateMinecraft" }
    flatDir { dirs(rootProject.file("libs")) }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    // Official Mojang mappings, NOT Yarn. The whole codebase is written against net.minecraft.*
    // official names -- 33k lines of it, shared with the NeoForge and Forge nodes -- so moving to
    // Yarn would be a rename pass with no functional gain, and would be the one thing that made
    // the loaders unable to share a source tree at all. Loom remaps Fabric API and mod jars to
    // these names for us.
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Xaero's World Map, as the FABRIC build of it -- the NeoForge jar is a different artifact
    // and its classes will not load here. Only the world map is a compile input: MTR, Create and
    // Xaero's Minimap are reached by reflection, so they are runtime mods and deliberately absent.
    //
    // Loom remaps this jar to the official names on the way in, which is why the file name does
    // not have to say so; see libs/README.md.
    modCompileOnly(fileTree(rootProject.file("libs")) { include("xaeroworldmap-fabric-$minecraftVersion-*.jar") })
}

// Minecraft 1.20.x marks RenderType.CompositeState protected inside RenderType, and that is the
// flag javac enforces, so the HUD's own render type cannot be built without widening it. 1.21.1
// made it public and needs no widener. The file is shared by every fabric node -- it is a no-op
// where it is not needed -- but the entry only means anything below 1.21.
loom {
    accessWidenerPath = rootProject.file("src/fabric/resources/howtogo.accesswidener")
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
    inputs.properties(replaceProperties)
    filesMatching(listOf("fabric.mod.json")) { expand(replaceProperties) }
    // MIT requires the notice to travel with every copy, and the copy people receive is the jar.
    from(rootProject.file("LICENSE")) { into("META-INF") }
}
