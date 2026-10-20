$ErrorActionPreference='Stop'
$CbersWorkspace=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$CbersWork=Join-Path $CbersWorkspace 'dados_qgis/cbers_8bpm_completo'
$CbersPython='C:\Program Files\QGIS 4.2.3\bin\python-qgis.bat'
$CbersScript=Join-Path $PSScriptRoot 'prepare_cbers_region.py'
Write-Output 'Aguardando a conclusão das cenas de alta resolução.'
while (!(Select-String -LiteralPath (Join-Path $CbersWork 'warp.log') -SimpleMatch '"stage": "sources_complete"' -Quiet)) {
    if (Select-String -LiteralPath (Join-Path $CbersWork 'warp.log') -SimpleMatch 'Traceback (most recent call last)' -Quiet) {
        throw 'A preparação das fontes falhou; confira warp.log.'
    }
    Start-Sleep -Seconds 15
}
foreach($CbersStage in @('levels','tiles','package')) {
    Write-Output "Iniciando etapa: $CbersStage"
    & $CbersPython $CbersScript $CbersStage
    if($LASTEXITCODE -ne 0) { throw "Falha na etapa $CbersStage" }
}
Write-Output 'Pacote regional preparado e pronto para transferência.'
