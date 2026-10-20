# TCC_Patrulha_Rural

Protótipo Android de navegação offline para viaturas policiais, desenvolvido em Kotlin e Jetpack Compose.

O aplicativo usa Valhalla Mobile para calcular rotas no aparelho, Ferrostar para acompanhar a navegação e apresentar instruções, e MapLibre para desenhar os mapas locais. A localização solicita diretamente o GPS por satélite em alta precisão. Há simulação de percurso, recálculo local, orientação por voz offline e diagnóstico de precisão e satélites.

O projeto Android está em [`Kotlin_Teste`](Kotlin_Teste/). Consulte o [README do aplicativo](Kotlin_Teste/README.md) para arquitetura, versões, testes e instruções de uso.

## Compilar

1. Clone este repositório.
2. Abra `Kotlin_Teste/settings.gradle.kts` no Android Studio e configure o Android SDK local.
3. Use JDK 21, Android SDK 36 e Build Tools 36.0.0.
4. Sincronize o Gradle e execute o módulo `app` em um dispositivo Android 7.1 ou superior com OpenGL ES 3.0.

No PowerShell, dentro de `Kotlin_Teste`:

```powershell
.\build-prototype.ps1 -JavaHome 'C:\caminho\jdk-21'
```

Esse script executa testes unitários, gera o APK e executa lint. Com um aparelho ou emulador autorizado, execute também os testes instrumentados:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Mapas e cobertura

O projeto inclui os mapas vetoriais, fontes e o pacote de roteamento do batalhão em `Kotlin_Teste/app/src/main/batalhaoAssets`. Não é necessário copiar os SDKs e caches da máquina original para compilar.

A versão 0.6.0 usa um [pacote regional de satélite CBERS-4A offline](Kotlin_Teste/CBERS_REGIAO.md), com 7,23 GB de blocos PNG dos 11 municípios. São 21 cenas de 14/05/2023 a 04/08/2026, sob CC BY 4.0, com créditos ao INPE. O mosaico cobre 99,998% dos pixels internos ao recorte, com pequenos vazios das fontes, e conserva transparência fora dos municípios. O pacote é transferido por USB separadamente do APK. No app, **Satélite**, **Vetorial** e **Área do 8º BPM** permitem alternar o fundo e consultar a região.

A [amostra de cerca de 6 × 6 km do Onalício Barros](Kotlin_Teste/CBERS_TESTE.md), introduzida na versão 0.5.1, permanece disponível quando o pacote regional não está instalado.

A atualização 0.6.1 oculta as linhas azuis de rios e córregos nos fundos Satélite e Vetorial. Atualizar o APK preserva o pacote regional já transferido.

A versão 0.5.0 traz o mapa vetorial recortado pelos 11 municípios do 8º BPM no Vale do Araguaia: 51 marcadores, 38 perímetros de assentamentos e os limites municipais, além de estradas, localidades e cursos d'água. A base de rotas mantém margem operacional de 15 km e inclui os acessos à Chácara 2 Meninos e ao Onalício Barros desenhados no QGIS. Consulte [o caso do batalhão](Kotlin_Teste/BATALHAO.md) para geração, validação e limitações. Os arquivos anteriores de Palmas foram preservados, mas não são empacotados nesta versão.

Dados de base © colaboradores do OpenStreetMap, sob ODbL. Veja as [atribuições dos recursos locais](Kotlin_Teste/app/src/main/batalhaoAssets/batalhao/NOTICE.md).

O Git conserva o código, os recursos vetoriais e de roteamento usados na compilação, a amostra CBERS e os scripts de preparação. O satélite regional completo, instaladores e arquivos de trabalho do QGIS permanecem locais nas pastas ignoradas `dados_qgis/`, `dados_batalhao/` e `exportacoes/`. Os [registros leves de validação da versão 0.6.1](Kotlin_Teste/validation/8bpm-2026-10-20/README.md) incluem resultados, fontes e hashes dos pacotes.

A navegação funciona com o aplicativo aberto. A versão 0.6.1 passou nos 24 testes instrumentados no Galaxy A34 com Android 16 em 20/10/2026, incluindo a renderização do satélite nos onze municípios, alternância de fundos, os acessos à chácara e ao Onalício, seleção de assentamento, perímetros, mapa local, navegação simulada e recálculo após desvio. Também passaram 19 testes unitários e 9 testes do pacote; lint terminou sem erros. O mapa instalado ocupa aproximadamente 7,64 GB no aparelho. A validação com deslocamento real em campo continua pendente.
