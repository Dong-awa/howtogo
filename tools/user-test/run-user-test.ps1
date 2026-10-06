# Launches the mod in a real client and gets it ready to be tested from a player's side.
#
# The routing harness (tools/routing-harness) covers the pure logic in seconds with no game. This is the
# other half: a real client, the real world, the mod's own screens and guidance. What it does is start
# the client, wait until the mod is actually up (its "constructed" line in the log), and print the
# checklist of things to do in the game -- including the one command that now answers "is this mod
# working" by itself: /howtogo selftest.
#
# Usage:
#   .\tools\user-test\run-user-test.ps1                 # start the client and wait for it
#   .\tools\user-test\run-user-test.ps1 -Stop           # stop client(s) started from this project
#   .\tools\user-test\run-user-test.ps1 -ResetData      # back up and clear the mod's own data files
#
# Exit: 0 when the client is up (or the requested action succeeded), 1 otherwise.

param(
    [switch]$Stop,
    [switch]$ResetData,
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = (Resolve-Path (Join-Path $here '..\..')).Path
$runDir = Join-Path $project 'run'
$latest = Join-Path $runDir 'logs\latest.log'
$dataDir = Join-Path $runDir 'config\howtogo'

# The client this project starts is identifiable by the VM args file Gradle hands it, so a stop cannot
# touch a game the player launched from somewhere else.
function Get-ProjectClients {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like "*$project*clientRunVmArgs.txt*" }
}

if ($Stop) {
    $clients = @(Get-ProjectClients)
    if ($clients.Count -eq 0) {
        Write-Host 'no client started from this project is running'
        exit 0
    }
    foreach ($client in $clients) {
        Write-Host "stopping client pid $($client.ProcessId)"
        Stop-Process -Id $client.ProcessId -Force
    }
    exit 0
}

if ($ResetData) {
    # Verified before it is touched: the path must be the mod's data directory inside this project's run
    # directory, and whatever was there is moved aside rather than deleted.
    if (-not (Test-Path $dataDir)) {
        Write-Host "nothing to reset: $dataDir does not exist"
        exit 0
    }
    $expected = (Join-Path $project 'run\config\howtogo')
    if ((Resolve-Path $dataDir).Path -ne $expected) {
        throw "refusing to reset $dataDir -- resolved to something other than $expected"
    }
    $backup = Join-Path $runDir ("config\howtogo-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    Move-Item $dataDir $backup
    Write-Host "moved the mod's data aside to $backup"
    Write-Host 'the next launch starts from an empty world of roads, stations and lines'
    exit 0
}

if (@(Get-ProjectClients).Count -gt 0) {
    Write-Host 'a client started from this project is already running -- test that one, or stop it first:'
    Write-Host "  .\tools\user-test\run-user-test.ps1 -Stop"
    exit 0
}

Write-Host "starting the client from $project (this takes a minute or two)..."
$log = Join-Path $project 'tools\user-test\client.log'
$process = Start-Process -FilePath (Join-Path $project 'gradlew.bat') -ArgumentList 'runClient',
    '--offline', '--console=plain' -WorkingDirectory $project -PassThru -RedirectStandardOutput $log `
    -RedirectStandardError (Join-Path $project 'tools\user-test\client.err.log')

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$up = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    if (Test-Path $latest) {
        # The mod says this once, from its own constructor: proof that the build under test is the one
        # the client loaded rather than a stale jar.
        if (Select-String -Path $latest -Pattern '\[HowToGo\] constructed' -Quiet) {
            $up = $true
            break
        }
    }
    if ($process.HasExited) {
        Write-Host "the launcher exited with code $($process.ExitCode); see $log"
        exit 1
    }
}
if (-not $up) {
    Write-Host "the client did not come up within $TimeoutSeconds s; see $log and $latest"
    exit 1
}

Write-Host ''
Write-Host 'the client is up and the mod is loaded. In the game:'
Write-Host ''
Write-Host '  1. load the test world (single player), then run:  /howtogo selftest'
Write-Host '     -- it checks this world against everything the mod says it can do and prints a report'
Write-Host '  2. the parts only a player can drive, from the checklist:'
Write-Host '     .\tools\user-test\README.md'
Write-Host ''
Write-Host "mod log:      $latest   (filter for [HowToGo])"
Write-Host "launcher log: $log"
exit 0
