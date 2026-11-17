# Build helper: always uses JDK 17.
#
# This project targets Minecraft 1.20.1 / Forge 47.x, so both the Gradle daemon
# and the Java toolchain must run on JDK 17. Gradle 8.14.x does not support
# JDK 25, and the ambient JAVA_HOME may point there, so the JDK is selected
# explicitly here and exported to the Gradle launcher.
#
# Usage:
#   .\build.ps1                # gradlew build
#   .\build.ps1 clean build    # any gradle arguments are passed through
#   .\build.ps1 --offline build
#
# Override the JDK by setting the STAR_SWORD_JDK17 environment variable.

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs = @('build')
)

$ErrorActionPreference = 'Stop'

function Test-Jdk17([string] $path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return $false }
    if (-not (Test-Path (Join-Path $path 'bin\java.exe'))) { return $false }
    # A JDK ships a 'release' descriptor; reading it avoids spawning java.exe
    # (whose -version output goes to stderr and would trip $ErrorActionPreference).
    $release = Join-Path $path 'release'
    if (Test-Path $release) {
        $content = Get-Content $release -Raw -ErrorAction SilentlyContinue
        if ($content -match 'JAVA_VERSION="(\d+)') { return ([int]$Matches[1] -eq 17) }
    }
    return $false
}

function Find-Jdk17 {
    $candidates = @($env:STAR_SWORD_JDK17, 'C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot')
    $candidates += Get-ChildItem 'C:\Program Files\*\jdk*17*', 'C:\Program Files\Java\jdk*17*' `
        -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
    foreach ($candidate in $candidates) {
        if (Test-Jdk17 $candidate) { return $candidate }
    }
    return $null
}

$jdk17 = Find-Jdk17
if (-not $jdk17) {
    Write-Error ('JDK 17 is required to build this project but was not found. ' +
        'Install JDK 17, or point STAR_SWORD_JDK17 at its installation directory.')
    exit 1
}

$env:JAVA_HOME = $jdk17
Write-Host "JAVA_HOME = $jdk17" -ForegroundColor DarkGray

# Gradle and its annotation processors write progress/diagnostics to stderr. With
# $ErrorActionPreference = 'Stop' (needed above for the JDK probing) PowerShell turns
# every redirected stderr line into a terminating error, which used to kill the build
# midway through :compileJava. Only the exit code is authoritative here.
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & (Join-Path $PSScriptRoot 'gradlew.bat') @GradleArgs
    $gradleExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousEap
}
exit $gradleExit
