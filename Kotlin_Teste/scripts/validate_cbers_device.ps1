param(
    [string]$Serial = 'RXCWA0634NV',
    [int]$ExpectedTests = 24
)
$ErrorActionPreference = 'Stop'
if($Serial -notmatch '^[A-Za-z0-9_:.-]+$') { throw 'Serial Android inválido.' }
$CbersWorkspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$CbersWork = Join-Path $CbersWorkspace 'dados_qgis/cbers_8bpm_completo'
$CbersAdb = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../.tools/sdk/platform-tools/adb.exe')).Path
$CbersTestApk = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')).Path
$CbersReceipt = Get-Content -LiteralPath (Join-Path $CbersWork 'package-receipt.json') -Raw | ConvertFrom-Json
$CbersInstalled = Get-Content -LiteralPath (Join-Path $CbersWork 'device-installation.json') -Raw | ConvertFrom-Json
$CbersManifest = Get-Content -LiteralPath (Join-Path $CbersWork 'package/package.json') -Raw | ConvertFrom-Json
if($CbersInstalled.status -ne 'installed_and_verified' -or $CbersInstalled.device -ne $Serial -or $CbersInstalled.archiveSha256 -ne $CbersReceipt.sha256) {
    throw 'Instalar e verificar este pacote no aparelho antes dos testes.'
}
$CbersOutput = Join-Path $CbersWork 'device-validation'
New-Item -ItemType Directory -Path $CbersOutput -Force | Out-Null

function Invoke-CbersDevice {
    param([string[]]$DeviceArguments)
    & $CbersAdb -s $Serial @DeviceArguments
    if($LASTEXITCODE -ne 0) { throw "ADB falhou: $($DeviceArguments -join ' ')" }
}

function Save-CbersBinary {
    param([string]$RemoteArguments,[string]$Destination)
    $CbersStart = [Diagnostics.ProcessStartInfo]::new()
    $CbersStart.FileName = $CbersAdb
    $CbersStart.Arguments = "-s $Serial $RemoteArguments"
    $CbersStart.UseShellExecute = $false
    $CbersStart.CreateNoWindow = $true
    $CbersStart.RedirectStandardOutput = $true
    $CbersStart.RedirectStandardError = $true
    $CbersProcess = [Diagnostics.Process]::Start($CbersStart)
    $CbersStream = [IO.File]::Create($Destination)
    try { $CbersProcess.StandardOutput.BaseStream.CopyTo($CbersStream) }
    finally { $CbersStream.Dispose() }
    $CbersError = $CbersProcess.StandardError.ReadToEnd()
    $CbersProcess.WaitForExit()
    if($CbersProcess.ExitCode -ne 0) { throw "Falha ao coletar captura: $CbersError" }
    $CbersProcess.Dispose()
}

Write-Output 'Instalando o APK de testes e executando a suíte no mapa regional instalado.'
Invoke-CbersDevice -DeviceArguments @('install','-r',$CbersTestApk)
$CbersRawLog = Join-Path $CbersOutput 'instrumentation.log'
# Keep the screen awake across tests that temporarily close their Activity.
# FLAG_KEEP_SCREEN_ON alone cannot wake a display that was already asleep.
$CbersOriginalStayOn = (Invoke-CbersDevice -DeviceArguments @('shell','settings','get','global','stay_on_while_plugged_in') | Out-String).Trim()
if($CbersOriginalStayOn -notmatch '^(?:[0-7]|null)$') { throw 'Configuração de tela inesperada.' }
try {
    Invoke-CbersDevice -DeviceArguments @('shell','svc','power','stayon','usb')
    Invoke-CbersDevice -DeviceArguments @('shell','input','keyevent','KEYCODE_WAKEUP')
    Invoke-CbersDevice -DeviceArguments @('shell','wm','dismiss-keyguard')
    & $CbersAdb -s $Serial shell am instrument -w -r br.com.palmasoffline.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath $CbersRawLog
    $CbersRunnerExit = $LASTEXITCODE
} finally {
    if($CbersOriginalStayOn -eq 'null') {
        Invoke-CbersDevice -DeviceArguments @('shell','settings','delete','global','stay_on_while_plugged_in')
    } else {
        Invoke-CbersDevice -DeviceArguments @('shell','settings','put','global','stay_on_while_plugged_in',$CbersOriginalStayOn)
    }
}
$CbersRaw = Get-Content -LiteralPath $CbersRawLog -Raw
$CbersTests = [Collections.Generic.List[object]]::new()
$CbersStatus = @{}
$CbersStartedClass = $null
$CbersStartedMethod = $null
foreach($CbersLine in ($CbersRaw -split '\r?\n')) {
    if($CbersLine -match '^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$') {
        $CbersStatus[$Matches[1]] = $Matches[2]
    } elseif($CbersLine -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)') {
        $CbersCode = [int]$Matches[1]
        if($CbersCode -eq 1) {
            $CbersStartedClass = $CbersStatus['class']
            $CbersStartedMethod = $CbersStatus['test']
        } elseif($CbersCode -le 0) {
            $CbersMethod = $CbersStatus['test']
            $CbersClass = $CbersStatus['class']
            if(!$CbersMethod) { $CbersMethod = $CbersStartedMethod; $CbersClass = $CbersStartedClass }
            if($CbersMethod) {
                $CbersTests.Add([ordered]@{ class=$CbersClass; method=$CbersMethod; statusCode=$CbersCode; stack=$CbersStatus['stack'] })
            }
        }
        $CbersStatus = @{}
    }
}
$CbersPassed = @($CbersTests | Where-Object { $_.statusCode -eq 0 }).Count
$CbersSuccess = $CbersRunnerExit -eq 0 -and $CbersTests.Count -eq $ExpectedTests -and $CbersPassed -eq $ExpectedTests -and $CbersRaw -match "OK \($ExpectedTests tests?\)"
$CbersResult = [ordered]@{
    recordedAt = [DateTimeOffset]::Now.ToString('o')
    device = $Serial
    runner = 'adb am instrument / AndroidJUnitRunner'
    archiveSha256 = $CbersReceipt.sha256
    testApkSha256 = (Get-FileHash -LiteralPath $CbersTestApk -Algorithm SHA256).Hash.ToLowerInvariant()
    status = $(if($CbersSuccess) { 'passed' } else { 'failed' })
    tests = $CbersTests.Count
    passed = $CbersPassed
    failedOrSkipped = $CbersTests.Count-$CbersPassed
    cases = $CbersTests.ToArray()
}
$CbersResult | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $CbersOutput 'test-results.json') -Encoding utf8
if(!$CbersSuccess) { throw 'A suíte não passou integralmente. Conferir instrumentation.log e test-results.json.' }

Write-Output 'Coletando as imagens e medidas dos onze municípios.'
foreach($CbersProbe in ($CbersManifest.probes | Where-Object { $_.zoom -eq $CbersManifest.maxZoom })) {
    $CbersCode = [string]$CbersProbe.code
    if($CbersCode -notmatch '^\d{7}$') { throw 'Código municipal inválido.' }
    Save-CbersBinary -RemoteArguments "exec-out run-as br.com.palmasoffline cat files/regional-satellite-test/$CbersCode.png" -Destination (Join-Path $CbersOutput "$CbersCode.png")
}
foreach($CbersName in @('municipalities','file-fixture')) {
    $CbersJson = Invoke-CbersDevice -DeviceArguments @('exec-out','run-as','br.com.palmasoffline','cat',"files/regional-satellite-test/$CbersName.json")
    [IO.File]::WriteAllText((Join-Path $CbersOutput "$CbersName.json"),($CbersJson -join "`n"),[Text.UTF8Encoding]::new($false))
}
foreach($CbersName in @('satellite-enabled','vector-only','satellite-restored')) {
    Save-CbersBinary -RemoteArguments "exec-out run-as br.com.palmasoffline cat files/satellite-test/$CbersName.png" -Destination (Join-Path $CbersOutput "$CbersName.png")
}
$CbersComparison = Invoke-CbersDevice -DeviceArguments @('exec-out','run-as','br.com.palmasoffline','cat','files/satellite-test/comparison.json')
[IO.File]::WriteAllText((Join-Path $CbersOutput 'comparison.json'),($CbersComparison -join "`n"),[Text.UTF8Encoding]::new($false))
Invoke-CbersDevice -DeviceArguments @('shell','am','force-stop','br.com.palmasoffline')
Invoke-CbersDevice -DeviceArguments @('shell','am','start','-n','br.com.palmasoffline/.MainActivity')
Start-Sleep -Seconds 3
Save-CbersBinary -RemoteArguments 'exec-out screencap -p' -Destination (Join-Path $CbersOutput 'app-regional.png')
Invoke-CbersDevice -DeviceArguments @('shell','du','-sk','/sdcard/Android/data/br.com.palmasoffline/files/maps/cbers8bpm-v1') | Set-Content -LiteralPath (Join-Path $CbersOutput 'storage.txt')
Write-Output "Validação concluída: $CbersPassed testes passaram. Capturas em $CbersOutput"
