# Throws away every build cache for this checkout, then verifies the branch builds from cold.
#
# Why this exists: `.gradle/`, `build/` and `run/` are gitignored, so switching branches leaves them
# behind. Each branch is a different Minecraft, a different loader and -- on the Fabric branches --
# a different mapping namespace, and Loom keys its cached artifacts by a hash of the mappings rather
# than by the branch. Switching between them therefore accumulates several sets of cached Minecraft
# jars and remapped mods in one directory, and a stale entry can be picked up for the wrong target.
# The failure it produces is "Cannot remap access widener from namespace 'official'. Expected:
# 'intermediary'" during Loom's Minecraft setup -- which looks like a source problem and is not one.
#
# The project-local caches are cleared first because they are the ones that actually went stale; the
# global Loom store is cleared only with -Deep, because it is shared with every other Loom project on
# the machine and re-downloading it costs minutes.
#
#   usage: .\tools\reset-caches.ps1 [-Deep] [-NoBuild]
#
#   -Deep     also clear the global Loom store for this branch's Minecraft version and the build cache
#   -NoBuild  clear the caches but do not rebuild afterwards

param(
    [switch]$Deep,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Continue'
$projectDir = Split-Path -Parent $PSScriptRoot
Push-Location $projectDir
try {
    $branch = (& git rev-parse --abbrev-ref HEAD).Trim()
    Write-Host "branch: $branch"

    # The branch's declared Minecraft version, so -Deep clears the right global store.
    $minecraft = (Select-String -Path 'gradle.properties' -Pattern '^\s*minecraft_version\s*=\s*(.+)$' |
        Select-Object -First 1).Matches[0].Groups[1].Value.Trim()
    Write-Host "minecraft: $minecraft"

    function Remove-Cache([string]$path, [string]$label) {
        if (Test-Path $path) {
            $mb = [math]::Round((Get-ChildItem $path -Recurse -File -ErrorAction SilentlyContinue |
                Measure-Object Length -Sum).Sum / 1MB, 1)
            Remove-Item -Recurse -Force $path -ErrorAction SilentlyContinue
            Write-Host "  removed $label ($mb MB)"
        }
    }

    Write-Host "project-local caches (these survive every branch switch):"
    Remove-Cache (Join-Path $projectDir '.gradle') 'project .gradle/'
    Remove-Cache (Join-Path $projectDir 'build') 'build/'

    if ($Deep) {
        Write-Host 'global caches (shared with other Loom projects on this machine):'
        $loomStore = Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\$minecraft"
        Remove-Cache $loomStore "fabric-loom store for $minecraft"
        Remove-Cache (Join-Path $env:USERPROFILE '.gradle\caches\build-cache-1') 'gradle build cache'
    }

    # A removed .gradle/ takes Gradle's own file-system watcher state with it, which is the point.
    if (-not $NoBuild) {
        Write-Host ''
        Write-Host 'rebuilding from cold...'
        & (Join-Path $projectDir 'gradlew.bat') build --console=plain --no-build-cache
        if ($LASTEXITCODE -ne 0) {
            Write-Host ''
            Write-Host 'BUILD FAILED after a clean cache reset. If it is still the access widener error,'
            Write-Host 'the stale entry is not in this checkout -- re-run with -Deep.'
            exit 1
        }
        Write-Host ''
        Write-Host 'cold build OK'
    }
}
finally {
    Pop-Location
}
