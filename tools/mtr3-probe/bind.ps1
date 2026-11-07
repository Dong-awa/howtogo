# Drives the real Mtr3Data reader against real MTR 3.x objects, with no game running.
#
# The probe in Probe.java checks that the names the reader looks up exist. This checks the other half:
# it compiles the reader itself (src/main/.../mtr/Mtr3Data.java, plus MtrSnapshotData.java and a stub
# HowToGo, since the real one is a game entrypoint), binds it against the YMTR jar, builds a small
# railway out of MTR's own classes with reflection, feeds it through the conversion and reads the
# result back.
#
# The classpath is the one run.ps1 measured: the 1.20.1-fabric node's runtime classpath plus Loom's
# cached intermediary Minecraft jar, which is the namespace a running Fabric game has. The reader is
# a reflective one, so nothing here links against MTR or Minecraft at compile time -- only at run time.
#
# Usage:  .\tools\mtr3-probe\bind.ps1
#         .\tools\mtr3-probe\bind.ps1 -MtrJar <other.jar>
#         .\tools\mtr3-probe\bind.ps1 -Refresh     # re-read the node classpath from Gradle first
#
# Cache: build/classpath-1.20.1-fabric.txt, written by run.ps1 and reused here. Run that once first.
#
# Exit code: the check's own, so a reader that stops reading is a failing script.

param(
    [switch]$Refresh,
    [string]$MtrJar = 'C:\Users\hzz12\Downloads\MTR-fabric-1.20.1-3.6.4.2.jar',
    [string]$Node = '1.20.1-fabric',
    [string]$GradleUserHome = 'H:\MCMOD\Hexcolorblocks',
    [string]$IntermediaryJar
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = (Resolve-Path (Join-Path $here '..\..')).Path
$work = Join-Path $here 'build'
$classpathFile = Join-Path $work "classpath-$Node.txt"
$classes = Join-Path $work 'bind-classes'
$stubDir = Join-Path $work 'bind-stub'
$jdk = 'C:\Program Files\Java\jdk-21.0.11'
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
$separator = [IO.Path]::PathSeparator

if ($Refresh -or -not (Test-Path -LiteralPath $classpathFile)) {
    Write-Host "reading the $Node runtime classpath from gradle (via run.ps1 -Refresh)..."
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $here 'run.ps1') -Refresh -MtrJar $MtrJar -Node $Node -GradleUserHome $GradleUserHome -DevOnly | Out-Host
    if (-not (Test-Path -LiteralPath $classpathFile)) {
        throw "no classpath file at $classpathFile"
    }
}
$runtime = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
if (-not $runtime) { throw "empty classpath file: $classpathFile" }

if (-not (Test-Path -LiteralPath $MtrJar)) { throw "MTR jar not found: $MtrJar" }
$mtr = (Resolve-Path -LiteralPath $MtrJar).Path

# Loom's intermediary-named Minecraft jar: the class_* names a running Fabric game has.
if (-not $IntermediaryJar) {
    $root = Join-Path $GradleUserHome 'caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-intermediary'
    if (Test-Path -LiteralPath $root) {
        $IntermediaryJar = (Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*1.20.1*.jar' |
            Where-Object { $_.Name -like 'minecraft-merged-intermediary-*' -and $_.Name -like '*.jar' } |
            Select-Object -First 1).FullName
    }
}
if (-not $IntermediaryJar) {
    throw 'no intermediary Minecraft jar found; pass -IntermediaryJar <path> (run.ps1 prints the one it used)'
}
$inter = (Resolve-Path -LiteralPath $IntermediaryJar).Path
Write-Host "intermediary Minecraft: $inter"

# The stub entrypoint: the reader logs through bili.dongsz.howtogo.HowToGo, whose real class needs the
# game. Only the logger is used, so that is all the stub has.
New-Item -ItemType Directory -Force -Path $stubDir | Out-Null
$stubPackage = Join-Path $stubDir 'bili\dongsz\howtogo'
New-Item -ItemType Directory -Force -Path $stubPackage | Out-Null
$stub = @'
package bili.dongsz.howtogo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Probe stub for the game's entrypoint: the reader only uses the logger. */
public final class HowToGo {
    public static final Logger LOGGER = LoggerFactory.getLogger("howtogo-probe");

    private HowToGo() {
    }
}
'@
Set-Content -Path (Join-Path $stubPackage 'HowToGo.java') -Value $stub -Encoding ASCII

$mtrSource = Join-Path $project 'src\main\java\bili\dongsz\howtogo\mtr\Mtr3Data.java'
$snapshotSource = Join-Path $project 'src\main\java\bili\dongsz\howtogo\mtr\MtrSnapshotData.java'
$bindSource = Join-Path $here 'BindCheck.java'

Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null
Write-Host 'compiling the reader and the check (nothing of MTR or Minecraft is linked: every lookup is reflective)...'
& $javac '-encoding' 'UTF-8' '-proc:none' '-nowarn' '-cp' $runtime '-d' $classes `
    (Join-Path $stubPackage 'HowToGo.java') $mtrSource $snapshotSource $bindSource
if ($LASTEXITCODE -ne 0) { throw "javac failed on the check with $LASTEXITCODE" }

$log = Join-Path $work 'bind-check.log'
$stderrLog = Join-Path $work 'bind-check.err.log'
Write-Host ''
& $java '-cp' ($classes + $separator + $runtime + $separator + $mtr + $separator + $inter) `
    'bili.dongsz.howtogo.mtr.BindCheck' 2> $stderrLog | Tee-Object -FilePath $log | Out-Host
$code = $LASTEXITCODE
if ((Test-Path -LiteralPath $stderrLog) -and (Get-Item -LiteralPath $stderrLog).Length -gt 0) {
    Add-Content -Path $log -Value ''
    Add-Content -Path $log -Value '--- stderr ---'
    Add-Content -Path $log -Value (Get-Content -LiteralPath $stderrLog -Raw)
}
Write-Host ''
Write-Host ('log: ' + $log)
Write-Host 're-run:'
Write-Host ('  powershell -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $here 'bind.ps1') + '"')
exit $code
