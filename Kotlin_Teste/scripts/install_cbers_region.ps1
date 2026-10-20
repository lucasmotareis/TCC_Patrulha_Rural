param(
    [string]$Serial = 'RXCWA0634NV',
    [string]$PackageRoot = (Join-Path $PSScriptRoot '../../dados_qgis/cbers_8bpm_completo')
)
$ErrorActionPreference = 'Stop'
$CbersRoot = (Resolve-Path -LiteralPath $PackageRoot).Path
$CbersReceipt = Get-Content -LiteralPath (Join-Path $CbersRoot 'package-receipt.json') -Raw | ConvertFrom-Json
$CbersManifestFile = Join-Path $CbersRoot 'package/package.json'
$CbersManifest = Get-Content -LiteralPath $CbersManifestFile -Raw | ConvertFrom-Json
$CbersArchive = (Resolve-Path -LiteralPath (Join-Path $CbersRoot 'cbers8bpm-v1.tar')).Path
$CbersAdb = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../.tools/sdk/platform-tools/adb.exe')).Path
if ($Serial -notmatch '^[A-Za-z0-9_:.-]+$') { throw 'Serial Android inválido.' }
if ($CbersManifest.packageId -ne 'cbers8bpm-v1' -or !$CbersManifest.complete) { throw 'Pacote incompleto.' }
if ((Get-Item -LiteralPath $CbersArchive).Length -ne $CbersReceipt.bytes) { throw 'Tamanho do TAR incorreto.' }
$CbersLocalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $CbersArchive).Hash.ToLowerInvariant()
if ($CbersLocalHash -ne $CbersReceipt.sha256) { throw 'Hash do TAR incorreto.' }

function Invoke-CbersAdb {
    param([string[]]$AdbArguments)
    & $CbersAdb -s $Serial @AdbArguments
    if ($LASTEXITCODE -ne 0) { throw "ADB falhou: $($AdbArguments -join ' ')" }
}

$CbersDeviceRoot = '/sdcard/Android/data/br.com.palmasoffline/files/maps'
$CbersDeviceStage = "$CbersDeviceRoot/cbers8bpm-v1.incoming"
$CbersDeviceFinal = "$CbersDeviceRoot/cbers8bpm-v1"
$CbersDeviceArchive = "$CbersDeviceRoot/cbers8bpm-v1.transfer.tar"
Write-Output 'Conferindo aparelho e espaço disponível.'
Invoke-CbersAdb -AdbArguments @('get-state')
$CbersDisk = Invoke-CbersAdb -AdbArguments @('shell','df','-k','/sdcard')
$CbersFree = [long](($CbersDisk[-1].Trim() -split '\s+')[3]) * 1024
$CbersRequired = [long]$CbersReceipt.bytes + [long]$CbersManifest.tileBytes + 1GB
if ($CbersFree -lt $CbersRequired) { throw "Espaço insuficiente: necessário aproximadamente $CbersRequired bytes." }
Invoke-CbersAdb -AdbArguments @('shell','am','force-stop','br.com.palmasoffline')
Invoke-CbersAdb -AdbArguments @('shell','mkdir','-p',$CbersDeviceStage)

Write-Output "Transferindo $($CbersReceipt.bytes) bytes do mapa regional."
Invoke-CbersAdb -AdbArguments @('push','-Z',$CbersArchive,$CbersDeviceArchive)
Write-Output 'Verificando o SHA-256 do arquivo recebido no celular.'
$CbersRemoteDigest = Invoke-CbersAdb -AdbArguments @('shell','sha256sum',$CbersDeviceArchive)
if (($CbersRemoteDigest -split '\s+')[0] -ne $CbersReceipt.sha256) { throw 'O TAR recebido não corresponde ao original.' }
Write-Output 'Extraindo os blocos no armazenamento do aplicativo.'
Invoke-CbersAdb -AdbArguments @('shell','tar','-xf',$CbersDeviceArchive,'-C',$CbersDeviceStage)

# Only numeric tile paths from our generated manifest enter the verification script.
$CbersChecks = [Collections.Generic.List[string]]::new()
$CbersChecks.Add('#!/system/bin/sh')
$CbersChecks.Add('set -eu')
$CbersChecks.Add("cd '$CbersDeviceStage'")
$CbersChecks.Add('count=$(find tiles -type f -name ''*.png'' | wc -l)')
$CbersChecks.Add(('[ "$count" -eq {0} ]' -f $CbersManifest.tileCount))
$CbersChecks.Add(('printf ''%s  %s\n'' ''{0}'' ''package.json'' | sha256sum -c -' -f $CbersReceipt.manifestSha256))
$CbersChecks.Add(('printf ''%s  %s\n'' ''{0}'' ''tile-index.tsv'' | sha256sum -c -' -f $CbersManifest.indexSha256))
foreach ($CbersProbe in $CbersManifest.probes) {
    if ($CbersProbe.path -notmatch '^tiles/\d+/\d+/\d+\.png$' -or $CbersProbe.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Amostra de verificação inválida.' }
    $CbersChecks.Add(('printf ''%s  %s\n'' ''{0}'' ''{1}'' | sha256sum -c -' -f $CbersProbe.sha256,$CbersProbe.path))
}
$CbersChecks.Add('echo VERIFIED')
$CbersCheckFile = Join-Path $CbersRoot 'verify-device.sh'
[IO.File]::WriteAllText($CbersCheckFile,($CbersChecks -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
Invoke-CbersAdb -AdbArguments @('push',$CbersCheckFile,'/data/local/tmp/cbers8bpm-verify.sh')
Write-Output 'Conferindo quantidade de blocos, índice e imagens dos onze municípios.'
$CbersVerification = Invoke-CbersAdb -AdbArguments @('shell','sh','/data/local/tmp/cbers8bpm-verify.sh')
if ($CbersVerification[-1].Trim() -ne 'VERIFIED') { throw 'A verificação no aparelho não foi concluída.' }

# Keep a prior map recoverable if this script is used for a later installation.
Invoke-CbersAdb -AdbArguments @('shell',"if [ -d '$CbersDeviceFinal' ]; then if [ -e '$CbersDeviceFinal.previous' ]; then exit 8; fi; mv '$CbersDeviceFinal' '$CbersDeviceFinal.previous'; fi")
Invoke-CbersAdb -AdbArguments @('shell','mv',$CbersDeviceStage,$CbersDeviceFinal)
$CbersInstalled = [ordered]@{
    installedAt = [DateTimeOffset]::Now.ToString('o')
    device = $Serial
    directory = $CbersDeviceFinal
    archiveSha256 = $CbersReceipt.sha256
    manifestSha256 = $CbersReceipt.manifestSha256
    tileCount = $CbersManifest.tileCount
    tileBytes = $CbersManifest.tileBytes
    probesVerified = $CbersManifest.probes.Count
    status = 'installed_and_verified'
}
$CbersInstalled | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $CbersRoot 'device-installation.json') -Encoding utf8
Write-Output 'Mapa publicado e verificado. Removendo somente o TAR temporário da transferência.'
Invoke-CbersAdb -AdbArguments @('shell','rm',$CbersDeviceArchive)
Invoke-CbersAdb -AdbArguments @('shell','am','force-stop','br.com.palmasoffline')
Invoke-CbersAdb -AdbArguments @('shell','am','start','-n','br.com.palmasoffline/.MainActivity')
Write-Output 'Instalação do mapa regional concluída.'
