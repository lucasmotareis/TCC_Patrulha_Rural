# Fundo de satélite CBERS-4A — teste offline

A versão 0.5.1 acrescenta uma amostra de imagem do INPE ao mapa do 8º BPM. A amostra cobre apenas uma pequena área do Onalício Barros; não representa cobertura de satélite dos onze municípios. O mapa vetorial e o grafo de rotas continuam disponíveis na região já empacotada.

## Usar

- **Satélite (amostra)** mostra a fotografia com estradas, assentamentos e rotas por cima.
- **Vetorial** permite comparar o mesmo local com o desenho do mapa.
- **Ver amostra** enquadra a área fotografada.
- Selecione **Vetorial** e depois **Área do 8º BPM** para retornar à visão regional. Fora da amostra, o fundo continua vetorial.

A imagem está incluída no APK. O aplicativo não solicita permissão de internet e não acessa um servidor de imagens durante o uso. Seleções de origem/destino e rotas não precisam ser apagadas para trocar o fundo.

## Pacote para o celular

O APK de teste é específico para **ARM64**, a arquitetura do Galaxy A34. O pacote universal foi recusado por falta de espaço; a variante ARM64 omite bibliotecas destinadas a outros processadores, mantendo os mesmos mapas e funcionalidades.

Para reproduzir essa variante, com JDK e SDK configurados:

```powershell
.\gradlew.bat :app:assembleDebug :app:connectedDebugAndroidTest -PtargetAbi=arm64-v8a
```

Sem `-PtargetAbi`, a compilação continua incluindo as arquiteturas suportadas pelas dependências.

## Fonte e licença

Coleção oficial: [CBERS-4A/WPM RGB fusionado — INPE](https://data.inpe.br/bdc/stac/v1/collections/CB4A-WPM-PCA-FUSED-1).

Cena selecionada: `CBERS4A_WPM_PCA_RGB321_20260804_210_125`, adquirida em **04/08/2026**. A cor resulta da fusão de bandas multiespectrais de 8 m com a pancromática de 2 m; não é uma captura multiespectral nativa de 2 m. A resolução efetiva também depende da aquisição e do processamento.

Licença da coleção: [Creative Commons Atribuição 4.0 Internacional](https://creativecommons.org/licenses/by/4.0/deed.pt-br). A distribuição deve manter o crédito ao INPE, o link da licença e a indicação dos processamentos. A licença da imagem não altera as licenças das camadas vetoriais.

Os metadados, limites geográficos, hash da imagem e processamentos estão em `app/src/main/batalhaoAssets/batalhao/satellite/sample.json` e `NOTICE.md`. Fontes e arquivos intermediários ficam em `../dados_qgis/cbers_8bpm_teste/`.

## Escopo do teste

O recorte é limitado à região do 8º BPM e preparado em Web Mercator para sobreposição no MapLibre. A cobertura e a nitidez desta amostra não comprovam disponibilidade equivalente em toda a região.

Com uma rota já calculada, recriar a tela (por exemplo, ao girar o aparelho) pode voltar a enquadrar a rota. **Ver amostra** retorna à área fotografada; essa situação é distinta da alternância entre os dois fundos, que preserva a câmera.

Uma imagem de satélite serve como referência visual. Ela não comprova a condição atual de uma estrada, ponte ou porteira nem altera automaticamente o grafo de navegação. A precisão e a transitabilidade devem ser verificadas em campo.

## Validação em 20/10/2026

- APK ARM64 0.5.1, código 7, instalado no Galaxy A34 com Android 16 e aberto na amostra ao finalizar.
- 19 testes unitários aprovados; lint sem erros, com 11 avisos (atualizações de dependências e sugestões já registradas no relatório).
- Os 19 casos instrumentados existentes passaram na execução integral. Os dois casos novos de satélite passaram após corrigir a busca de controles na árvore de acessibilidade e aguardar o renderer ficar ocioso antes das capturas. O APK do produto não mudou durante essas correções de teste: 21 casos distintos validados.
- As capturas confirmaram a imagem sob as camadas vetoriais, seleção do Onalício, rota Caseara–Onalício e alternância sem alterar câmera, geometria canônica da rota ou destino. Aproximadamente 39,9% dos pixels mudaram ao ocultar a imagem; reativá-la restaurou exatamente os pixels da captura inicial.
- A máscara geográfica da amostra apresentou zero pixels visíveis fora da região dos 11 municípios.

Entrega e relatório: `../exportacoes/8bpm/2026-10-20/cbers-teste/`. O APK tem 59.242.608 bytes e SHA-256 `72f9cea4ff6f08d64dc2901a80ff2630a3980aa7fefae21ba5c23cfba1fa2647`. Capturas do renderer e da interface foram inspecionadas. A imagem e as rotas funcionam com recursos locais; não foi realizado deslocamento físico em campo nem alterada a conectividade do celular.
