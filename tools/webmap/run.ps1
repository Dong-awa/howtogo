# Serves the browser map without Minecraft, for looking at the page in a real browser.
#
# The mod's own server is the shipping one; this only stands in for the game's thread, so what is
# being looked at is the real page, the real scripts and the real payload, against a made-up city.
#
# Usage:  .\tools\webmap\run.ps1                 # http://127.0.0.1:7573/, until Enter
#         .\tools\webmap\run.ps1 -Port 8080
#         .\tools\webmap\run.ps1 -Fixture out.json   # also write the payload the page will be given
#         .\tools\webmap\run.ps1 -Seconds 600        # stay up for ten minutes, then stop by itself
#
# Requires the mod to have been compiled once by tools\routing-harness\run-fast.ps1 (it writes the
# runtime classpath and the mod's classes this script reuses).
#
# *** Stop this before running a Gradle build. ***
# The runtime classpath it starts the JVM with includes build\moddev\artifacts\neoforge-*.jar, and a
# process holding those jars open stops Gradle from rewriting them: the failure is
# createMinecraftArtifacts exiting 1, which says nothing about the cause. Without -Seconds the tool
# stops on Enter; with no console at all it stops by itself after five minutes.

param(
    [int]$Port = 7573,
    # Where to write the payload as well, so it can be fed to tools\webmap-test\run.js.
    [string]$Fixture,
    # Seconds to serve for; 0 waits for a line on standard input instead (and serves for five minutes
    # when there is no console to wait on). Use this from a script or a background job.
    [int]$Seconds = 0
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = (Resolve-Path (Join-Path $here '..\..')).Path
$harnessBuild = Join-Path $project 'tools\routing-harness\build'
$classpathFile = Join-Path $harnessBuild 'classpath.txt'
$modClasses = Join-Path $harnessBuild 'mod-classes'
$resources = Join-Path $project 'src\main\resources'
$out = Join-Path $here 'build'

if (-not (Test-Path $classpathFile) -or -not (Test-Path $modClasses)) {
    throw "compile the mod first: powershell -ExecutionPolicy Bypass -File tools\routing-harness\run-fast.ps1"
}
$runtime = (Get-Content $classpathFile -Raw).Trim()

New-Item -ItemType Directory -Path $out -Force | Out-Null
Write-Host 'compiling the standalone server...'
& javac -encoding UTF-8 -proc:none -nowarn -cp "$modClasses;$runtime" -d $out (Join-Path $here 'Serve.java')
if ($LASTEXITCODE -ne 0) { throw "javac failed with $LASTEXITCODE" }

# Named arguments, and only the ones that were asked for: passing an empty string positionally would
# have PowerShell drop it and shift the rest along, which is how this tool once wrote the payload into
# a file named after the number of seconds it was told to run for.
$serveArgs = @("--port=$Port")
if ($Fixture) { $serveArgs += "--payload=$Fixture" }
if ($Seconds -gt 0) { $serveArgs += "--seconds=$Seconds" }

& java -cp "$out;$modClasses;$resources;$runtime" Serve @serveArgs
exit $LASTEXITCODE
