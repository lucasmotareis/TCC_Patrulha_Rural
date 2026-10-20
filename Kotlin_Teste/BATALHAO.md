# Caso do batalhão — Vale do Araguaia

## Pacote vetorial 0.5.0 — 20/10/2026

O mapa visual usa a união exata dos 11 municípios do IBGE 2024, sem margem. São nove camadas GeoJSON: estradas, áreas da base OSM, cursos d'água, localidades, região, municípios, pontos dos assentamentos, perímetros CIGMA/INCRA e o acesso revisado ao Onalício Barros. As fontes originais do QGIS são preservadas.

Os pontos da lista compartilhada são filtrados pela região e conservam seus atributos de revisão. O registro 40, declarado incorreto pelo usuário, é excluído. Os perímetros são recortados na divisa; o atributo de área fornecido pela origem não é recalculado e continua representando a área original.

O app abre enquadrado na região. Tocar um assentamento usa a coordenada exata da feição como destino e mostra seu nome e sua situação de revisão. O marcador não é tratado como uma entrada confirmada. O botão **Área do 8º BPM** retorna à visão regional.

A base de rotas é `batalhao_onalicio_v1.osm.pbf` / `onalicio_graph_v1/valhalla_tiles.tar`, já testada em 20/10, incluindo Chácara 2 Meninos e Onalício Barros. O gerador exige que o PBF e o TAR correspondam aos hashes registrados em `dados_batalhao/onalicio_teste/validacao_onalicio.json`. O grafo preserva a margem operacional de 15 km; o envelope de seleção/GPS também permanece com essa margem.

Metadados em `app/src/main/batalhaoAssets/batalhao/metadata.json` registram `visualBounds`, `routingBounds`, contagens, exclusões, fontes e hashes. `validation_report.json` registra a verificação de validade e de inclusão de todas as geometrias na união municipal. O estilo e as fontes são locais; não há imagens nem requisições de internet.

Para reproduzir o pacote a partir das fontes locais já presentes:

```powershell
$env:PYTHONPATH = "$PWD\.tools\map-python;$PWD\.tools\batalhao-python"
python scripts/package_batalhao.py --assets app/src/main/batalhaoAssets
```

O gerador aceita caminhos explícitos para `--osm`, `--routing-tar`, `--routing-validation`, `--region`, `--coverage`, `--settlements`, `--settlement-areas`, `--access-roads` e `--version`. Uma nova revisão de estradas deve ser conectada e validada antes do empacotamento. Atualizações continuam sendo entregues como APK; o ZIP exportado é uma cópia dos recursos para integração, sem importador no celular nesta versão.

### Resultado e validação da versão 0.5.0

- Recursos: 15.261.083 bytes; ZIP: 5.083.959 bytes; APK: 107.365.140 bytes, incluindo bibliotecas nativas.
- 5.080 vias, 839 áreas, 1.071 cursos d'água, 52 localidades, 11 municípios, 1 região, 51 marcadores, 38 perímetros e 1 acesso operacional destacado.
- Os 51 marcadores conservam 49 situações não revisadas e 2 pendentes. Além do ID 40 incorreto, ficaram fora por coordenada os IDs 46, 47, 49, 50, 52, 53, 54, 55 e 57. O nome do município no texto não substituiu o filtro espacial.
- 9 testes Python aprovados, incluindo validade/recorte de todas as geometrias e integridade do grafo; estilo aprovado pelo validador MapLibre.
- 19 testes unitários aprovados. APK e lint concluídos; lint com zero erros e três avisos.
- 19 testes instrumentados aprovados no Samsung Galaxy A34, Android 16, em 20/10/2026, sem falhas, erros ou testes ignorados; 39,638 segundos de testes.
- O motor Android confirmou Caseara–Onalício nos dois sentidos, aproximadamente 40,866 km, usando o novo acesso. O renderer confirmou o ponto, seleção por toque com atualização de B e preenchimento/contorno dos perímetros.
- Instalação da versão 0.5.0 (versionCode 6) confirmada. Captura regional inspecionada; app reaberto ao terminar. Não houve deslocamento real em campo nem alteração da conectividade.

Entrega em `exportacoes/8bpm/2026-10-20/`, com APK, ZIP, captura e `validacao.json`. Evidências técnicas em `Kotlin_Teste/.tools/8bpm-vetorial-validation/`. SHA-256 do APK: `2fbd43773f9402d95a4b220dbf78e47f58f405a2629a87bd39c9bad20b90e9f3`. SHA-256 do ZIP: `6e379f2da1fc9c1e369880977febfbae628bef8b01a6d94392d13fe6f294f907`.

## Base inicial — Chácara 2 Meninos (14/10/2026)

Cobertura: Paraíso do Tocantins, Pugmil, Chapada de Areia, Monte Santo do Tocantins, Barrolândia, Divinópolis do Tocantins, Marianópolis do Tocantins, Caseara, Abreulândia, Dois Irmãos do Tocantins e Araguacema. Área municipal somada: 18.679 km² (IBGE 2024). O recorte inclui margem de 15 km para preservar conexões nas divisas.

O desenho do usuário tinha 2.812 m; aproximadamente 2.065 m estavam a até 10 m de estradas existentes. A inspeção identificou a via OSM 913318451 como o caminho já cadastrado. Foi mantida sua geometria e acrescentado somente o ramal terminal ausente, de 435 m. A junção foi alinhada em 15,35 m à geometria existente. O desenho original está preservado no GeoPackage do QGIS.

O destino é o final do desenho mais recente: longitude -49.18961793050047, latitude -10.119233731352583, diferente da coordenada aproximada mencionada anteriormente. O usuário declarou autorização de uso pelo batalhão; `access=yes` vale para esta base operacional local. Não houve publicação no OpenStreetMap. Piso não informado, sem classificação inventada; via classificada como acesso (`highway=service`).

## Validação e tamanho da base inicial

- Base OSM/Geofabrik Norte de 2026-10-05, preservando IDs e relações de restrição.
- 6.800 trechos de referência; 6.801 após a inclusão.
- Antes: nenhuma via a até 100 m do destino. Depois: rota de 3,555 km desde Chapada de Areia, incluindo o novo acesso nas instruções.
- Rotas de Paraíso para as outras dez sedes calculadas com sucesso.
- Grafo: 4.014.080 bytes, 72 tiles. Recursos locais (vetores, grafo e fontes): aproximadamente 12,2 MB antes da compressão do APK.
- Quatro testes do conversor verificam nó compartilhado e preservação de restrições, acesso desconectado, cruzamento interior não revisado e estrada duplicada.

Relatórios: `dados_batalhao/validacao_rotas.json`, `conexoes_chacara_v1.json` e `acesso_chacara_manifest.json`. Distâncias e tempos vêm do motor; não houve deslocamento medido em campo. A geometria da junção e as condições de circulação devem ser conferidas em campo.

### Validação no celular — 14/10/2026

Versão 0.4.0 instalada e testada no Samsung Galaxy A34 (SM-A346M), Android 16. O comando `:app:connectedDebugAndroidTest` concluiu com 14 testes aprovados, zero falhas, erros ou testes ignorados; execução dos testes em 72,743 segundos.

- Motor nativo calculou a rota de Chapada à Chácara 2 Meninos, com distância entre 3,4 e 3,7 km, instrução contendo o nome do acesso e chegada no final do desenho.
- Renderizador nativo desenhou as vias locais e enquadrou a rota calculada em Paraíso.
- Simulação chegou ao destino; desvio provocou recálculo local; navegação pausou em segundo plano e descartou resultados de cálculos cancelados.
- Pedido ao provedor GPS por satélite em alta precisão foi ativado e liberado ao cancelar. Isso verifica a integração, sem comprovar precisão ou recepção em campo.
- Recursos locais e ausência da permissão INTERNET confirmados. A conectividade do aparelho não foi alterada.
- Cópia do grafo corrompida, mantendo o mesmo tamanho, foi recuperada a partir dos recursos do APK.

Evidências locais: `.tools/batalhao-device-validation/results.xml` e `.tools/batalhao-device-validation/navigation-simulation.png`. A captura da simulação em Paraíso foi inspecionada: mapa, traçado, posição simulada e controles visíveis. O aplicativo foi aberto novamente ao término dos testes. A simulação usa posições virtuais; não houve percurso físico até a chácara.

## Arquivos

- Projeto QGIS: `dados_qgis/vale_araguaia_11_municipios/batalhao_rotas.qgz`.
- Desenho original: `dados_qgis/vale_araguaia_11_municipios/estradas_batalhao.gpkg`, camada `novas_estradas`.
- Ramal conectado: `dados_batalhao/acesso_chacara_novo.geojson`.
- Rota calculada: `dados_batalhao/rota_validada_chapada_chacara.geojson`.
- Recursos Android: `app/src/main/batalhaoAssets/`.

No QGIS, azul identifica a rota calculada e laranja o ramal novo. Nenhuma fotografia de satélite foi incluída no aplicativo.

## Fluxo para gerar

Instale `scripts/requirements-batalhao.txt` em um ambiente Python. Nesta máquina, osmium/shapely estão em `.tools/map-python` e pyproj em `.tools/batalhao-python`; inclua ambos no `PYTHONPATH`.

1. `prepare_batalhao_base.py --osm <norte.osm.pbf> --coverage <cobertura_com_margem.geojson> --output <pasta>` recorta a base preservando referências.
2. Desenhe os acessos no QGIS com aderência às estradas. Exporte GeoJSON EPSG:4326. Preencha `nome`, `highway`, `access`, `oneway` e `status=revisado`; `surface` pode ficar vazio quando desconhecido.
3. `merge_batalhao_roads.py --base <base.osm.pbf> --roads <acessos.geojson> --output <nova_versao.osm.pbf> --report <conexoes.json>` conecta extremidades com tolerância padrão de 5 m. Rejeita sobreposições extensas, conexões ambíguas e cruzamentos interiores que exigem revisão. Não é um conversor geral de qualquer rede. `prepare_chacara_access.py` registra a reconciliação específica deste desenho com a estrada existente.
4. Execute `build_batalhao_graph.py --input /work/<nova_versao.osm.pbf> --name <nome_novo>` no contêiner Valhalla, montando dados em `/work`, scripts em `/scripts` e bancos auxiliares em `/reference`. Foi usada a imagem local `ghcr.io/valhalla/valhalla-scripted`, ID `eb31ed81d981`, Valhalla 3.9.1-f28832966. A configuração inicial é `/work/base_graph/valhalla.json`. O script constrói em disco temporário Linux e copia o TAR final para Windows. A primeira tentativa diretamente no bind mount apresentou falha de E/S e não foi utilizada.
5. `package_batalhao.py --data <dados_batalhao> --coverage <cobertura_com_margem.geojson> --assets app/src/main/batalhaoAssets` gera os recursos. Os padrões atuais usam a base Onalício e o relatório validado de 20/10; forneça os caminhos explícitos e um relatório compatível ao gerar outra versão.
6. Execute `build-prototype.ps1 -JavaHome <JDK21>` para testes unitários, APK e lint; com Android conectado, execute `gradlew.bat :app:connectedDebugAndroidTest`.

O app verifica o SHA-256 do grafo e instala uma cópia identificada por esse hash. Não depende apenas do tamanho: grafos diferentes podem ocupar o mesmo número de bytes. O pacote Android continua `br.com.palmasoffline` para preservar a instalação existente; o nome exibido é Patrulha Rural.

## Limitações atuais

- Atualizações ainda são distribuídas em novo APK; download independente de pacotes e sincronização automática com QGIS não foram implementados.
- Perfil de automóvel padrão do Valhalla, sem perfil específico de viatura 4x4.
- A checagem inicial de cobertura usa o envelope da região com margem; o roteador confere a presença de estrada a até 100 m do ponto solicitado.
- Testes instrumentados aprovados no Galaxy A34/Android 16; uso com deslocamento real em campo continua pendente.
- O download anterior de imagens do Tocantins continua pausado e não é necessário para este fluxo vetorial.
