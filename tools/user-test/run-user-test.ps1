# Launches the mod in a real client and gets it ready to be tested from a player's side.
#
# The routing harness (tools/routing-harness) covers the pure logic in seconds with no game. This is the
# other half: a real client, the real world, the mod's own screens and guidance. Two ways to use it:
#
#   -Auto      the client opens a world by itself, runs the in-game self-test, writes the report to the
#              log, and quits. One command, no typing, a pass/fail at the end -- which is the whole of
#              what a build check needs. See SelfTest and AutoSelfTest in the mod.
#   (default)  the client opens and stays up for the parts only a player can drive: the checklist in
#              README.md, and /howtogo selftest typed by hand in whatever world is loaded.
#
# Usage:
#   .\tools\user-test\run-user-test.ps1                     # start the client, wait until the mod is up
#   .\tools\user-test\run-user-test.ps1 -Auto               # open TEST, self-test, report, quit
#   .\tools\user-test\run-user-test.ps1 -Auto -World MyWorld
#   .\tools\user-test\run-user-test.ps1 -Stop               # stop client(s) started from this project
#   .\tools\user-test\run-user-test.ps1 -ResetData          # back up and clear the mod's own data files
#
# Exit: 0 when the client is up, or when -Auto's report has no failures, or when the requested action
# succeeded; 1 otherwise.

param(
    [switch]$Stop,
    [switch]$ResetData,
    [switch]$Auto,
    [string]$World = 'TEST',
    [int]$TimeoutSeconds = 900,
    # Seconds to wait in the world before the checks run: long enough for the road network, the machine
    # -read rail layers and MTR's own data to have arrived.
    [int]$SettleSeconds = 25
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = (Resolve-Path (Join-Path $here '..\..')).Path
$runDir = Join-Path $project 'run'
$latest = Join-Path $runDir 'logs\latest.log'
$dataDir = Join-Path $runDir 'config\howtogo'
$log = Join-Path $here 'client.log'

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

# Only new lines are read, so a report from an earlier run cannot be mistaken for this one's.
$fromByte = 0
if (Test-Path $latest) {
    $fromByte = (Get-Item $latest).Length
}

$properties = @('--offline', '--console=plain')
if ($Auto) {
    # The trigger the mod reads, and the world to open: both through the run config, because Gradle's
    # --args would replace the launcher's own arguments rather than add to them.
    $properties += "-PhtgSelftest=$SettleSeconds"
    $properties += '-PhtgSelftestQuit=1'
    $properties += "-PhtgQuickPlay=$World"
    Write-Host "starting the client from $project (build, then launch; this takes a minute or two)..."
    Write-Host "it will open '$World', run the self-test after ${SettleSeconds}s, and quit."
} else {
    Write-Host "starting the client from $project (this takes a minute or two)..."
}

# Launched through a generated .cmd rather than by assembling a command string for cmd /c: passing that
# string through Start-Process drops the -P properties on the way (the run came up with
# -Dhowtogo.selftest= empty and the checks never ran), and a file has no quoting to get wrong.
$bat = Join-Path $here 'run-client.cmd'
Set-Content -Path $bat -Encoding ASCII -Value @(
    '@echo off',
    "`"$project\gradlew.bat`" runClient $($properties -join ' ') > `"$log`" 2>&1"
)
$launcher = Start-Process -FilePath $bat -WorkingDirectory $project -WindowStyle Hidden -PassThru

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$up = $false
$report = @()
$failed = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    if ($Auto -and (Test-Path $latest)) {
        # Read only what has been appended since the launch: the self-test's own summary line is what
        # says the run is over, and it is written once.
        $stream = [System.IO.File]::Open($latest, 'Open', 'Read', 'ReadWrite')
        try {
            $null = $stream.Seek($fromByte, 'Begin')
            $reader = New-Object System.IO.StreamReader($stream)
            $text = $reader.ReadToEnd()
        } finally {
            $stream.Dispose()
        }
        $report = @($text -split "`r?`n" | Where-Object { $_ -match '\[HowToGo\] selftest' })
        $summary = $report | Where-Object { $_ -match 'selftest summary \| failed=(\d+)' }
        if ($summary) {
            $failed = [int]([regex]::Match($summary[0], 'failed=(\d+)').Groups[1].Value)
            $up = $true
            break
        }
    } elseif (Test-Path $latest) {
        if (Select-String -Path $latest -Pattern '\[HowToGo\] constructed' -Quiet) {
            $up = $true
            break
        }
    }
    if ($launcher.HasExited -and -not $Auto) {
        Write-Host "the launcher exited with code $($launcher.ExitCode); see $log"
        exit 1
    }
}

if (-not $up) {
    Write-Host "the client did not come up within $TimeoutSeconds s; see $log and $latest"
    Write-Host "  .\tools\user-test\run-user-test.ps1 -Stop"
    exit 1
}

if ($Auto) {
    Write-Host ''
    Write-Host '--- self-test report ---'
    $report | ForEach-Object { Write-Host ($_ -replace '^.*\[HowToGo\] ', '') }
    # The mod closes the client itself when -PhtgSelftestQuit is set, but the rig does not depend on
    # that: it stops what it started either way, so the next run cannot be refused by a client left over
    # from this one.
    foreach ($client in @(Get-ProjectClients)) {
        Stop-Process -Id $client.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($null -eq $failed) {
        Write-Host 'no summary line was written: the run did not finish'
        exit 1
    }
    Write-Host ''
    Write-Host "$failed failure(s) of $($report.Count - 1) check(s)"
    if ($failed -eq 0) { exit 0 } else { exit 1 }
}

Write-Host ''
Write-Host 'the client is up and the mod is loaded. In the game:'
Write-Host ''
Write-Host '  1. load a world, then run:  /howtogo selftest'
Write-Host '     -- it checks that world against everything the mod says it can do and prints a report'
Write-Host '  2. the parts only a player can drive, from the checklist:'
Write-Host '     .\tools\user-test\README.md'
Write-Host ''
Write-Host "mod log:      $latest   (filter for [HowToGo])"
Write-Host "launcher log: $log"
exit 0
