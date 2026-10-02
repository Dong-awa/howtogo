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
$sources = Get-ChildItem (Join-Path $project 'src\main\java') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -proc:none -nowarn -cp $compilePath -d $modClasses $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed on the mod with $LASTEXITCODE" }

Write-Host 'compiling the harness...'
# The freshly compiled classes come before the ones gradle built, so what runs is the working tree
# rather than the last build.
& javac -encoding UTF-8 -proc:none -nowarn -cp "$modClasses;$compilePath" -d $harnessClasses `
    (Join-Path $here 'Harness.java')
if ($LASTEXITCODE -ne 0) { throw "javac failed on the harness with $LASTEXITCODE" }

Write-Host 'running the harness...'
Write-Host ''
# From the harness's own directory: the logging setup that comes with the game's classpath writes a
# logs/ folder into the working directory, and it belongs next to the harness's other output rather
# than at the root of the project.
Push-Location $work
& java -cp "$harnessClasses;$modClasses;$compilePath" Harness
$harnessCode = $LASTEXITCODE
Pop-Location
exit $harnessCode
