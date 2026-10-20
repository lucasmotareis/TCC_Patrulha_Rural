# Registro da versão 0.6.1 — 20/10/2026

Registros leves da entrega offline do 8º BPM, validados em Samsung Galaxy A34 (SM-A346M), Android 16. `app-release.json` identifica o APK ARM64 e os resultados: 24 testes instrumentados, 19 testes unitários e 9 testes do pacote aprovados; lint sem erros e com 12 avisos.

`test-results.json` contém os resultados instrumentados. `municipalities.json` registra a comparação visual do satélite com o fundo vetorial nos onze municípios; `comparison.json` registra a alternância de fundos com preservação de rota, câmera e destino.

A pasta `cbers/` preserva o manifesto do mapa, o recibo com SHA-256 e tamanho do TAR, as fontes oficiais, a seleção das 21 cenas, seus recortes e as medidas de cobertura. O mosaico combina aquisições de 14/05/2023 a 04/08/2026; a cobertura válida medida foi de 99,9982% do recorte. Consulte `cbers/NOTICE.md` para créditos e licença.

O repositório inclui os recursos vetoriais e de roteamento necessários à compilação, além da amostra CBERS do Onalício. O satélite regional completo, APKs, arquivos intermediários e projetos de trabalho do QGIS permanecem locais em `dados_qgis/`, `dados_batalhao/` e `exportacoes/`; este registro não substitui o backup dessas pastas. A preparação e instalação do satélite estão documentadas em `../../CBERS_REGIAO.md`.

Os registros copiados para cá identificam o modelo do aparelho; o número de série USB não é necessário para reprodução. A validação com deslocamento real em campo continua pendente.
