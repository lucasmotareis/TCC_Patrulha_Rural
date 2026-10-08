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

O repositório inclui os mapas, fontes e o pacote de roteamento de Palmas necessários ao protótipo. Não é necessário copiar os SDKs e caches da máquina original para compilar.

A cobertura atual compreende o recorte de Palmas, Taquaralto e Taquaruçu documentado no aplicativo. Para outras zonas rurais, é necessário gerar mapas e grafo de roteamento da região desejada com os scripts em `Kotlin_Teste/scripts`.

Dados © colaboradores do OpenStreetMap, sob ODbL. Veja as [atribuições dos recursos locais](Kotlin_Teste/app/src/main/assets/palmas/NOTICE.md).

A navegação funciona com o aplicativo aberto. Os testes automatizados foram executados no aparelho; a validação de deslocamento real em campo continua pendente.
