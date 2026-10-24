param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$work = Join-Path $repo 'build/review-check/network'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$init = Join-Path $work 'classpath.gradle'
$classpathFile = Join-Path $work 'classpath.txt'
@'
allprojects {
    tasks.register('reviewNetworkClasspath') {
        dependsOn 'classes', 'clientClasses'
        doLast {
            new File(project.property('reviewClasspathFile')).text =
                (sourceSets.main.runtimeClasspath + sourceSets.client.runtimeClasspath).asPath
        }
    }
}
'@ | Set-Content -LiteralPath $init -Encoding UTF8
Push-Location $repo
try {
    & .\gradlew.bat --offline -I $init "-PreviewClasspathFile=$classpathFile" reviewNetworkClasspath
    if ($LASTEXITCODE -ne 0) { throw 'Could not obtain the production runtime classpath.' }
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    & (Join-Path $JavaHome 'bin/javac.exe') -d $work (Join-Path $PSScriptRoot 'NetworkRegression.java')
    if ($LASTEXITCODE -ne 0) { throw 'Regression harness compilation failed.' }
    $arguments = Join-Path $work 'java.args'
    @('-cp', ('"' + ($work + ';' + $classpath).Replace('\', '/') + '"'), 'NetworkRegression') |
        Set-Content -LiteralPath $arguments -Encoding ASCII
    & (Join-Path $JavaHome 'bin/java.exe') "@$arguments"
    if ($LASTEXITCODE -ne 0) { throw 'Network regression failed.' }
} finally {
    Pop-Location
}
