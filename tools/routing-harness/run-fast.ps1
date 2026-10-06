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
# Exit:   the harness's own status, so a failing check fails the script.

param(
    [switch]$Refresh,
    # An MTR build to check the reflection handshake against, instead of whatever is in run/mods. Kept
    # as a parameter because the answer to "does this mod work with MTR x.y.z" is per build: one jar at
    # a time on the classpath, one run each, and the check reads that jar's own shapes.
    [string]$MtrJar,
    [string]$MtrMapJar,
    # Writes the browser map's fixture payload to this path. The page's own tests are then run against
    # a payload this server really produced -- node tools\webmap-test\run.js <path> -- rather than
    # against one written to match it, which is what catches the two sides drifting apart.
    [string]$WriteWebMapFixture
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = (Resolve-Path (Join-Path $here '..\..')).Path
$work = Join-Path $here 'build'
$modClasses = Join-Path $work 'mod-classes'
$harnessClasses = Join-Path $work 'harness-classes'
$classpathFile = Join-Path $work 'classpath.txt'

if ($Refresh -or -not (Test-Path $classpathFile)) {
    Write-Host 'reading the runtime classpath from gradle (needed once per build.gradle change)...'
    $initScript = Join-Path $env:TEMP 'howtogo-print-classpath.gradle'
    $gradleErr = Join-Path $work 'gradle-stderr.log'
    $initBody = @(
        'gradle.projectsEvaluated {'
        '    rootProject.tasks.register(''printCp'') {'
        '        doLast {'
        '            println "CPSTART"'
        '            println rootProject.sourceSets.main.runtimeClasspath.asPath'
        '            println "CPEND"'
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
& javac -encoding UTF-8 -proc:none -nowarn -cp $compilePath -d $modClasses $sources
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
$harnessPath = $compilePath
if ($mtr) { $harnessPath = "$harnessPath;$mtr" }
if ($overlay) { $harnessPath = "$harnessPath;$overlay" }

Write-Host 'compiling the harness...'
Remove-Item $harnessClasses -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $harnessClasses -Force | Out-Null
# Recurse: a check that has to reach package-private members lives in that package's directories
# rather than in this one, so a top-level-only listing would compile every check but those.
$harnessSources = Get-ChildItem $here -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -proc:none -nowarn -cp "$modClasses;$harnessPath" -d $harnessClasses `
    $harnessSources
if ($LASTEXITCODE -ne 0) { throw "javac failed on the harness with $LASTEXITCODE" }

Write-Host 'running the harness...'
Write-Host ''
# From the harness's own directory: the logging setup that comes with the game's classpath writes a
# logs/ folder into the working directory, and it belongs next to the harness's other output.
# The mod's own resources are appended last so that the web map's checks can fetch the page, its
# scripts and the vendored html2canvas out of the real files rather than out of a stub -- appended
# rather than prepended, so nothing here can shadow a class or a resource the runtime already has.
$resources = Join-Path $project 'src\main\resources'
Push-Location $work
& java "-Dhowtogo.webmap.fixture=$WriteWebMapFixture" -cp "$harnessClasses;$modClasses;$harnessPath;$resources" Harness
$harnessCode = $LASTEXITCODE
Pop-Location
exit $harnessCode
