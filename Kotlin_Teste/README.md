# Palmas Offline

Protótipo Android em Kotlin e Jetpack Compose, com Valhalla Mobile 0.6.4,
Ferrostar 0.57.0 e MapLibre Native 13.6.1 (OpenGL ES).
O mapa visual e o cálculo de rotas usam dados locais incluídos no APK.
O aplicativo não depende do Docker e não possui permissão de internet. GPS é solicitado somente para navegação real.

## Usar no celular

1. Instale `app/build/outputs/apk/debug/app-debug.apk` em um dispositivo Android 7.1 ou superior
   com OpenGL ES 3.0.
2. Use **A · Origem** e **B · Destino** para selecionar o ponto que será alterado por toque.
   Depois de escolher A, o app passa automaticamente para B.
3. Toque em **Calcular rota** para gerar o percurso da viatura.
4. Aguarde a primeira cópia dos tiles para o armazenamento privado do app.
5. A linha verde mostra a geometria calculada nas ruas. A câmera enquadra o percurso.
6. Use **Instruções** para abrir as manobras e **Coordenadas** para editar os valores manualmente.
7. Ative o modo avião e repita o teste para conferir mapa e roteamento offline.

O mapa permite zoom e deslocamento. **Enquadrar** volta a mostrar a rota/pontos selecionados.
Editar um ponto ou trocar o transporte limpa a rota anterior.
A navegação veicular oferece GPS real, simulação e instruções faladas quando há uma voz portuguesa offline instalada. Busca de endereços não está incluída.

O recorte visual cobre Palmas, Taquaralto e Taquaruçu, entre as coordenadas
`oeste=-48.45, sul=-10.42, leste=-48.05, norte=-10.08`.
Esse retângulo não é o polígono administrativo do município.
Os pontos de origem e destino devem estar dentro dele.

## Navegação de viaturas

1. Escolha o destino B. Origem A permanece disponível para planejamento e simulação.
2. Toque em **Navegar por GPS**, permita localização precisa e aguarde uma posição utilizável.
3. Confira a rota calculada a partir do GPS e toque em **Iniciar navegação**.
4. O painel de instruções e o resumo de progresso são componentes Compose reais do Ferrostar.
5. **Recentralizar** retoma o acompanhamento após mover o mapa. **Enquadrar** mostra o percurso completo.
6. Use **Silenciar/Ativar voz** para controlar os anúncios. O botão de fechar no painel de progresso encerra a sessão.

O aplicativo possui somente roteamento veicular (`auto`), destinado ao protótipo de
navegação de viaturas. A interface continua utilizável se a permissão for negada.
A localização solicita diretamente o provedor de satélites do Android, em alta precisão,
sem depender do provedor combinado em modo de economia. A tela mostra quantos satélites
estão em uso e a precisão estimada em metros. Ela permanece acesa também durante a aquisição.
Posições mais antigas que 12 segundos, precisão pior que 50 metros e posições fora do
recorte não entram no núcleo de navegação. Entre 35 e 50 metros, há aviso de precisão
reduzida; recálculo automático exige precisão de até 35 metros. A prévia considera a
incerteza das duas posições ao decidir se é necessário recalcular antes de iniciar,
evitando reinícios por oscilação de GPS enquanto a viatura está parada.
O recálculo exige desvio persistente detectado pelo Ferrostar, com intervalo mínimo de
10 segundos entre tentativas. A direção de deslocamento é enviada ao Valhalla quando confiável.

A navegação funciona enquanto o aplicativo está aberto. Ao sair dele, a sessão é pausada,
o GPS e a voz são interrompidos e pedidos pendentes são invalidados. **Retomar** recalcula
a partir da posição GPS atual; na simulação, reinicia o percurso planejado.

### Simulação sem GPS

Escolha A e B e toque em **Simular**, depois em **Iniciar simulação**.
O selo **SIMULAÇÃO · posições virtuais** diferencia o modo de teste do GPS real.
As posições são geradas pelo `SimulatedLocationProvider` do Ferrostar, a 3x a velocidade.
**Simular desvio** desloca temporariamente a posição cerca de 160 metros para leste,
para exercitar detecção e recálculo com o grafo local. Na borda da região ou em vias
paralelas, escolha outro percurso se o desvio não for identificado. A chegada encerra
o acompanhamento e apresenta **Concluir**.

### Voz offline

O aplicativo só seleciona vozes portuguesas instaladas que não exigem rede, preferindo pt-BR.
Sem uma voz adequada, informa a limitação e mantém as instruções visuais. A instalação de
uma voz, quando desejada, é feita nas configurações de texto para fala do aparelho antes
do teste offline. Não há fallback automático para voz online.

### Integração e compatibilidade

- Ferrostar `core`, `ui-compose` e `ui-formatters`: **0.57.0**, publicados no Maven Central.
- Valhalla Mobile: **0.6.4**, com os mesmos tiles locais.
- MapLibre Native OpenGL: **13.6.1**. Não há um segundo renderer na árvore de dependências.
- Compose BOM **2026.02.01**, Activity **1.13.0**, Lifecycle **2.10.0** e coroutines **1.11.0**.
- AGP **9.0.1**, plugin Compose/Kotlin **2.2.10**, Gradle **9.1.0**, JDK **21**.
- Android mínimo **API 25 / 7.1**, exigido pelo Ferrostar; `desugar_jdk_libs` **2.1.5**.

O mapa mantém a integração nativa e seus recursos locais; os painéis visuais vêm do
Ferrostar. Isso evita conflitos entre variantes OpenGL/Vulkan e preserva as camadas atuais.
`OfflineNavigationRouteProvider` chama o actor Valhalla local com `format=osrm`,
`shape_format=polyline6`, instruções visuais/faladas e idioma pt-BR. O parser nativo do
Ferrostar converte a resposta, em metros, para sua sessão. OSRM aqui é um formato,
não outro motor. Não existe provedor HTTP configurado e qualquer chamada a ele falha.

`OfflineRouter` conserva os índices das manobras no formato Valhalla, ajustando-os quando
concatena pernas com ponto compartilhado. A sessão utiliza gerações para descartar
respostas de chamadas nativas síncronas que já não pertencem à navegação atual.

## Instalar via USB

Ative a depuração USB, desbloqueie o telefone e aceite a autorização do computador.
No PowerShell, dentro desta pasta:

```powershell
$adb = '.\.tools\sdk\platform-tools\adb.exe'
& $adb devices
& $adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
& $adb shell am start -n 'br.com.palmasoffline/.MainActivity'
```

O identificador precisa aparecer seguido de `device`. Se houver mais de um aparelho,
adicione `-s NUMERO_DO_DISPOSITIVO` antes do comando de instalação/inicialização.

## Compilar

Abra esta pasta pelo `settings.gradle.kts` no Android Studio, sincronize e execute o módulo `app`.
Configure o Gradle com JDK 21 e Android SDK 36 / Build Tools 36.0.0.
O exemplo de console original `src/Main.kt` foi preservado; ele não faz parte do app.

Pelo PowerShell:

```powershell
.\build-prototype.ps1 -JavaHome 'C:\Users\lucas.reis\.jdks\ms-21.0.12'
```

O script executa testes unitários, gera o APK e executa lint.
`local.properties` aponta para o SDK local instalado em `.tools/sdk`.
`gradle.properties` ajusta a verificação de caminho e a codificação dos argumentos Java
para o diretório `Navegação_GPS` no Windows.

## Dados locais e reprodução

- `app/src/main/assets/palmas/*.geojson`: ruas, áreas, cursos d'água e localidades do OSM.
- `palmas/style.json`: estilo visual com referências exclusivamente a assets locais.
- `palmas/fonts/`: glyphs para os nomes das ruas, sem download em tempo de uso.
- `palmas/metadata.json`: limites, quantidades e hash do pacote de roteamento.
- `app/src/main/assets/palmas_tiles_v1.tar`: 24 tiles Valhalla, cerca de 12 MB.

O pacote de roteamento mantém uma margem regional e tiles de hierarquia superior,
que abrangem áreas vizinhas e são indivisíveis. Isso preserva as conexões das vias.
O mapa visual e a seleção de pontos ficam restritos ao recorte descrito acima.
O TAR anterior com toda a Região Norte foi retirado do APK.
Ao inicializar o novo motor, o app remove a cópia antiga desse TAR do seu armazenamento privado.

O mapa é derivado diretamente do arquivo OSM; não foram baixados tiles de
`tile.openstreetmap.org`. Dados © colaboradores do OpenStreetMap, sob ODbL.
Os GeoJSON estão disponíveis no APK. Atribuições adicionais: `palmas/NOTICE.md`.

Para regenerar os dados com Python e as bibliotecas `osmium` e `shapely`:

```powershell
$env:PYTHONPATH = "$PWD\.tools\map-python"
python scripts/prepare_palmas.py `
    --osm ../dados_palmas_norte_v2/norte-latest.osm.pbf `
    --routing-tar ../dados_palmas_norte_v2/valhalla_tiles.tar `
    --assets app/src/main/assets
python scripts/create_map_style.py
```

O arquivo `.osm.pbf` e o TAR regional original são necessários apenas para a geração.
O TAR de Palmas usado pelo aplicativo é versionado para permitir compilar após clonar o repositório. Os arquivos regionais de geração ficam fora do Git.
Se mudar os tiles sem mudar seu nome de versão, limpe os dados do aplicativo para reinstalá-los.

## Organização e validação

- `MainActivity.kt`: tela Compose e ciclo de vida do MapView.
- `PalmasMapController.kt`: camadas, marcadores, seleção e enquadramento.
- `RouteViewModel.kt`: estado, erros e execução fora da thread principal.
- `OfflineRouter.kt`: instalação dos tiles, chamada Valhalla e leitura da resposta.
- `Polyline6.kt`: decodificação da geometria Valhalla com seis casas decimais.
- `PalmasRegion.kt`: limites da região permitida.
- `scripts/prepare_palmas.py`: extração GeoJSON e seleção de tiles do grafo existente.

Os testes unitários cobrem coordenadas, região e polyline6.
O estilo pode ser validado com o pacote oficial `@maplibre/maplibre-gl-style-spec`.
Os testes instrumentados verificam a rota real, a geometria e os recursos offline:

```powershell
# Com aparelho ou emulador autorizado e JDK/Gradle configurados:
.\gradlew.bat :app:connectedDebugAndroidTest
```

O pacote regional foi validado também no Valhalla do Docker, com rotas curtas,
centro–Taquaralto e Palmas–Taquaruçu. Isso valida a seleção do grafo, mas não substitui
a execução da biblioteca e do renderer no Android.



## Validação da navegação — 08/10/2026

A versão 0.3.0 foi compilada com JDK 21 e instalada em um Samsung Galaxy A34
(SM-A346M), Android 16, conectado por USB.

- **13 testes unitários aprovados**, incluindo qualidade da localização, cobertura,
  debounce de desvio, intervalo de recálculo e invalidação de gerações.
- **11 testes instrumentados aprovados** no aparelho, usando os motores nativos e os
  tiles do APK: mapa e enquadramento, conversão OSRM, instruções visuais/faladas,
  índices de várias pernas, chegada pelo simulador Ferrostar, recálculo local após
  desvio, pausa ao sair do app e descarte de resposta de um recálculo não interrompível
  depois de encerrar a sessão.
- **Build e lint aprovados**. Lint: zero erros e seis avisos não bloqueantes,
  relativos a versões fixadas, compatibilidade de armazenamento e sugestões KTX.
- Interface inspecionada no aparelho. Captura: `.tools/navigation-validation/navigation-simulation.png`.
- Fluxo de permissão precisa conferido pela interface, com autorização **apenas desta vez**.
  A leitura real recebida tinha precisão insuficiente, e o aplicativo apresentou o aviso
  e manteve o navegador aguardando, sem assumir uma posição simulada.
- O APK e o manifesto instalado não solicitam `INTERNET`. Mapas e ambos os cálculos
  (inicial e recálculo) usam arquivos e bibliotecas locais.

Há uma exceção específica em `app/lint.xml` para a classe opcional
`FerrostarForegroundService` incluída na biblioteca. Ela não é declarada no manifesto
mesclado e o aplicativo não configura um gerenciador de serviço, portanto não publica
notificações. A exceção não desativa outros erros nem adiciona permissões desnecessárias.

O teste de deslocamento com **GPS real dentro da cobertura de Palmas** continua sendo
uma validação de campo. Também não foi alterada a conectividade do aparelho durante
esta execução. Para o aceite manual, instale o APK, habilite localização precisa,
confira o funcionamento em modo avião dentro da cobertura, percorra uma rota,
verifique uma conversão, um desvio e a chegada. Repita negando localização e sem voz
portuguesa instalada para conferir os estados de recuperação.

### Arquivos da integração

| Arquivo | Responsabilidade |
| --- | --- |
| `OfflineNavigationRouteProvider.kt` | Ponte Valhalla local → rota Ferrostar, formato OSRM/polyline6 |
| `RouteViewModel.kt` | Sessão, GPS, prévia, recálculo, cancelamento e pausa |
| `NavigationPolicy.kt` | Validação de posição, debounce e gerações |
| `GnssLocationProvider.kt` | Solicitação direta de satélites e diagnóstico de precisão |
| `NavigationLocationFeed.kt` | Entrada de posições aprovadas no núcleo |
| `OfflineVoice.kt` | TTS instalado e seleção de voz offline |
| `MainActivity.kt` | Componentes Compose reais do Ferrostar e controles |
| `PalmasMapController.kt` | Percurso, marcador, orientação, enquadramento e câmera |

APK final: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `D8865626701CDC4AC34CF13EC41DA5FDA09A87F8005E05192BCFBB777C338CDB`.


## Correção GPS e perfil de viaturas — versão 0.3.1

Caminhada e bicicleta foram retiradas da interface e da API do roteador do aplicativo.
O cálculo agora fixa o perfil veicular `auto`.

O diagnóstico do celular mostrou o pedido anterior no provedor `fused` em modo
`BALANCED`, recebendo estimativas de 100 m, enquanto o provedor `gps` estava `OFF`.
`GnssLocationProvider` substitui essa seleção automática: solicita `GPS_PROVIDER`,
com `QUALITY_HIGH_ACCURACY` no Android 12+, ou pedido direto ao GPS nas versões anteriores.
Também publica quantidade de satélites visíveis/em uso e precisão estimada.
Referência da API: [Android LocationRequest.Builder](https://developer.android.com/reference/android/location/LocationRequest.Builder).

Validação da versão 0.3.1:

- **14 testes unitários aprovados**.
- **12 testes instrumentados aprovados** no Galaxy A34/Android 16.
- O novo teste confere no serviço Android que o próprio aplicativo solicita o provedor
  de satélites em alta precisão e que seu pedido é removido ao cancelar.
- Build e lint aprovados: zero erros, seis avisos não bloqueantes.
- Aquisição de uma posição precisa e deslocamento real em campo não foram validados,
  conforme a opção do usuário de continuar sem esse teste.

O pacote offline ainda é o recorte de Palmas descrito neste documento. Para percorrer
zonas rurais fora dele, é necessário gerar/instalar mapas e grafo de roteamento dessas
regiões; alterar apenas os limites de coordenadas não fornece os dados ausentes.

O APK 0.3.1 substitui o APK da validação anterior:
`app/build/outputs/apk/debug/app-debug.apk`.
SHA-256 da versão 0.3.1: 0D1BFD2286F2A074C8C0817CF62B99552FB0E090BE53F17BC602A2B4B84ED2FA.
