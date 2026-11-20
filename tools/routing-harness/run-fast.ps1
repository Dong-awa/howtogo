# Type-checks the mod and runs the routing regression harness, without Gradle.
#
# The same two steps run.ps1 does -- javac on the mod, javac on the harness, then the harness -- but
# with the classpath read from build/classpath.txt, which run.ps1 wrote the first time. That file
# changes only when build.gradle does, so this is the loop to use while working: seconds instead of the
# minute or two Gradle's configuration phase costs on every run.
#
# Usage:  .\tools\routing-harness\run-fast.ps1
#         .\tools\routing-harness\run-fast.ps1 -Refresh      # re-read the classpath from Gradle first
#         .\tools\routing-harness\run-fast.ps1 -MtrJar <path>  # check another MTR build's handshake
#         .\tools\routing-harness\run-fast.ps1 -RoadWeaverJar <path>  # the same for RoadWeaver
#         .\tools\routing-harness\run-fast.ps1 -WorkDir build-verify-a
#             A directory of its own for the class files, so two people (or two agents) type-checking
#             the same working copy do not delete each other's output halfway through. The classpath
#             is copied from build/classpath.txt when the directory is new, so this costs nothing.
# Exit:   the harness's own status, so a failing check fails the script.

param(
    [switch]$Refresh,
    # An MTR build to check the reflection handshake against, instead of whatever is in run/mods. Kept
    # as a parameter because the answer to "does this mod work with MTR x.y.z" is per build: one jar at
    # a time on the classpath, one run each, and the check reads that jar's own shapes.
    [string]$MtrJar,
    [string]$MtrMapJar,
    # A RoadWeaver build to check the same thing for: which of its map-data and road-store classes this
    # reader still knows is a property of the release, so it is measured one jar at a time.
    [string]$RoadWeaverJar,
    # Writes the browser map's fixture payload to this path. The page's own tests are then run against
    # a payload this server really produced -- node tools\webmap-test\run.js <path> -- rather than
    # against one written to match it, which is what catches the two sides drifting apart.
    [string]$WriteWebMapFixture,
    # Where the class files and the harness's own logs go, relative to this directory. The default is
    # the one every run used to write, so an existing workflow is unchanged; anything else is a
    # private workspace, and a new one takes its classpath from the default rather than asking Gradle.
    [string]$WorkDir = 'build'
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = (Resolve-Path (Join-Path $here '..\..')).Path
$work = if ([System.IO.Path]::IsPathRooted($WorkDir)) { $WorkDir } else { Join-Path $here $WorkDir }
if (-not (Test-Path $work)) {
    New-Item -ItemType Directory -Path $work -Force | Out-Null
}
$sharedClasspath = Join-Path $here 'build\classpath.txt'
$modClasses = Join-Path $work 'mod-classes'
$harnessClasses = Join-Path $work 'harness-classes'
$classpathFile = Join-Path $work 'classpath.txt'

# A private workspace borrows the classpath the default one already has: it is a property of
# build.gradle, not of this run, and asking Gradle for it is a minute of configuration for a file
# that cannot have changed.
if (-not $Refresh -and -not (Test-Path $classpathFile) -and (Test-Path $sharedClasspath)) {
    Copy-Item $sharedClasspath $classpathFile
}

if ($Refresh -or -not (Test-Path $classpathFile)) {
    Write-Host 'reading the runtime classpath from gradle (needed once per build.gradle change)...'
    $initScript = Join-Path $env:TEMP 'howtogo-print-classpath.gradle'
    $gradleErr = Join-Path $work 'gradle-stderr.log'
    # The task is registered on the active node's subproject rather than the root: under stonecutter
    # the root project has no sourceSets of its own -- the runtime classpath lives on the node.
    $activeNode = (Select-String -Path (Join-Path $project 'stonecutter.gradle.kts') `
        -Pattern '^\s*stonecutter active "([^"]+)"').Matches[0].Groups[1].Value
    $initBody = @(
        'gradle.projectsEvaluated {'
        '    rootProject.subprojects { p ->'
        '        if (p.name == ''' + $activeNode + ''') {'
        '            p.tasks.register(''printCp'') {'
        '                doLast {'
        '                    println "CPSTART"'
        '                    println p.sourceSets.main.runtimeClasspath.asPath'
        '                    println "CPEND"'
        '                }'
        '            }'
        '        }'
        '    }'
        '}'
    )
    Set-Content -Path $initScript -Value $initBody -Encoding ASCII
    Push-Location $project
    $printed = & .\gradlew.bat --init-script $initScript -q printCp --offline --console=plain 2>$gradleErr |
        Out-String
    $cpCode = $LASTEXITCODE
    Pop-Location
    if ($cpCode -ne 0) {
        Get-Content $gradleErr | Write-Host
        throw "gradle printCp failed with $cpCode"
    }
    $start = $printed.IndexOf('CPSTART')
    $end = $printed.IndexOf('CPEND')
    if ($start -lt 0 -or $end -lt 0) {
        Write-Host $printed
        throw 'could not read the runtime classpath out of gradle'
    }
    $printed.Substring($start + 8, $end - $start - 8).Trim() |
        Set-Content -Path $classpathFile -Encoding ASCII
}

# javac and java are started from a response file rather than from the command line.
#
# The toolchain is invoked twice with a whole source tree and a runtime classpath: this tree's classpath
# alone is sixteen thousand characters, and the two source roots add one line per file. Windows caps a
# process's command line at 32767 characters, and past that limit CreateProcess fails before javac ever
# starts -- "The filename or extension is too long", which reads as a broken compiler rather than as a
# long list. A response file has no such cap.
#
# Paths are written with forward slashes and quoted, so no argument in the file depends on how Windows
# backslashes are escaped: javac and java accept forward slashes on Windows, and a quoted argument with no
# backslash in it is read the same way by both.
function Convert-ToArgFileLine([string]$value) {
    return '"' + ($value -replace '\\', '/') + '"'
}

function New-ArgumentFile([string]$path, [string[]]$lines) {
    Set-Content -LiteralPath $path -Value $lines -Encoding ASCII
    return $path
}

$runtime = (Get-Content $classpathFile -Raw).Trim()

# The compileOnly dependencies -- Xaero's World Map and the phone mod -- are in libs/ and are not on the
# runtime classpath by design.
$compileOnly = (Get-ChildItem (Join-Path $project 'libs') -Filter *.jar |
    ForEach-Object { $_.FullName }) -join ';'
$compilePath = "$runtime;$compileOnly"

Write-Host 'compiling the mod...'
# Both source roots. The mod is split -- src/main holds everything that has to know which Minecraft and
# which loader it is on, src/common the pure Java every branch shares -- and a compile that sees only one
# of them cannot resolve the other's packages, so it fails on the first import rather than on anything the
# change under test touched.
$sources = @('src\main\java', 'src\common\java') |
    ForEach-Object { Join-Path $project $_ } |
    Where-Object { Test-Path $_ } |
    ForEach-Object { Get-ChildItem $_ -Recurse -Filter *.java } |
    ForEach-Object { $_.FullName }
# Deliberately without MTR on the classpath: the reader is reflective, and compiling against a mod only
# its users have would be a dependency by another name.
Remove-Item $modClasses -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $modClasses -Force | Out-Null
$modArgs = @('-encoding', 'UTF-8', '-proc:none', '-nowarn',
    '-cp', (Convert-ToArgFileLine $compilePath),
    '-d', (Convert-ToArgFileLine $modClasses))
$modArgs += $sources | ForEach-Object { Convert-ToArgFileLine $_ }
$modArgFile = New-ArgumentFile (Join-Path $work 'javac-mod.args') $modArgs
& javac "@$modArgFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed on the mod with $LASTEXITCODE" }

# MTR itself is added after the mod has been compiled, and only for the harness: with its jar on the
# classpath the harness checks that every class, field and method the reader looks up is one MTR has.
# MTR Map Overlay is added the same way, for the same check of its own reader: neither is a dependency
# of the mod itself -- both readers are reflective -- so both are for the harness only, and a machine
# that has never seen either simply skips the check.
$mtr = if ($MtrJar) { (Resolve-Path $MtrJar).Path } else {
    (Get-ChildItem (Join-Path $project 'run\mods') -Filter 'MTR-*.jar' -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }) -join ';'
}
$overlay = if ($MtrMapJar) { (Resolve-Path $MtrMapJar).Path } else {
    (Get-ChildItem (Join-Path $project 'run\mods') -Filter '*mtrmap*.jar' -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }) -join ';'
}
$roadWeaver = if ($RoadWeaverJar) { (Resolve-Path $RoadWeaverJar).Path } else {
    (Get-ChildItem (Join-Path $project 'run\mods') -Filter 'roadweaver-*.jar' -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }) -join ';'
}
if ($mtr) {
    Write-Host "an MTR jar is present: the reflection handshake will be checked against $(Split-Path $mtr -Leaf)"
} else {
    Write-Host 'no MTR jar in run/mods (and none given with -MtrJar): the handshake check will be skipped'
}
if ($overlay) {
    Write-Host 'an MTR Map Overlay jar is present: its reflection handshake will be checked'
} else {
    Write-Host 'no MTR Map Overlay jar in run/mods: its handshake check will be skipped'
}
if ($roadWeaver) {
    Write-Host "a RoadWeaver jar is present: its reflection handshake will be checked against $(Split-Path $roadWeaver -Leaf)"
} else {
    Write-Host 'no RoadWeaver jar in run/mods (and none given with -RoadWeaverJar): the handshake check will be skipped'
}
$harnessPath = $compilePath
if ($mtr) { $harnessPath = "$harnessPath;$mtr" }
if ($overlay) { $harnessPath = "$harnessPath;$overlay" }
if ($roadWeaver) { $harnessPath = "$harnessPath;$roadWeaver" }

Write-Host 'compiling the harness...'
Remove-Item $harnessClasses -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $harnessClasses -Force | Out-Null
# Recurse: a check that has to reach package-private members lives in that package's directories
# rather than in this one, so a top-level-only listing would compile every check but those.
$harnessSources = Get-ChildItem $here -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$harnessArgs = @('-encoding', 'UTF-8', '-proc:none', '-nowarn',
    '-cp', (Convert-ToArgFileLine "$modClasses;$harnessPath"),
    '-d', (Convert-ToArgFileLine $harnessClasses))
$harnessArgs += $harnessSources | ForEach-Object { Convert-ToArgFileLine $_ }
$harnessArgFile = New-ArgumentFile (Join-Path $work 'javac-harness.args') $harnessArgs
& javac "@$harnessArgFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed on the harness with $LASTEXITCODE" }

Write-Host 'running the harness...'
Write-Host ''
# From the harness's own directory: the logging setup that comes with the game's classpath writes a
# logs/ folder into the working directory, and it belongs next to the harness's other output.
# The mod's own resources are appended last so that the web map's checks can fetch the page, its
# scripts and the vendored html2canvas out of the real files rather than out of a stub -- appended
# rather than prepended, so nothing here can shadow a class or a resource the runtime already has.
$resources = Join-Path $project 'src\main\resources'
$runArgs = @("-Dhowtogo.webmap.fixture=$WriteWebMapFixture",
    '-cp', (Convert-ToArgFileLine "$harnessClasses;$modClasses;$harnessPath;$resources"),
    'Harness')
$runArgFile = New-ArgumentFile (Join-Path $work 'java-harness.args') $runArgs
Push-Location $work
& java "@$runArgFile"
$harnessCode = $LASTEXITCODE
Pop-Location
exit $harnessCode
