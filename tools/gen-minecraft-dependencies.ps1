# Generates net.neoforged:minecraft-dependencies:<mc> from a NeoForm config, into the workspace
# mirror.
#
# Why this exists: ModDevGradle asks for that artifact per Minecraft version, and the mirror has it
# for 1.21.1, 1.21.4, 1.21.6, 1.21.8, 1.21.11, 26.1.2, 26.2 and 26.3 -- every node that builds --
# and for no 1.20.x. Neither the CDN nor bmclapi publishes the group at all, so the 1.20.x nodes
# cannot resolve it and their Minecraft artifacts never get built.
#
# The NeoForm config for a version carries the answer: a `libraries` object with `client`, `server`
# and `joined` arrays of plain "group:artifact:version" strings. That is exactly what the module
# metadata describes, split into variants.

param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string[]]$Versions
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

foreach ($mc in $Versions) {
    # Find this version's NeoForm config in the Gradle cache. The file name carries a timestamp,
    # so match on the version prefix rather than an exact name.
    $zipFile = Get-ChildItem "$Root\.gradle-home\caches" -Recurse -Filter "neoform-$mc-*.zip" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $zipFile) { throw "no neoform config cached for $mc" }

    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipFile.FullName)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -match 'config\.json$' } | Select-Object -First 1
        if (-not $entry) { throw "no config.json in $($zipFile.Name)" }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $config = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Close() }
    } finally { $zip.Dispose() }

    # The NeoForm config's own `libraries` key is NOT the Minecraft library set. For 1.20.4 it
    # holds three entries -- jsr305, java-objc-bridge, annotations -- because the Minecraft libraries
    # are downloaded later by the runtime's listLibraries step rather than declared there. Using it
    # produced a module with three dependencies, and the node then failed on org.lwjgl.glfw, org.joml
    # and com.mojang.text2speech not being on the classpath at all.
    #
    # The real set is in Mojang's version JSON, so that is what this reads. Rules are ignored on
    # purpose: this is a COMPILE classpath, and an extra jar is harmless while a missing one is a
    # build failure. Natives are skipped -- they carry a classifier and nothing compiles against them.
    $manifest = Invoke-RestMethod -Uri 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json' -TimeoutSec 60
    $versionEntry = $manifest.versions | Where-Object { $_.id -eq $mc } | Select-Object -First 1
    if (-not $versionEntry) { throw "$mc is not in Mojang's version manifest" }
    $versionJson = Invoke-RestMethod -Uri $versionEntry.url -TimeoutSec 60
    $allLibs = @()
    foreach ($lib in $versionJson.libraries) {
        # Two filters, both needed. `natives` marks a library whose jars are all classifier-
        # qualified, and `downloads.artifact` is absent for the same reason -- netty-transport-
        # native-epoll publishes only a linux-x86_64 jar, so asking for the bare coordinate fails
        # with "Could not find netty-transport-native-epoll-4.1.97.Final.jar". A library with a
        # real base jar always has downloads.artifact, including the ones that also ship natives
        # (lwjgl does), so this keeps those and drops only the natives-only entries.
        if ($lib.natives) { continue }
        # A classifier-qualified name -- "io.netty:netty-transport-native-epoll:4.1.97.Final:linux-aarch_64"
        # -- has four segments, and its jar is published under that classifier. Taking the first three
        # asks for a bare coordinate that does not exist, which is exactly how netty-transport-
        # native-epoll failed. Nothing compiles against a native, so they are dropped entirely.
        if (($lib.name -split ':').Count -gt 3) { continue }
        if (-not $lib.downloads -or -not $lib.downloads.artifact) { continue }
        if ($lib.name) { $allLibs += $lib.name }
    }
    if ($allLibs.Count -eq 0) { throw "no libraries in $mc's version json" }

    # The three arrays are already "group:artifact:version", which is what a module dependency
    # wants once split. joined is the union the NeoForm runtime uses when it merges client and
    # server; it is listed as its own thing here because the variants are per distribution.
    function ConvertTo-Deps($names) {
        $out = @()
        foreach ($n in $names) {
            $parts = $n.Split(':')
            if ($parts.Count -lt 3) { continue }
            $out += [ordered]@{
                # Everything transitive is excluded: the list is already flat and complete, and
                # letting Gradle walk the graph would pull a second copy of half of it.
                excludes = @([ordered]@{ module = '*'; group = '*' })
                group    = $parts[0]
                module   = $parts[1]
                version  = [ordered]@{ strictly = $parts[2] }
            }
        }
        return $out
    }

    $clientDeps = ConvertTo-Deps $allLibs
    $serverDeps = ConvertTo-Deps $allLibs
    $joinedDeps = ConvertTo-Deps $allLibs

    $jvm = $config.java_target
    if (-not $jvm) { $jvm = 17 }

    function Variant($name, $attrs, $deps) {
        return [ordered]@{
            name         = $name
            attributes   = $attrs
            dependencies = $deps
            files        = @()
        }
    }

    $variants = @(
        (Variant 'clientCompileDependencies' ([ordered]@{
            'net.neoforged.distribution' = 'client'; 'org.gradle.usage' = 'java-api'
            'org.gradle.jvm.version' = $jvm; 'org.gradle.category' = 'library'
        }) $clientDeps),
        (Variant 'clientRuntimeDependencies' ([ordered]@{
            'net.neoforged.distribution' = 'client'; 'org.gradle.usage' = 'java-runtime'
            'org.gradle.jvm.version' = $jvm; 'org.gradle.category' = 'library'
        }) $clientDeps),
        (Variant 'serverCompileDependencies' ([ordered]@{
            'net.neoforged.distribution' = 'server'; 'org.gradle.usage' = 'java-api'
            'org.gradle.jvm.version' = $jvm; 'org.gradle.category' = 'library'
        }) $serverDeps),
        (Variant 'serverRuntimeDependencies' ([ordered]@{
            'net.neoforged.distribution' = 'server'; 'org.gradle.usage' = 'java-runtime'
            'org.gradle.jvm.version' = $jvm; 'org.gradle.category' = 'library'
        }) $serverDeps),
        (Variant 'joinedCompileDependencies' ([ordered]@{
            'net.neoforged.distribution' = 'joined'; 'org.gradle.usage' = 'java-api'
            'org.gradle.jvm.version' = $jvm; 'org.gradle.category' = 'library'
        }) $joinedDeps),
        (Variant 'joinedRuntimeDependencies' ([ordered]@{
            'net.neoforged.distribution' = 'joined'; 'org.gradle.usage' = 'java-runtime'
            'org.gradle.jvm.version' = $jvm; 'org.gradle.category' = 'library'
        }) $joinedDeps),
        (Variant 'platformDependencies' ([ordered]@{
            'org.gradle.category' = 'platform'; 'org.gradle.usage' = 'java-api'
        }) @()),
        (Variant 'platformRuntimeDependencies' ([ordered]@{
            'org.gradle.category' = 'platform'; 'org.gradle.usage' = 'java-runtime'
        }) @())
    )

    $module = [ordered]@{
        formatVersion = '1.1'
        component     = [ordered]@{ module = 'minecraft-dependencies'; group = 'net.neoforged'; version = $mc }
        createdBy     = [ordered]@{ gradle = [ordered]@{ version = '8.14.6'; buildEnvironment = 'howtogo' } }
        variants      = $variants
    }

    $dir = "$Root\.recon\maven-local\net\neoforged\minecraft-dependencies\$mc"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    [System.IO.File]::WriteAllText("$dir\minecraft-dependencies-$mc.module", ($module | ConvertTo-Json -Depth 12))

    $pom = @(
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        '  <modelVersion>4.0.0</modelVersion>'
        '  <groupId>net.neoforged</groupId>'
        '  <artifactId>minecraft-dependencies</artifactId>'
        "  <version>$mc</version>"
        '</project>'
    ) -join "`r`n"
    [System.IO.File]::WriteAllText("$dir\minecraft-dependencies-$mc.pom", $pom)

    Write-Host ("  {0}: client {1} / server {2} / joined {3} 条" -f $mc, $clientDeps.Count, $serverDeps.Count, $joinedDeps.Count)
}
