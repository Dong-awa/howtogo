# Pre-populates the NeoForm runtime's download cache from the CDN.
#
# Why this is needed: neoform-runtime does its own downloading and reads
# https://maven.neoforged.net/releases/ from a hardcoded constant inside its jar, not from the
# Gradle repositories. maven.neoforged.net is unreachable from here, so every library the runtime
# wants fails with a ConnectException -- one per run, because it stops at the first.
#
# The cache is caches/neoformruntime/artifacts/<maven path>, laid out like a Maven repository, so
# the fix is to put the files there ourselves from neoforged.forgecdn.net, which serves the same
# tree. This script loops: build, read the URL it failed on, fetch it, repeat.

param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string]$Node,
    [int]$MaxRounds = 30
)

$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'D:\java\zulu25'
$env:GRADLE_USER_HOME = "$Root\.gradle-home"
$gradle = 'D:\java\gradle-9.5.1\bin\gradle.bat'
$cache = "$Root\.gradle-home\caches\neoformruntime\artifacts"
$upstream = 'https://maven.neoforged.net/releases/'
$cdn = 'https://neoforged.forgecdn.net/releases/'

$fetched = 0
for ($round = 1; $round -le $MaxRounds; $round++) {
    $log = & $gradle --console=plain -p $Root "-PhtgLocalMaven=$Root/.recon/maven-local" ":$Node:createMinecraftArtifacts" '--info' '--rerun-tasks' 2>&1
    if ($log | Select-String -Pattern '^BUILD SUCCESSFUL') {
        Write-Host "  round ${round}: BUILD SUCCESSFUL (fetched $fetched)"
        exit 0
    }

    # The line that names what it wanted: "DL    https://maven.neoforged.net/releases/<path>"
    # Collect first, index second: Select-String returns nothing at all when there is no match,
    # and indexing .Matches on $null throws rather than yielding $null.
    $hits = @($log | Select-String -Pattern 'DL\s+(https://maven\.neoforged\.net/releases/\S+)')
    $url = $null
    if ($hits.Count -gt 0) { $url = $hits[-1].Matches[0].Groups[1].Value }
    if (-not $url) {
        Write-Host "  round ${round}: failed without a downloadable URL -- stopping"
        $log | Select-String -Pattern 'Caused by|Node action' | Select-Object -First 4 | ForEach-Object { Write-Host "    $($_.Line.Trim())" }
        exit 1
    }

    $relative = $url.Substring($upstream.Length)
    $target = Join-Path $cache ($relative -replace '/', '\')
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    try {
        Invoke-WebRequest -Uri ($cdn + $relative) -OutFile $target -TimeoutSec 180
        $fetched++
        Write-Host ("  round {0}: {1}" -f $round, $relative)
    } catch {
        Write-Host ("  round {0}: CDN does not have {1} -- stopping" -f $round, $relative)
        exit 1
    }
}

Write-Host "  gave up after $MaxRounds rounds"
exit 1
