# Type-checks the mod and runs the routing regression harness against it.
#
# The mod's own sources are compiled here with javac rather than by gradle's compileJava, because that
# task depends on the minecraft artifacts task, which cannot rewrite its jars while the game is
# running from them -- and running the harness while the game is open is exactly when it is wanted.
# The classpath still comes from gradle, through a temporary init script, so build.gradle is untouched.
#
# Usage:  .\tools\routing-harness\run.ps1
#         powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\routing-harness\run.ps1
# Exit:   the harness's own status, so a failing check fails the script.
#
# Written for Windows PowerShell 5.1 as well as PowerShell 7: no operator that only one of them has,
# and no reliance on the execution policy, which is why the second form above is documented.

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = (Resolve-Path (Join-Path $here '..\..')).Path
$work = Join-Path $here 'build'
$modClasses = Join-Path $work 'mod-classes'
$harnessClasses = Join-Path $work 'harness-classes'
$initScript = Join-Path $env:TEMP 'howtogo-print-classpath.gradle'
$gradleErr = Join-Path $work 'gradle-stderr.log'

New-Item -ItemType Directory -Path $work -Force | Out-Null
Remove-Item $modClasses -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $harnessClasses -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $modClasses -Force | Out-Null
New-Item -ItemType Directory -Path $harnessClasses -Force | Out-Null

# The classpath the mod itself would run with, including Minecraft and NeoForge.
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
# ASCII, because a UTF-8 byte order mark at the top of a Groovy script is one thing gradle's script
# reader need not forgive.
Set-Content -Path $initScript -Value $initBody -Encoding ASCII

Write-Host 'reading the runtime classpath from gradle...'
Push-Location $project
# stderr goes to a file: gradle writes progress there, and a caller with a strict error preference
# would otherwise treat every line of it as a failure.
$printed = & .\gradlew.bat --init-script $initScript -q printCp --offline --console=plain 2>$gradleErr | Out-String
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
$runtime = $printed.Substring($start + 8, $end - $start - 8).Trim()

# The compileOnly dependencies -- Xaero's World Map and the phone mod -- are in libs/ and are not on
# the runtime classpath by design.
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
# Deliberately without MTR on the classpath: the reader is reflective, and compiling against the mod
# only its users have would be a dependency by another name. The compiler not seeing MTR is what keeps
# "no hard dependency" true.
& javac -encoding UTF-8 -proc:none -nowarn -cp $compilePath -d $modClasses $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed on the mod with $LASTEXITCODE" }

# MTR itself is added after the mod has been compiled, and only for the harness: with its jar on the
# classpath the harness checks that every class, field and method the reader looks up is one MTR
# actually has, which is the one part of the integration no made-up reading can test. MTR Map Overlay
# is added the same way for the same check of its own reader; neither is a dependency of the mod, so a
# machine that has never seen either simply skips that check.
$mtr = (Get-ChildItem (Join-Path $project 'run\mods') -Filter 'MTR-*.jar' -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName }) -join ';'
$overlay = (Get-ChildItem (Join-Path $project 'run\mods') -Filter '*mtrmap*.jar' -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName }) -join ';'
if ($mtr) {
    Write-Host 'an MTR jar is present: the reflection handshake will be checked'
} else {
    Write-Host 'no MTR jar in run/mods: the handshake check will be skipped'
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
# The freshly compiled classes come before the ones gradle built, so what runs is the working tree
# rather than the last build.
# Recurse: a check that has to reach package-private members lives in that package's directories
# rather than in this one, so a top-level-only listing would compile every check but those.
$harnessSources = Get-ChildItem $here -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -proc:none -nowarn -cp "$modClasses;$harnessPath" -d $harnessClasses `
    $harnessSources
if ($LASTEXITCODE -ne 0) { throw "javac failed on the harness with $LASTEXITCODE" }

Write-Host 'running the harness...'
Write-Host ''
# From the harness's own directory: the logging setup that comes with the game's classpath writes a
# logs/ folder into the working directory, and it belongs next to the harness's other output rather
# than at the root of the project.
Push-Location $work
& java -cp "$harnessClasses;$modClasses;$harnessPath" Harness
$harnessCode = $LASTEXITCODE
Pop-Location
exit $harnessCode
