# Verifies, with the real 1.20.1-Fabric classpath and no game running, the MTR 3.x (mtr.*) classes,
# fields and methods a reflective reader is planned to touch.
#
# The MTR jar (YMTR's Fabric release) is a Production jar: its Minecraft references are intermediary
# names (net.minecraft.class_2338). A Loom node's runtime classpath -- what Gradle prints below --
# carries the SAME Minecraft in the official namespace instead (net.minecraft.core.BlockPos), so the
# probe runs twice against two classpaths:
#
#   dev         the node runtime classpath + the MTR jar. Every mtr.* name in the plan is checked
#               here; net.minecraft.class_* cannot resolve, and that miss is the finding: on a dev
#               classpath a class_* row is a namespace fact, not a version fact.
#   production  the same, plus Loom's cached intermediary Minecraft jar, whose class_* names are the
#               ones a running Fabric game has. This is the pass that tells the reader which
#               spelling to use at runtime, and that MTR's own field types (corner1: class_3545,
#               getCenter(): class_2338, Rail.getPosition(): class_243) resolve there.
#
# Usage:  .\tools\mtr3-probe\run.ps1
#         .\tools\mtr3-probe\run.ps1 -Refresh           # re-read the classpath from Gradle first
#         .\tools\mtr3-probe\run.ps1 -MtrJar <other.jar> # check another MTR 3.x build
#         .\tools\mtr3-probe\run.ps1 -DevOnly            # skip the intermediary pass
#         .\tools\mtr3-probe\run.ps1 -IntermediaryJar <path>
#
# Cache: build/classpath-1.20.1-fabric.txt is written once and reused, exactly like
# tools/routing-harness/build/classpath.txt, because Gradle's configuration phase costs a minute for
# a question that changes only when build scripts do.
#
# Exit code: 0 when the probe ran (found/unresolved counts are printed; the dev pass is expected to
# miss the class_* rows). Non-zero only when the tool itself could not run: no Gradle classpath, no
# MTR jar, javac failed.

param(
    # Re-read the runtime classpath from Gradle instead of reusing the cached file.
    [switch]$Refresh,
    # The MTR 3.x build to check. One jar at a time: an answer about names is an answer about a
    # release, and two builds on the classpath together cannot say which one answered.
    [string]$MtrJar = 'C:\Users\hzz12\Downloads\MTR-fabric-1.20.1-3.6.4.2.jar',
    # The Stonecutter node whose runtime classpath is measured. 1.20.1-fabric is the node the
    # MTR 3.x reader is for; the root project is the active node (a different one) and must not be
    # used, or the classpath measured would be another Minecraft's.
    [string]$Node = '1.20.1-fabric',
    # The Gradle user home the node's dependencies were actually downloaded into. The default
    # (%USERPROFILE%\.gradle) on this machine does not hold them: this tree was built with the
    # shared cache under H:\MCMOD\Hexcolorblocks, which is why the harness's own classpath.txt points
    # there too. -g, not an environment variable, so the caller's shell is left alone.
    [string]$GradleUserHome = 'H:\MCMOD\Hexcolorblocks',
    # The intermediary-named Minecraft jar for the production pass. Found automatically in Loom's
    # cache when omitted; without it the production pass is skipped rather than failing, because the
    # dev pass alone is still a useful answer.
    [string]$IntermediaryJar,
    [switch]$DevOnly
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = (Resolve-Path (Join-Path $here '..\..')).Path
$work = Join-Path $here 'build'
$classpathFile = Join-Path $work "classpath-$Node.txt"
$probeClasses = Join-Path $work 'probe-classes'
$probeSource = Join-Path $here 'Probe.java'

$jdk = 'C:\Program Files\Java\jdk-21.0.11'
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
$separator = [IO.Path]::PathSeparator
$probeClass = 'Probe'

New-Item -ItemType Directory -Force -Path $work | Out-Null
if (-not (Test-Path -LiteralPath $javac)) { throw "javac not found at $javac" }

# ---------------------------------------------------------------------------------------------
# 1. The node's runtime classpath, from Gradle
# ---------------------------------------------------------------------------------------------
if ($Refresh -or -not (Test-Path -LiteralPath $classpathFile)) {
    Write-Host "reading the $Node runtime classpath from gradle (once per build-script change)..."
    $initScript = Join-Path $work 'print-classpath.gradle'
    $gradleErr = Join-Path $work 'gradle-stderr.log'
    # Registered from an init script rather than by adding a task to a build script: build scripts are
    # not this tool's to change. The task is put on the NODE, not on the root project -- Gradle 9
    # refuses to resolve another project's configuration from a task in this one ("Resolution of the
    # configuration ':1.20.1-fabric:runtimeClasspath' was attempted without an exclusive lock"), so
    # the task has to live where the configuration does.
    #
    # --configure-on-demand is load-bearing: a task in one node otherwise configures every node in
    # the Stonecutter tree, and the 26.x nodes' Loom requires a Java 25 Gradle JVM (and their
    # Minecraft setup a download this network cannot make), so the build fails before the 1.20.1 node
    # is reached.
    $initBody = @'
gradle.beforeProject { p ->
    if (p.path == "NODEPATH") {
        p.afterEvaluate {
            p.tasks.register("printMtr3Classpath") {
                doLast {
                    println "CPSTART"
                    println p.sourceSets.main.runtimeClasspath.asPath
                    println "CPEND"
                }
            }
        }
    }
}
'@
    $initBody = $initBody.Replace('NODEPATH', ":$Node")
    Set-Content -Path $initScript -Value $initBody -Encoding ASCII

    $taskPath = ':' + $Node + ':printMtr3Classpath'
    $gradleArgs = @(
        '-g', $GradleUserHome,
        '--init-script', $initScript,
        '-q', $taskPath,
        '--offline', '--console=plain', '--configure-on-demand'
    )
    Push-Location $project
    try {
        $printed = & (Join-Path $project 'gradlew.bat') @gradleArgs 2> $gradleErr | Out-String
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($code -ne 0) {
        Get-Content $gradleErr -ErrorAction SilentlyContinue | Write-Host
        throw "gradle could not print the $Node classpath (exit $code)"
    }

    $inside = $false
    $lines = @()
    foreach ($line in ($printed -split "`r?`n")) {
        if ($line -match '^CPSTART') { $inside = $true; continue }
        if ($line -match '^CPEND') { $inside = $false; continue }
        if ($inside) { $lines += $line }
    }
    $runtime = ($lines -join '').Trim()
    if (-not $runtime) {
        Write-Host $printed
        throw 'could not read the runtime classpath out of gradle'
    }
    Set-Content -Path $classpathFile -Value $runtime -Encoding ASCII
}

$runtime = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
if (-not $runtime) { throw "empty classpath file: $classpathFile" }
Write-Host "runtime classpath: $classpathFile"

# ---------------------------------------------------------------------------------------------
# 2. The MTR jar
# ---------------------------------------------------------------------------------------------
# One jar on the classpath at a time, and it is the release under test: an answer about names is an
# answer about a version, and the point of the tool is that it can be re-pointed at another release.
if (-not (Test-Path -LiteralPath $MtrJar)) {
    throw "MTR jar not found: $MtrJar (pass -MtrJar <path>)"
}
$mtr = (Resolve-Path -LiteralPath $MtrJar).Path
Write-Host "MTR jar: $mtr"

# ---------------------------------------------------------------------------------------------
# 3. Compile the probe -- against the JDK only: every lookup it makes is reflective, and on purpose
#    it compiles with neither MTR nor Minecraft on the classpath. A probe that needed them would be
#    a probe that could only check what it already linked against.
# ---------------------------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $probeClasses)) {
    New-Item -ItemType Directory -Force -Path $probeClasses | Out-Null
}
$javacErr = Join-Path $work 'javac-stderr.log'
& $javac '-encoding' 'UTF-8' '-proc:none' '-nowarn' '-d' $probeClasses $probeSource 2> $javacErr
if ($LASTEXITCODE -ne 0) {
    Get-Content $javacErr -ErrorAction SilentlyContinue | Write-Host
    throw "javac failed on Probe.java with $LASTEXITCODE"
}

# ---------------------------------------------------------------------------------------------
# 4. The two passes
# ---------------------------------------------------------------------------------------------
function Invoke-ProbePass {
    param([string]$Label, [string]$Classpath)
    $log = Join-Path $work "probe-$Label.log"
    $stderrLog = Join-Path $work "probe-$Label.err.log"
    Write-Host ''
    # Out-Host, not left as the function's output: the function's only result has to be the exit
    # code, or the caller captures the whole report as its value.
    & $java '-cp' $Classpath $probeClass $Label 2> $stderrLog | Tee-Object -FilePath $log | Out-Host
    $code = $LASTEXITCODE
    if ((Test-Path -LiteralPath $stderrLog) -and (Get-Item -LiteralPath $stderrLog).Length -gt 0) {
        Add-Content -Path $log -Value ''
        Add-Content -Path $log -Value '--- stderr ---'
        Add-Content -Path $log -Value (Get-Content -LiteralPath $stderrLog -Raw)
    }
    return $code
}

$devCode = Invoke-ProbePass -Label 'dev' -Classpath ($probeClasses + $separator + $runtime + $separator + $mtr)
if ($devCode -ne 0) { throw "the dev pass failed with $devCode" }

if ($DevOnly) {
    Write-Host ''
    Write-Host '-DevOnly: the production (intermediary) pass was skipped'
} else {
    # Loom keeps the intermediary-named merged Minecraft jar beside the named one, in the same
    # mapping-hash directory. It is the closest thing to "the Minecraft a Fabric game actually runs"
    # that can be put on a classpath without starting one.
    if (-not $IntermediaryJar) {
        $token = $null
        foreach ($entry in ($runtime -split [regex]::Escape($separator))) {
            if ($entry -match 'minecraft-merged-[0-9a-f]+-([^\\]+)\.jar$') {
                $token = $Matches[1]
                break
            }
        }
        if ($token) {
            $roots = @(
                (Join-Path $GradleUserHome 'caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-intermediary'),
                (Join-Path $project '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-intermediary'),
                (Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-intermediary')
            )
            foreach ($root in $roots) {
                if (-not (Test-Path -LiteralPath $root)) { continue }
                $candidate = Join-Path $root "$token\minecraft-merged-intermediary-$token.jar"
                if (Test-Path -LiteralPath $candidate) {
                    $IntermediaryJar = $candidate
                    break
                }
            }
        }
    }

    if ($IntermediaryJar) {
        $inter = (Resolve-Path -LiteralPath $IntermediaryJar).Path
        Write-Host ''
        Write-Host "intermediary Minecraft: $inter"
        # Appended, not substituted: the named jar stays where Gradle put it, and the class_* names
        # come from the jar that has them. The two name sets do not overlap.
        $productionCode = Invoke-ProbePass -Label 'production' -Classpath ($probeClasses + $separator + $runtime + $separator + $mtr + $separator + $inter)
        if ($productionCode -ne 0) { throw "the production pass failed with $productionCode" }
    } else {
        Write-Host ''
        Write-Host 'no intermediary Minecraft jar found in the Loom cache: the production pass is skipped'
        Write-Host '(the dev pass above still checked every mtr.* name; pass -IntermediaryJar <path> to aim it)'
    }
}

# ---------------------------------------------------------------------------------------------
# 5. Where the evidence is
# ---------------------------------------------------------------------------------------------
Write-Host ''
Write-Host ('classpath file : ' + $classpathFile)
Write-Host ('probe logs     : ' + (Join-Path $work 'probe-dev.log'))
if (-not $DevOnly) {
    Write-Host ('                 ' + (Join-Path $work 'probe-production.log'))
}
Write-Host ''
Write-Host 're-run:'
Write-Host ('  powershell -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $here 'run.ps1') + '"')

exit 0
