# Discogs #211 — corpus de avaliação da utilidade editorial

Data: 2026-11-08. Escopo: Android mobile, PR #211, sem APK/smoke físico até o conjunto passar pela CI.

## Pergunta que o corpus precisa responder

O enriquecimento Discogs torna a discografia do BitChord materialmente mais completa ou mais correta do que MusicBrainz isolado? A resposta exige casos reais, comparados por ReleaseGroup, não contagem de chamadas HTTP ou presença de um rodapé de metadados.

## Amostra reprodutível (30 grupos/lançamentos, a selecionar com MBIDs/Discogs IDs verificados)

- **A — Tipo editorial questionável (10):** MusicBrainz marca Album/EP/Single, mas gravadora/artista e/ou Discogs fornecem evidência contrária. Registrar a fonte da avaliação manual e se há duas edições Discogs de fato distintas. Não inferir tipo pela duração, número de faixas ou rótulo LP.
- **B — Release sem relação direta MB→Discogs (10):** MusicBrainz conhece o grupo e uma edição oficial com tracklist completa, mas essa edição não tem relação Release→Discogs. Comparar vínculo de Master, barcode e busca autenticada por título; classificar Verified, Ambiguous, Candidate, NoLink ou Unavailable, sem tratar falha de rede como ausência.
- **C — Discogs-only aparente (10):** um artista com MBID confirmado e discografia completa, pequena o suficiente para leitura paginada integral, contém no Discogs um lançamento sem correspondência MusicBrainz. Exigir identidade de artista, edição verificável, faixas, gravadora e classificação explícita. Uma primeira página MusicBrainz ou artista homônimo não prova ausência.

Os exemplos observados no A54 em 2026-11-07 (ILLIT BREAK EVEN e álbum BTS) são candidatos de observação, **não** casos previamente classificados como verdade editorial. Confirmar IDs e as fontes antes de contá-los.

### Controles negativos observados, fora das 30 vagas

- **ILLIT — SUPER REAL ME:** a edição oficial MusicBrainz [541adf5a-48d4-4f7b-a7bb-afd3cea5af27](https://musicbrainz.org/release/541adf5a-48d4-4f7b-a7bb-afd3cea5af27) já está classificada como **EP**. Uma evidência Discogs que apenas concorde com EP é *corroboração*, **não** correção de classificação.
- **BTS — Proof:** a edição oficial MusicBrainz [67b65e1b-d054-4a68-b8ac-da4bdd5a10cb](https://musicbrainz.org/release/67b65e1b-d054-4a68-b8ac-da4bdd5a10cb) está classificada como **Album + Compilation**, em concordância geral com o [catálogo oficial BIGHIT](https://bts.ibighit.com/eng/discography/detail/proof/index.html). A adição de dados Discogs não constitui, por si só, uma nova classificação correta.

Estes são controles de **classificação MusicBrainz já existente**, não edições Discogs verificadas, nem prova de que há vínculo Discogs direto. Não preencher `reviewed=true` ou alegar ganho para eles sem confirmar o registro Discogs exato.

## Sondagem de candidatos públicos (10/11/2026)

A coleta preliminar é automatizada por
`scripts/catalog_211_discogs_corpus_candidates.py` e pelo workflow
`.github/workflows/catalog-211-discogs-candidate-scan.yml`.
São observados, com orçamento de rede limitado e **sem token**, dez artistas
com MBIDs públicos (ENHYPEN, ILLIT, NewJeans, LE SSERAFIM, IVE, aespa,
NMIXX, TOMORROW X TOGETHER, BABYMONSTER e Stray Kids).
A relação MusicBrainz Artist → Discogs Artist precisa ser única e explícita;
a lista de ReleaseGroups MusicBrainz precisa estar completa, íntegra e
caber em até quatro páginas. O índice público Discogs utiliza no máximo
duas páginas: a segunda só é consultada quando a primeira não gera nenhuma
pista de título novo, espelhando a política de descoberta do app. Não cria
vínculo entre ReleaseGroup e Discogs Master e registra `pages_checked`.
Uma segunda página inválida não vira uma alegação de ausência.
O app compartilha o mesmo comparador conservador de títulos conhecidos
entre o filtro do índice e a inspeção final, evitando hidratações
Discogs desnecessárias de grupos MusicBrainz já completos.

Uma segunda etapa conservadora `scripts/catalog_211_discogs_candidate_details.py`
consulta **no máximo 8 entradas do índice por execução**, distribuídas
entre artistas (até 2 por artista; máximo 16 requests públicos para Master e
Release). Valida os IDs e o retorno exato de Master.main_release →
Release.master_id, anota crédito da edição, gravadora, faixas e formato, e
identifica vídeo isolado ou créditos de artista incompatíveis. A revisão de
10/11/2026 mostrou nomes desambiguados como `Ive (16)` e `TXT (5)`;
a integração pública valida o ID Discogs de `artists[]` contra a relação
exata MusicBrainz Artist → Discogs Artist, sem inferir equivalências por nome.
A análise pública demonstrou que `EASY CRAZY HOT` já possui [edição
MusicBrainz](https://musicbrainz.org/release/e6de9634-eb96-4b54-9568-755a99ec312b)
relacionada ao [Discogs Release 35021231](https://www.discogs.com/release/35021231).
A comparação source-only também reconhece **apenas aliases bilíngues
explicitamente grafados no título do próprio MusicBrainz** (ex.:
`合 (HOP)` → `HOP`, `ジュエリー (Jewelry)` → `Jewelry`).
Isso não permite adivinhar traduções, versões Live/Remix ou títulos com
barra `/`; filtra pistas aparentes do índice tanto no scanner quanto
na biblioteca Android. Além disso, admite equivalência dos mesmos
três ou mais termos Unicode em igual ordem com separadores diferentes,
como `Easy-Crazy-Hot`. Ela não supõe tradução de um título,
não colapsa versões com termos adicionais e não substitui a prova de edição.
A estante source-only rejeita formatos exclusivamente DVD, Blu-ray ou
vídeo ainda que a descrição Discogs inclua "Album" ou "Single";
edições que também declaram CD/File/Vinyl mantêm elegibilidade e exigem
as demais verificações.

O relatório `discogs-211-corpus-edition-observations.json` é uma
<b>ficha de inspeção</b> (não prova ausência MusicBrainz; não modifica o
manifesto). Falhas e rate-limit são rotulados indisponíveis, nunca ausência.

O arquivo gerado `discogs-211-corpus-candidates.json` diferencia
`no_unique_artist_relation`, `incomplete_mb_browse`,
`network_unavailable` e `index_compared_not_adjudicated`.
`candidate_index_hints` são **apenas títulos não idênticos no índice**:
podem ser edições, grafias alternativas, traduções ou conteúdos já
presentes no MusicBrainz. A sondagem **não** hidrata nem verifica
automaticamente esses candidatos e **não** preenche nenhuma das 30 vagas
como `reviewed=true`. Revisar manualmente identidade da edição,
relações, créditos, tracklist, tipo, fontes editoriais independentes e
navegação no app antes de julgar cada caso do coorte C.
Amostragem de dez artistas não define a taxa de recuperação global.
Ausência de relação Discogs, paginação incompleta, falhas de rede e zero pistas
são resultados válidos que devem permanecer registrados sem imputação.

## Controles públicos de pares exatos (10/11/2026)

O roteiro `scripts/catalog_211_discogs_edition_pair_review.py` usa somente
`docs/plans/catalog-211-discogs-edition-pairs.json` (máximo 6 pares
por execução). Os seis pares incluem ENHYPEN `MEMORABILIA`, TXT `Setsuna Hanabi`,
LE SSERAFIM `EASY CRAZY HOT`, IVE `Fashion`, IVE `DARE ME` e TXT
`7TH YEAR` em CD, com MusicBrainz Release MBID e Discogs Release ID.
Requer a relação MB Artist→Discogs Artist explícita e idêntica ao
`artists[].id` da edição; identifica o ReleaseGroup real do registro
MB, seu tipo primário e os fatos de tracklist, barcode e relação direta
Release→Discogs. Máximo 18 GETs públicos, sem token; resultados indisponíveis
não geram inferência negativa. O comparador apresenta evidências
pontuais e *nunca* retorna Verified, ausência MB ou `reviewed=true`.
Esse controle revela diferença de pontuação no título de
`EASY CRAZY HOT` e um título de faixa diferente, apesar do vínculo direto
e barcode coincidente. O matcher Android pode aceitar apenas variações
Unicode de separadores com **vínculo direto de Release**, barcode validado,
ano coincidente, gravadora presente, ao menos cinco faixas e até duas
diferenças puramente de pontuação. Pesquisa e Master sem vínculo continuam
sem tal exceção; ordem diferente, créditos incompatíveis ou países
contraditórios continuam bloqueados.
Esses pares são **controles de identidade**, não substitutos das 30 vagas
A/B/C nem prova automática de ganho do Discogs. Artifact no mesmo
workflow: `discogs-211-edition-pair-evidence.json`.

## Campos por caso

O manifesto JSON usa exatamente: `id`, `cohort` (`type`, `unlinked`, `source_only`), `reviewed` (booleano), `sources` (rótulos de fontes; ao menos uma fonte independente: `artist`, `label` ou `release_notes`), `musicbrainz_group_mbid` (obrigatório nos coortes A e B), `discogs_release_id` (obrigatório quando `match_state=verified` e no coorte C), `musicbrainz_type`, `reference_type`, `app_type` (`Album`, `EP`, `Single`, `Unknown`), `match_state`, `match_correct`, `visible`, `absent_in_musicbrainz`.

Os booleanos precisam ser explícitos e os IDs devem corresponder a entidades públicas reais. Casos repetidos por ReleaseGroup (A) ou identidade de edição (B/C) são rejeitados. URLs de evidências, data da revisão e detalhes da adjudicação devem acompanhar as notas públicas do corpus, embora o script ainda não audite automaticamente essas URLs. A partir de 10/11/2026, `reviewed=true` exige também:
`reviewed_at_utc` (ISO UTC), `review_notes` substantivas,
`discogs_release_id` com URL exata `https://www.discogs.com/release/ID`
e `evidence_links.independent_editorial` (HTTPS, domínio diferente
de Discogs, MusicBrainz e GitHub). Para A, URL MusicBrainz do
ReleaseGroup exato; para B, URL MusicBrainz Release MBID exato; para C,
Artist MBID, URL do artista, `artist_browse_complete=true` e URL do
workflow observador. URLs são **localizadores**, não prova automática de
que o conteúdo corrobora o julgamento. O revisor ainda precisa ler as
fontes, conferir data/edição, avaliar a UI e registrar a decisão.
Uma marcação humana `reviewed=true` **não é prova automática** de que a classificação esteja correta.

Para executar: `python3 -m unittest discover -s scripts -p 'test_catalog_211_discogs_corpus_report.py'`; depois `python3 scripts/catalog_211_discogs_corpus_report.py docs/plans/catalog-211-discogs-corpus-manifest.json --output discogs-coverage.json`. O avaliador retorna código 2 se o corpus estiver incompleto ou houver falso positivo, para impedir o uso do relatório como gate aprovado.

Não registrar tokens, imagens, títulos pessoais, atividade privada ou URLs com autenticação. Nomes/IDs públicos de lançamentos podem ser guardados no corpus de teste; não gravá-los nos diagnósticos gerais.

## Métricas (denominadores explícitos)

1. **Cobertura de correspondência:** edições realmente verificadas / edições elegíveis com dados MusicBrainz completos; separar relações diretas, Master e pesquisa autenticada.
2. **Precisão:** correspondências confirmadas pela avaliação independente / correspondências que o app marcou Verified. Meta de segurança: nenhum falso positivo conhecido.
3. **Melhoria de tipagem:** ReleaseGroups cuja categoria correta passou a ser exibida / grupos mal classificados do coorte A. Contabilizar separadamente a classificação preenchida (Unknown→Album/EP/Single) e a correção (Album→EP etc.).
4. **Recuperação Discogs-only:** lançamentos válidos adicionados à descoberta sem criar MBIDs / casos confirmados ausentes do MusicBrainz no coorte C.
5. **Paridade visual:** card nos componentes nativos da tela de artista, Home e Explorar, rótulo de fonte, nenhum botão de reprodução inexistente e atribuição em navegação externa quando não há MBID.
6. **Orçamento e falha:** pesquisas, hidratações e latência por visita; nenhuma série de consultas repetidas em reabertura imediata, nenhum token em logs e rede indisponível não altera a taxonomia canônica.

## Método e gates

1. Fixar manifest do corpus antes de consultar resultados, incluindo falhas e exemplos negativos.
2. Executar testes unitários/Compose e CI Android sobre **o mesmo SHA**. Não usar um artefato de um commit anterior.
3. Com um token pessoal configurado no próprio aparelho, efetuar descoberta autenticada, observando também comportamento sem token.
4. Conferir manualmente os estados Verified/Disputed/Unknown e o destino de cada card. A classificação derivada deve expirar e nunca modificar os tipos MusicBrainz armazenados.
5. Gerar relatório com contagens reais, denominadores, IDs públicos, evidências de revisão e divergências; só depois decidir se a cobertura justifica um novo smoke físico.

## Limitações conhecidas

- A busca pública em Discogs exige token do usuário; sem ele, a melhoria depende de relações previamente existentes.
- Um ReleaseGroup e um Discogs Master não são a mesma entidade. Edições diferentes podem ter classificações distintas; dois registros Discogs não são duas declarações independentes de gravadoras.
- O enriquecimento do feed reaproveita grupos conhecidos. O cache Discogs-only depende de artistas efetivamente consultados e só apresenta metadados, não reproduz nem salva falsos lançamentos MusicBrainz.
- As páginas de gênero não devem presumir gênero a partir de Discogs se ele não foi verificado. Descobertas Discogs-only ficam separadas por tipo sob Explore, não como charts ou lançamentos recentes mundiais.
- A ausência de vínculo no MusicBrainz não é prova de que a edição não existe no Discogs.

## Pré-seleção rastreável (10/11/2026)

O manifesto agora distingue **`selected_unreviewed`** de **`reviewed`**.
Cinco das dez vagas **B** contêm edições reais sem URL direta
MB Release→Discogs Release na [sondagem de seis pares #42818809120](https://github.com/jssantogit/BitChord/actions/runs/42818809120):
ENHYPEN `MEMORABILIA`, TXT `Setsuna Hanabi`, IVE `Fashion`,
IVE `DARE ME` e TXT `7TH YEAR` em CD. MBID exato, ReleaseGroup e
Release Discogs estão fixados individualmente, sem alegar identidade da
mesma edição.

Três vagas **C** são **controles negativos preliminares** do índice
Discogs: aespa `World Tour In Cinemas` (Blu-ray), Stray Kids
`The DominATE Experience` (DVD) e ENHYPEN `Confessions`
(créditos múltiplos). As três possuem URLs públicas tipadas e
[observação #42807755689](https://github.com/jssantogit/BitChord/actions/runs/42807755689);
estão marcadas `excluded_by_provider_metadata`, mas **não revisadas**
por uma segunda fonte editorial ou testadas na UI do aparelho.

O relatório valida IDs, URLs públicas tipadas, unicidade e estados dessa
pré-seleção. Retorna `reviewed=0`, `metrics=null` e
`complete=false` enquanto faltarem as avaliações independentes.
É progresso de seleção, **não** medida de cobertura, e ainda são
necessários os controles restantes do corpus e a prova de navegação no app.

Status: **8 pistas reais documentadas em 30 vagas; nenhuma adjudicada**.
Não publicar indicadores de ganho ou precisão até revisão independente.
