# Discogs #211 — descoberta e reconciliação editorial além do MusicBrainz (08/11/2026)

## Intenção aprovada

A mesma [PR #211](https://github.com/jssantogit/BitChord/pull/211) deverá cobrir **quatro resultados verificáveis**:

1. Encontrar candidatos Discogs mesmo quando a edição MusicBrainz não tem URL Discogs.
2. Corrigir a **classificação exibida** de álbum / EP / single quando evidências justificam, sem falsificar os dados canônicos do MusicBrainz.
3. Recuperar lançamentos registrados apenas no Discogs, usando IDs próprios do provedor, sem criar MBIDs ou faixas reproduzíveis.
4. Exibir resultados elegíveis nas **estantes nativas existentes** de álbuns e singles/EPs, preservando ações compatíveis com o contexto e UI modular.

Não produzir smoke APK até o mecanismo estar completo e a CI verde; a decisão anterior do usuário de suspender smoke permanece. `main` intocada, nenhuma PR extra ou merge.

## Três escopos de identidade que não são intercambiáveis

- MusicBrainz `ReleaseGroup MBID`: grupo editorial, reúne edições de um lançamento.
- MusicBrainz `Release MBID`: edição específica, contém barcode, gravações, selo, data, formato e país.
- Discogs `Master ID` / `Release ID`: identificadores próprios. O Master é agrupamento de versões; a Release é edição. **Não** inventar um mapeamento 1:1 entre um Master e um Release Group, nem entre duas edições apenas por título.

Toda identidade externa recebe `discogs:master:<id>` ou `discogs:release:<id>`; nunca usar UUID, MBID, `ReleaseId` local, `MediaAssetId` ou `recordingId` falsos.

## Caminhos de descoberta — ordem de custo/qualidade

1. **Direto**: URL MusicBrainz Release → Discogs Release. Já implementado, manter.
2. **Grupo**: URL MusicBrainz ReleaseGroup → Discogs Master, quando houver. Obter `main_release` / versões candidatas, validar que não estamos vendendo uma edição diferente como a solicitada. Esse caminho não necessita da busca geral autenticada; a relação em si é evidência de *grupo*, não de edição.
3. **Busca autenticada opt-in**: `/database/search?type=release`, índices por barcode; depois artista + título (+ ano/catno). A pesquisa exige autenticação; token do próprio usuário fornecido voluntariamente, mantido em armazenamento criptografado fail-closed (nunca `SharedPreferences` com fallback plano, build config, log ou backup). Sem token, não tentar chamar o endpoint e não degradar a navegação.
4. **Lançamento só Discogs**: depois de resultados reais e escopo de artista comprovado, formar identidade independente e estante consultável com atribuição; não preencher o catálogo MusicBrainz com dados falsos. Separar `not_checked`, `not_found`, `candidate`, `verified`, `conflict`, `unavailable`, `auth_required`.

Aplicar limite por tela/consulta, cancelamento, backoff 429, dedupe, cache temporário com TTL de no máximo cinco horas para conteúdo renderizado e logs categóricos sem títulos/IDs/headers/token.

## Critérios de identidade de edição

- Vínculo direto não dispensa comparação de artistas, título, status oficial, lista completa, barcode, ano e país quando presentes.
- **Sem vínculo direto:** artist credit e título exatos (normalizados), tracklist completa e em ordem, barcode válido **igual** entre provedores; ano/país não conflitantes. Homônimos, múltiplos candidatos fortes, créditos incompletos, barcode ausente ou listas parcialmente diferentes permanecem candidatos/inconclusivos. Não promover apenas título ou mesma capa.
- Master agrupado é pista para buscar edições, **nunca** prova de Release.
- Discogs-only pode aparecer como resultado de metadados com identidade de origem, não tem playback/download/fixação de áudio sem MediaAsset verificável. A ausência no MusicBrainz só deve ser afirmada após busca completa compatível com as limitações do índice.

## Reconciliação de tipo e estantes

- Preservar tipo MusicBrainz no armazenamento canônico. Criar `effectiveEditorialType` derivado e atribuível para UI (Album/EP/Single), com fonte, tempo de validade e motivo.
- `Other` ou tipo ausente: preencher mediante pelo menos uma edição com identidade **verificada** e descrição Discogs explícita, sem contradição.
- Tipo MB definido e igual: corroborado.
- Tipo MB definido e conflitante: registrar conflito; somente permitir reclassificação automática sob consenso de pelo menos duas edições verificadas distintas do mesmo grupo e sem evidência concorrente contrária. Em qualquer outro caso, manter MB e informar discordância, sem alegar decisão da gravadora.
- Uma descrição Discogs isolada ou uma palavra no título não prova tipo; `LP` é formato, não sinônimo de Album. Entradas `Unofficial`, Promotion e divergentes são excluídas de correções automáticas.
- A classificação derivada alimentará as **estantes existentes** do artista, Home e Explorar por um único adaptador tipado. Não adicionar UI paralela nem reproduzir lançamentos sem origem de áudio.

## Corpus de valor real — registro inicial, sem fingir cobertura medida

Estes são **casos reais para verificar**, não evidências de classificação Discogs nem rótulos de `PASS`:

| Caso | Relação MusicBrainz conhecida | O que medir | Fonte do caso |
| --- | --- | --- | --- |
| ILLIT — SUPER REAL ME, digital mundial | Release MB `541adf5a-48d4-4f7b-a7bb-afd3cea5af27`, EP | Buscar sem vínculo Release e comparar barcode/tracklist; provável falso negativo de cobertura | https://musicbrainz.org/release/541adf5a-48d4-4f7b-a7bb-afd3cea5af27 |
| ILLIT — Super Real Me, edição física coreana | Release MB `68e87262-1bfb-4ede-922b-86a025b2ac96`, EP; barcode 8809704427821 | Não confundir variante física com digital | https://musicbrainz.org/release/68e87262-1bfb-4ede-922b-86a025b2ac96 |
| ILLIT — BREAK EVEN (2026) | Discografia MB o classifica como EP; caso observado no A54 com `no_link` | Verificar se realmente existe edição Discogs, não inventar confirmação | https://musicbrainz.org/artist/6b128dd7-e998-4edb-a4f5-400fef190445 |
| BTS — BE, digital | Release MB `75622cd1-7bf0-497d-a2ad-76a1dcb5d578`; ReleaseGroup tem Discogs Master `1995718` | Caminho Master → candidata, sem promover edição errada | https://musicbrainz.org/release/75622cd1-7bf0-497d-a2ad-76a1dcb5d578 |
| BTS — BE, deluxe CD | Release MB `4adde75f-ef2f-4195-a8da-fb65c9647dd8`; barcode 8809633189159 | Detectar risco de variante; não usar Master como edição | https://musicbrainz.org/release/4adde75f-ef2f-4195-a8da-fb65c9647dd8 |
| BTS — ARIRANG (2026) | ReleaseGroup MB `21159e3f-172e-43f6-aa7d-8e06a81fea49`; álbum; 14/16 faixas em diferentes versões | Não mesclar versões clean/explicit nem tracklists diferentes | https://musicbrainz.org/release-group/21159e3f-172e-43f6-aa7d-8e06a81fea49 |
| BTS — BTS, THE BEST | ReleaseGroup MB `b60d3e59-647c-493f-a4fb-3530cee04a16`, Album + Compilation, URL Discogs Master `2190664` | Preservar tipo secundário Compilation e evitar Master como Release | https://musicbrainz.org/release-group/b60d3e59-647c-493f-a4fb-3530cee04a16 |

Adicionar casos reais com resultados Discogs positivos, lançamentos ausentes do MB, homônimos, colaboração multiartista, CJK, barcode ausente, bootleg, relançamento deluxe e empate entre candidatos antes de aprovar correções automáticas.

## Métricas e critérios de saída

Relatar por segmento: `investigados / sem vinculo / candidatos Discogs / verificáveis / falsos positivos / reclassificados com prova / só Discogs exibidos nas estantes / conflitos / indisponíveis`. Comparar baseline **MusicBrainz-only** e novo mecanismo para os mesmos casos, com URL por item. Não usar dados sintéticos como prova de cobertura.

- <b>Precisão</b>: zero associações falsas no corpus auditado; qualquer conflito deve impedir promoção automática.
- <b>Utilidade</b>: medir quantos casos realmente ganham classificação correta ou ganham lançamento que faltava, não quantos rodapés aparecem.
- <b>Garantias</b>: três rotas de álbum/estantes nativas; nenhuma cópia de componente; cache/limite/attribution; nada toca, baixa ou cria faixas só por estar no Discogs.
- <b>Android</b>: Kotlin tests e Compose/CI verde no mesmo SHA, pacote A54 apenas quando implementação estiver integrada, nenhuma regressão na PR #209.

Fontes normativas: https://support.discogs.com/hc/en-us/articles/360005006654-Database-Guidelines-6-Format ; https://support.discogs.com/hc/en-us/articles/360005055493-Database-Guidelines-16-Master-Release ; https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use ; https://musicbrainz.org/doc/MusicBrainz_API .
