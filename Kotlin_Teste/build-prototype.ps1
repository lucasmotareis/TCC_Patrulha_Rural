param(
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if ([string]::IsNullOrWhiteSpace($JavaHome) -or !(Test-Path (Join-Path $JavaHome 'bin/java.exe'))) {
        throw 'Informe um JDK compatível: .\build-prototype.ps1 -JavaHome C:\caminho\jdk-21'
    }
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.tools/gradle-home'
    $gpsTiles = Join-Path $PSScriptRoot 'app/src/main/batalhaoAssets/batalhao_tiles_v1.tar'
    if (!(Test-Path -LiteralPath $gpsTiles)) {
        throw 'Gere batalhao_tiles_v1.tar com scripts/package_batalhao.py antes de compilar.'
    }
    & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain --max-workers=4
    if ($LASTEXITCODE -ne 0) { throw "Gradle falhou com código $LASTEXITCODE." }
    Write-Host "APK: $(Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk')"
} finally {
    Pop-Location
}
