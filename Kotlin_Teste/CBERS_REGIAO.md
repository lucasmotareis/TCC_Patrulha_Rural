# Satélite regional offline do 8º BPM

Preparação do pacote CBERS-4A dos onze municípios do Vale do Araguaia, a partir da união municipal IBGE 2024 já usada pelo aplicativo. A imagem é recortada no limite municipal, sem margem externa. A base de navegação conserva sua cobertura operacional própria.

## Dados e apresentação

As imagens são da coleção oficial `CB4A-WPM-PCA-FUSED-1`, licenciada pelo INPE sob [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). A composição combina cenas de 2023 a 2026 para preencher as lacunas de cobertura. As datas e os endereços das fontes acompanham o pacote.

O produto original é RGB fusionado por PCA, com pancromática nativa de 2 m e bandas coloridas de 8 m. O aplicativo lê blocos PNG sem perdas, no esquema XYZ, de 256 × 256 pixels, em EPSG:3857. Os níveis 7 a 16 permitem carregar somente a parte visível; o nível 16 corresponde a cerca de 2,39 m/pixel na projeção, aproximadamente 2,35 m/pixel no terreno nesta latitude. Aproximar além desse nível amplia a imagem disponível.

O processamento inclui escolha de cenas, mosaico, reprojeção bilinear, níveis reduzidos por média e reaplicação da máscara em cada nível. Os pixels externos ao recorte ficam transparentes e com canais RGB zerados. Os blocos transparentes também são incluídos, para que a consulta a uma área sem imagem tenha resposta local válida. Vazios internos da fonte são quantificados em `tile-coverage.json`.

O pacote concluído em 20/10/2026 contém 130.555 blocos (7.229.536.232 bytes, ou 7,23 GB). O TAR de transferência tem 7.356.098.560 bytes (7,36 GB), com SHA-256 `05982281cbf299ee41b52dd117f47628542be77bd5b1dbe5c36c4a833d27f889`. Foram usadas 21 cenas de 14/05/2023 a 04/08/2026. A cobertura medida no nível 16 foi de 99,9982% dos pixels internos; 61.103 pixels ficaram sem dados das fontes e nenhum pixel visível ficou fora do recorte.

A versão 0.6.1 do app oculta as linhas azuis de rios e córregos nos modos Satélite e Vetorial. Essa mudança de estilo usa o mesmo pacote de imagens.

## Armazenamento no Android

O mapa é transferido separadamente do APK para a pasta de arquivos do próprio aplicativo:

```text
/sdcard/Android/data/br.com.palmasoffline/files/maps/cbers8bpm-v1/
  package.json
  tile-index.tsv
  source-scenes.json
  region.geojson
  cutlines/{cena}.geojson
  NOTICE.md
  tiles/{z}/{x}/{y}.png
```

`OfflineSatellitePackage` valida o manifesto e arquivos de referência antes de ativar a fonte `RasterSource` com URLs `file://`. O aplicativo continua sem permissão de internet. Sem um pacote regional válido, a amostra do Onalício permanece disponível para teste.

O instalador verifica o SHA-256 do TAR no computador e no celular, extrai para uma pasta temporária, confere o índice, a quantidade de blocos e referências dos onze municípios, e publica a pasta do mapa após a validação. Uma versão anterior, se houver, é preservada como `.previous`. Somente o TAR temporário de transferência é removido após sucesso.

A instalação no Galaxy A34 (Android 16) foi concluída e conferida em 20/10/2026, incluindo os 130.555 blocos e 33 referências em três níveis de zoom. O TAR temporário foi removido do aparelho; a atualização do APK para 0.6.1 preservou o mapa.

## Reprodução

No diretório raiz, usando o Python do QGIS com GDAL, numpy e Pillow:

```powershell
& 'C:\Program Files\QGIS 4.2.3\bin\python-qgis.bat' Kotlin_Teste/scripts/prepare_cbers_region.py survey --dates 2023-03-01/2026-10-20
& 'C:\Program Files\QGIS 4.2.3\bin\python-qgis.bat' Kotlin_Teste/scripts/prepare_cbers_region.py previews --all
& 'C:\Program Files\QGIS 4.2.3\bin\python-qgis.bat' Kotlin_Teste/scripts/prepare_cbers_region.py plan
& 'C:\Program Files\QGIS 4.2.3\bin\python-qgis.bat' Kotlin_Teste/scripts/prepare_cbers_supplements.py
& 'C:\Program Files\QGIS 4.2.3\bin\python-qgis.bat' Kotlin_Teste/scripts/prepare_cbers_region.py warp
& 'C:\Program Files\QGIS 4.2.3\bin\python-qgis.bat' Kotlin_Teste/scripts/prepare_cbers_region.py levels
& 'C:\Program Files\QGIS 4.2.3\bin\python-qgis.bat' Kotlin_Teste/scripts/prepare_cbers_region.py tiles
& 'C:\Program Files\QGIS 4.2.3\bin\python-qgis.bat' Kotlin_Teste/scripts/prepare_cbers_region.py package
```

Os resultados ficam em `../dados_qgis/cbers_8bpm_completo/`. As cenas completas não são baixadas indiscriminadamente: o GDAL lê blocos dos COGs oficiais e prepara as áreas alocadas a cada cena. O auxiliar `prepare_cbers_supplements.py` prepara as alocações esparsas a partir da oitava cena por janelas de 1024 pixels que intersectam o recorte; `warp` prepara as maiores e reaproveita as demais. Arquivos concluídos são marcados e permitem retomar a execução. Uma cobertura ou seleção diferente deve usar outra pasta de trabalho para evitar misturar versões intermediárias.

Compilação ARM64 e validação, dentro de `Kotlin_Teste`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PtargetAbi=arm64-v8a
```

Após instalar o APK, transferir o pacote com o celular autorizado por USB:

```powershell
.\scripts\install_cbers_region.ps1 -Serial RXCWA0634NV
.\gradlew.bat :app:assembleDebugAndroidTest -PtargetAbi=arm64-v8a
.\scripts\validate_cbers_device.ps1 -Serial RXCWA0634NV
```

## Validação

Na versão 0.6.1, os 24 testes instrumentados passaram no Galaxy A34 / Android 16 em 20/10/2026. Foram verificados a leitura PNG por `file://` sem permissão de internet, hashes e dados de referência, a renderização nos onze municípios, as rotas locais, a seleção dos pontos e a alternância de fundos. Ao voltar para Satélite, a captura coincidiu integralmente com a anterior; rota, câmera e destino foram preservados. Também passaram os 19 testes unitários existentes, os 9 testes do pacote vetorial e lint sem erros (12 avisos).

O auxiliar executa `adb am instrument` sobre o pacote instalado, mantém a tela acesa durante os testes e restaura sua configuração ao terminar. Os resultados e capturas estão em `../dados_qgis/cbers_8bpm_completo/device-validation/`. A medição `du -sk` no Android foi de 7.457.447 KiB, aproximadamente 7,64 GB para a pasta do mapa. A exportação com APK, recibos e capturas está em `../exportacoes/8bpm/2026-10-20/cbers-completo/`.

As imagens registram as datas de aquisição e não comprovam condições atuais de estradas, pontes ou acessos. O teste no aparelho não substitui a conferência em campo.
