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
pontuais, incluindo o primeiro par de títulos de faixa divergentes
(limitado a 120 caracteres cada), as posições que diferem apenas na
pontuação e a identidade de artista por ID do provedor validada
independentemente dos nomes desambiguados. O relatório *nunca*
retorna Verified, ausência MB ou `reviewed=true`.
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

## Busca de edições alternativas do mesmo ReleaseGroup (10/11/2026)

O B-coorte inclui cinco pares observados sem relação MusicBrainz Release → Discogs Release. Para evitar o erro de comparar uma edição física com uma edição digital escolhida arbitrariamente, `scripts/catalog_211_discogs_unlinked_group_probe.py` recupera a **lista completa** e limitada a 25 Releases do ReleaseGroup exato (somente 5 grupos do manifesto). A operação exige a relação de IDs tipados entre o manifesto e os controles previamente verificados, o crédito de artista Discogs, e código de barras com dígito verificador válido. Apenas edições MusicBrainz do próprio grupo com barcode idêntico podem gerar até dois GETs de evidência detalhada por caso. Máximo de 20 consultas públicas, sem token.

O relatório `discogs-211-unlinked-alternative-editions.json` diferencia falta de barcode, grupo paginado incompletamente, nenhuma correspondência no índice, divergência de identidade e candidatas com evidências de tracklist, créditos e relações. **Não é uma prova de edição Verified, não prova ausência MusicBrainz, não altera o manifesto e não preenche reviewed=true.** Paginação acima de 25/fluxo incompleto não gera resultado negativo. A observação torna o B-coorte mais defensável antes de uma revisão independente e do smoke físico.

### Inspeção de conteúdo quando não há barcode (10/11/2026)

A primeira leitura pública do B-coorte encontrou código de barras Discogs validável apenas em 1/5 casos; mesmo nesse não havia barcode igual entre as quatro edições indexadas do mesmo grupo. Isso não prova que a edição está ausente. O roteiro pode agora consultar até duas edições **Official, do mesmo ano, no mesmo ReleaseGroup integralmente paginado**, com rótulo explícito `same_year_official_content_only` quando não há código de barras correspondente. Faixas iguais ajudam a orientar a investigação, mas não bastam para Verified sem a identidade da prensagem; códigos de barras diferentes continuam incompatíveis para edição exata. Máximo de 20 GETs públicos por execução, sem alterar as 30 vagas ou gerar taxas fictícias.

A inspeção real #42913671019 confirmou que usar apenas um Release escolhido perdia pelo menos dois controles úteis: ENHYPEN `MEMORABILIA` tem outra edição MBID `300beef2-54ff-4d98-8a29-0dc01dfc96dd` com 6/6 faixas, país/ano compatíveis; TXT `Setsuna Hanabi` tem outra MBID `91c4d2eb-d34f-427a-b489-44411063c131` com 4/4, país/ano compatíveis. São **correspondências de conteúdo**, não de edição: sem barcode e URL Release→Discogs direta, ficam `not_verified`. O sampler prioriza outras edições oficiais do mesmo ano antes de repetir a edição de referência, mantendo duas inspeções por grupo e no máximo 20 requests totais.

Falhas isoladas de consulta de uma edição alternativa agora ficam no próprio candidato, como `source_unavailable`, sem apagar um primeiro candidato com dados completos (observado no caso TXT Setsuna Hanabi em #42914239531). O caso principal informa `partial_candidate_details_not_verified` se coexistem uma comparação real e uma leitura indisponível; a precisão não é inflada.

### Gravadora + número de catálogo em pares públicos (10/11/2026)

A segunda análise B-coorte coleta agora `MusicBrainz Release.label-info[].label.name` + `catalog-number` (consulta `inc=labels`, sem GET adicional) e `Discogs Release.labels[].name` + `catno` da edição exata. O relatório preserva pares normalizados com Unicode NFKC/casefold e espaços equivalentes, **sem retirar hífens, prefixos ou traduzir nomes de gravadoras**. Valores-placeholder, sem catálogo ou arrays malformados não autorizam correspondência. O número de catálogo igual com gravadora divergente é sinal **fraco**, explícito como `catalog_only_unconfirmed`; só a coincidência **do par inteiro** recebe `exact_label_catalog_pair_observed_not_verified`. Em ambos os casos, `verified=false` permanece, pois gravadora e catálogo não bastam para provar prensagem, barcode ou vínculo Release→Discogs. Não altera nem homologa automaticamente nenhum caso do manifesto.

## Grupo A — triagem de tipo editorial sem inferência (10/11/2026)

`scripts/catalog_211_discogs_type_triage.py` toma o relatório já obtido com seis
**pares Release/Discogs de ID exato** e classifica, sem GETs adicionais, os
tipos `Album`, `EP`, `Single` explicitamente informados pelo
MusicBrainz ReleaseGroup e nas descrições de formato Discogs. Um par sem
vínculo Release→Discogs é somente `unlinked_type_context_only`: não
autoriza correção de categoria. Mesmo uma discordância em um Release
diretamente vinculado gera apenas `linked_type_disagreement_needs_review`,
**não** `Verified`, `DISCOGS_CORRECTION` ou `reviewed=true`. Estados
`unknown`/`ambiguous`, fonte indisponível, artista não confirmado e
edição MB não oficial permanecem inelegíveis. O artefato
`discogs-211-type-disagreement-triage.json` é verificável no mesmo
workflow de sondagem. O corpus A preserva dez vagas sem seleção até
surgirem casos realmente divergentes com evidência independente.

## Grupo C — reconciliação por barcode de edições públicas (11/11/2026)

O segundo passe `scripts/catalog_211_discogs_source_only_reconciliation.py` usa os mesmos
artifacts do scanner público (índice + detalhe), sem autenticação e sem refazer
queries Discogs. Apenas uma junção exata de identidade permite consultar no
MusicBrainz até dois códigos de barras Discogs por edição, em no máximo 16 GETs;
os casos inválidos, de vídeo, artista distinto e sem barcode não usam rede.

O resultado `existing_mb_artist_barcode` indica um registro MusicBrainz já existente
com **código de barras e MBID do artista coincidentes**. Isso NÃO valida a mesma
prensagem nem o vínculo Release→Discogs. Respostas incompletas, múltiplos resultados,
barcodes de outros artistas e ausência de hits são estados distintos; **nenhum
resultado negativo comprova lançamento ausente**. O relatório mantém
`confirmed_missing_from_musicbrainz=false`, `reviewed=false` e as métricas do
corpus em zero até adjudicação manual com fonte editorial independente e inspeção
visual. O artifact de comparação é reprodutível e não modifica as prateleiras.

## Grupo C — verificação por título de edição quando falta barcode (11/11/2026)

A primeira execução da etapa de barcode no [scanner #43004860199](https://github.com/jssantogit/BitChord/actions/runs/43004860199) teve 0 consultas MusicBrainz: dos 8 detalhes, 3 foram excluídos por vídeo/crédito de artista e os 5 restantes não tinham barcode Discogs publicamente utilizável. Resultado correto: nenhum registro ausente nem correspondente comprovado.

Para não encerrar a investigação nesse limite, `scripts/catalog_211_discogs_source_only_title_triage.py` consulta o índice `release` de ReleaseGroups MusicBrainz com filtro obrigatório `arid` (MBID do artista) para os casos sem barcode. Limita a uma busca por edição e oito buscas por workflow; utiliza somente título observado e IDs validados, nunca um link inserido no Discogs como destino de HTTP. O resultado `mb_title_index_lead_not_edition` é apenas uma pista de ReleaseGroup já indexado, não valida edição nem relação Discogs. Zero hits e falhas da API nunca são ausência provada. A identidade real de lançamento continua exigindo revisão humana e fonte editorial independente.

O artefato adicional `discogs-211-source-only-title-triage.json` está conectado ao scanner público, separado do corpus 0/30. Busca Lucene usa `release` e `arid` documentados na [API MusicBrainz](https://musicbrainz.org/doc/MusicBrainz_API/Search/ReleaseGroupSearch).

## Grupo C — segundo passe independente, sem reciclar casos B/C (11/11/2026)

Depois do primeiro scanner público (8 edições), um segundo passe separado
`scripts/catalog_211_discogs_fresh_c_followup.py` reusa o índice *já consultado* e
remove todas as identidades Discogs Master/Release previamente hidratadas,
preservando as mesmas 10 relações Artist MBID→Discogs Artist ID e o limite
por artista. Edições com Discogs Release ID já pré-selecionado nos grupos B ou C
do manifesto são descartadas antes da adjudicação, incluindo Masters que
apontam para Release conhecido. O resultado é guardado no artifact
`discogs-211-fresh-c-followup.json`. Nenhuma listagem/página adicional de
artista é consultada; é apenas a segunda amostra da primeira página.
Só ocorrências novas, com crédito de artista exato, faixas e metadados
válidos, prosseguem às sondagens conservadoras por barcode/título MusicBrainz.
Consultas negativas permanecem inconclusivas, e falhas/timeout não provam
ausência. O manifesto, as prateleiras e o contador 0/30 ficam inalterados.

Há limites independentes: máximo 8 novas entradas (2 por artista),
16 GETs Discogs para Release/Master, até 16 buscas barcode MB e no máximo
8 buscas por título MB. Nenhum token ou APK. Este segundo passe é
apenas um auxílio à seleção de C, nunca valida edição ou tipo editorial.

## Grupo C — segunda sondagem real e equivalência bilíngue segura (11/11/2026)

A [sondagem #43008963680](https://github.com/jssantogit/BitChord/actions/runs/43008963680)
concluiu SUCCESS e executou oito GETs Discogs + três consultas MusicBrainz
no segundo passe, sem novos pedidos do índice de artistas.
Foram observadas cinco entradas adicionais:

- ENHYPEN / *Romance: Untold -Daydream-* (Discogs Release 32437206):
  barcode + Artist MBID encontraram o MusicBrainz
  [Release 4c42b226](https://musicbrainz.org/release/4c42b226-24c8-4481-91ce-744ae59eaa5b);
  é um registro MB existente, mas não prova prensagem idêntica.
- TXT / *7TH YEAR* (Discogs Release 37110012): já pré-selecionado no
  caso B-05, corretamente excluído dos novos candidatos C.
- Stray Kids / *Come Play* (Discogs Release 32612256):
  múltiplos créditos, inelegível para artista único.
- TXT / *誓い = Chikai* (Discogs Release 31393100):
  a busca automatizada por título ficou indisponível; no entanto o
  MusicBrainz publica [誓い (CHIKAI)](https://musicbrainz.org/release/f34d9628-3757-4a1a-a7a0-241567df737e)
  com grupo do artista, portanto é falso candidato de ausência no nível
  ReleaseGroup (sem declarar equivalência exata de prensagem).
- Stray Kids / *Social Path / Superbowl (Japanese Ver.)* (Discogs Release
  38526540): a busca literal não encontrou grupo, mas o MusicBrainz lista
  [Social Path / Super Bowl -Japanese ver.-](https://musicbrainz.org/artist/142b343d-bf5a-428c-a64f-6d1a7566bbe9)
  como EP existente e edições na lista do artista. A grafia é diferente;
  manter restrição: não autorizar matching de prensagem nem supor ausência.

Para bloquear falsos candidatos C sem adivinhar traduções, o filtro
MusicBrainz-known do scanner e a política Kotlin do app passam a reconhecer
somente o **mesmo par literal nativo + alias latino** exposto em duas formas
explícitas de fonte, `誓い (CHIKAI)` e `誓い = Chikai`. Remixes, versões com
termos adicionais, aliases inventados e títulos sem escrita nativa continuam
distintos. Esta regra atua apenas no filtro *source-only* e não valida
Release→Discogs. A equivalência genérica entre `Superbowl` e `Super Bowl`
não é habilitada.

As duas fontes oficiais não comprovam nenhuma recuperação real de C;
corpus continua 0/30. Nenhuma modificação de tipagem ou promoção
`Verified` foi realizada.

## Integridade do consenso editorial: provas duplicadas e contraditórias (11/11/2026)

O mesmo Discogs Release ID pode ser observado mais de uma vez em cache ou
reconciliação, mas não pode representar duas afirmações independentes de tipo.
O consenso anteriormente usava `distinctBy(releaseId)` **antes de detectar
contradições**. Quando uma mesma Release chegava como EP e Album, ou vinculada a
dois Masters válidos diferentes, a primeira observação podia determinar o
resultado e permitir erroneamente um `DISCOGS_CORRECTION` do tipo canônico.

A validação agora rejeita em `Disputed` qualquer conjunto de provas
verificadas com tipos distintos ou dois Masters explícitos diferentes para o
mesmo Release ID, em qualquer ordem. Se uma observação carece de Master e outra
informa Master válido, mantém-se a informação positiva para não contar edições
de um mesmo Master como evidências independentes. Não se inferem IDs faltantes.

O armazenamento volátil também impede substituições silenciosas: uma
reobservação contraditória põe apenas aquele Release ID em quarentena até o
vencimento da evidência. Enquanto isso a edição não vota na classificação
Album/EP/Single. Um refresco com o tipo antigo não desfaz a disputa antes da
expiração. Isso afeta somente o modelo de leitura editorial, não modifica
ReleaseGroups MusicBrainz, biblioteca pessoal, áudio, cache de capa ou
atribuição de origem. Testes JUnit cobrem contradição de tipo, Master,
ordem de leitura, Master antes desconhecido e ciclo de expiração.

**Não confundir proteção de integridade com ganho mensurado:** corpus A/B/C
continua sem homologação (0/30); nenhuma correção de tipo real ou edição
Discogs-only foi promovida por esta mudança. APK e smoke físico seguem
dependentes de autorização separada.

## Primeiro caso B revisado com fonte editorial independente — B-02 (11/11/2026)

**TXT / セツナハナビ (Setsuna Hanabi)** — resultado da revisão:
`match_state=candidate`, **NOT VERIFIED**, nenhum ganho automático aplicado.
O [registro MusicBrainz de edição 6770b265](https://musicbrainz.org/release/6770b265-719a-463b-af5e-50ac5aeac1e5)
citado no controle é **digital** (17/08/2026; ReleaseGroup
`cb79234e-c3d3-4973-a3e3-6ec603b3d1f4`, Single), enquanto o
[anúncio primário da Universal Music Japan de 23/07/2026](https://www.universal-music.co.jp/txt/news/2026-07-23/)
descreve o **CD físico** de 19/08/2026, quatro faixas e versão regular
HYBJ-02085 / POCS-30033. O [Discogs Release 38302455](https://www.discogs.com/release/38302455)
foi observado em sondagens da
[CI #42921028437](https://github.com/jssantogit/BitChord/actions/runs/42921028437),
mas o conjunto de evidências não estabelece que a prensagem é
a mesma de um Release MB específico. A edição alternativa MB
`91c4d2eb-d34f-427a-b489-44411063c131` permanece inconclusiva.

**Resultado negativo útil:** não declarar Discogs-only nem mesmo Release
verificada pela coincidência de quatro faixas. O grupo Single está no
MusicBrainz, então `absent_in_musicbrainz=false`. A revisão é **somente
da identidade de fonte e da decisão de não promover**; o aparelho não foi
testado, portanto `app_type=Unknown` / `visible=false` não são medições de
UI e não devem ser apresentados como tal. Não se usa a classificação
do lançamento no MusicBrainz para declarar identidade da prensagem.

O manifesto passa de 0/30 para **1/30 revisado**, quatro pares B ainda
pré-selecionados sem revisão, três C excluídos preliminarmente e
zero ganho confirmado de correspondência, reclassificação ou recuperação.
**O benchmark completo continua bloqueado**, `metrics=null`, até
os 30 casos serem revisados; este caso não autoriza lançamento nem smoke APK.

### Nota de integridade da amostra

Uma revisão B negativa é um resultado real, mas não é uma
demonstração de superioridade Discogs. A contagem de revisões deve incluir
somente casos com fonte editorial independente, ID de Release dos dois
provedores, motivo verificável e status explícito que preserve incerteza.
Exemplos apresentados anteriormente como `0/30` eram históricos;
o corte mais recente é `1/30`.

## Segundo par B revisado — B-05: TXT / 7TH YEAR (11/11/2026)

**Resultado:** `match_state=conflict`, **NOT VERIFIED**, tipo EP
já existente no MusicBrainz, zero reclassificação aplicada.

A [notificação oficial BIGHIT MUSIC no Weverse](https://weverse.io/txt/notice/33998)
identifica o lançamento como *oitavo miniálbum* e registra as variantes
físicas de 13/04/2026. O [MusicBrainz Release
562a42ed](https://musicbrainz.org/release/562a42ed-de56-4282-99f4-e9f7da270098)
é um CD Official do ReleaseGroup
`dc4cfe5f-d5b9-4d18-b281-0691e93d40c8`, tipo EP, selo
BIGHIT MUSIC, catálogo BHE0779, barcode 8800357999260 e seis faixas.
O [varejo coreano](https://www.applemusic.co.kr/m/product.html?branduid=19348)
confirma o par catálogo + UPC informado pelo MB. O
[Discogs Release 37110012](https://www.discogs.com/release/37110012)
foi comparado na
[CI #42921028437](https://github.com/jssantogit/BitChord/actions/runs/42921028437):
catálogo BHE0779 consistente, **barcode divergente**, 4/6 faixas
literalmente iguais; não existe vínculo direto Release MBID→Discogs
Release neste par.

**A gravadora confirma o lançamento e a categoria; não confirma que
duas entradas de catálogos comunitários sejam a mesma prensagem.**
Nenhum identificador de edição é promovido, nenhuma correção para
Album, nenhuma entrada "somente Discogs" inventada. A apresentação
Android continua não aferida (`app_type=Unknown`, `visible=false`:
sem ganho visual comprovado, não declaração de ausência da UI).

O corpus agora tem **2/30 casos B revisados em sentido negativo**,
três outros B pré-selecionados e ainda sem revisão; três exclusões C
preliminares; **zero ganhos comprovados** de novas edições, recuperação
de grupos ausentes ou correção editorial. Não publicar indicadores
agregados até completar o corpus (`metrics=null`). Os registros
anteriores 0/30 e 1/30 permanecem históricos.

### Regra de adjudicação: conflito não é candidato vago

O relatório offline da PR passa a aceitar explicitamente `match_state=conflict`,
que já existe na política Kotlin como `DiscogsEditionVerdict.CONFLICT`.
B-05 registra esse estado porque os códigos de barras apresentados para
o mesmo par de edições físicas são incompatíveis, ainda que selo e número
de catálogo coincidam. A incompatibilidade exige **não vincular** as
edições; a fonte editorial independente confirma o EP, não a mesma
prensagem. Esse caso é revisado, mas não entra em `verified` nem nos
cálculos de precisão de vínculos verificados. O status `candidate` fica
reservado aos casos sem prova suficiente e sem contradição decisiva.

## Não reciclar casos B revisados como possíveis C (11/11/2026)

A passagem de B-02/B-05 para `reviewed=true` expôs uma lacuna
na segunda sondagem C: `_known_releases` guardava somente os B/C
**pré-selecionados e ainda não revisados**. Assim, o mesmo Discogs
Release 37110012, B-05 revisado como conflito de barcode, podia
reaparecer na sondagem como suposto candidato C, mesmo já havendo
evidência de grupo MusicBrainz existente.

Agora o scanner incorpora os **Release IDs válidos tanto de B/C
pré-selecionados quanto de B/C revisados**, exigindo que cada caso
revisto possua links e prova editorial validados pelo manifesto.
A validação do relatório também exige Discogs Release ID concreto
para **todo caso revisto**, inclusive os resultados `candidate` e
`conflict` — não somente os `verified`. Um Master de índice que
resolva para Release conhecida fica excluído na hidratação. A
regressão `test_cannot_revisit_existing_b_release_as_new_c_case`
demonstrou a quebra na [CI #43055313679](https://github.com/jssantogit/BitChord/actions/runs/43055313679)
e protege a correção. Nenhum título ou ID novo foi inventado,
nenhuma ausência MB afirmada e nenhum dado do usuário alterado.

## B-01/B-03/B-04 — revisão independente dos três pares restantes pré-selecionados (11/11/2026)

**Resultado:** os cinco pares B iniciais foram **revisados com fonte
editorial independente e decisão de não promover**. Nenhum se tornou
`Verified`; todos os grupos musicais já existem no MusicBrainz. O
corpus passa de 2/30 para **5/30 revisados**, porém **B-06 a B-10
continuam vagas em aberto**, assim como A e C. `metrics=null`,
nenhuma taxa global de sucesso publicada e nenhuma UI aferida.

### B-01 — ENHYPEN / MEMORABILIA (não é a mesma edição)

A [notificação oficial BELIFT LAB / Weverse](https://weverse.io/enhypen/notice/19177)
confirma CD físico da edição Moon, lançado em 13/05/2024
e com encarte específico. A
[edição MB `d0c0cfe6`](https://musicbrainz.org/release/d0c0cfe6-0d31-469e-965a-3a5c18b1eb10)
é **Digital Media do Japão**, 13/05/2024, seis faixas e
barcode `00602465697520`. A comparação pública dos mesmos IDs
[no scanner #43055872997](https://github.com/jssantogit/BitChord/actions/runs/43055872997)
mostra que o [Discogs Release 31008160](https://www.discogs.com/release/31008160)
é um **CD da Coreia do Sul**, seis faixas em ordem idêntica, sem
barcode compartilhado validado, com título editorial mais longo
`Dark Moon Special Album 'Memorabilia'`. A diferença direta
de país/formato deste **par de edições** impede `Verified`:
`match_state=conflict`. A edição MusicBrainz alternativa
`300beef2` não tem catalog number/barcode que permita confirmar
a mesma prensagem Discogs; segue não aprovada.

A gravadora chama o projeto de `SPECIAL ALBUM`, não confirma
uma categorização EP independente; por isso `reference_type=Unknown`
em B-01. `musicbrainz_type=EP` permanece o tipo existente,
**sem correção automática Album↔EP**. A revisão não atribui tipo
Album só por marketing ou embalagem de CD.

### B-03 — IVE / Fashion (single digital conhecido; identidade exata pendente)

A [comunicação primária Sony Music Japan](https://ssl.sme.co.jp/artist/IVE/info/582836)
identifica o single digital `Fashion` de 03/04/2026, primeiro
lançamento da faixa incluída no EP
[LUCID DREAM](https://www.sonymusic.co.jp/artist/IVE/info/582822).
[Apple Music](https://music.apple.com/us/album/fashion-single/1887487297)
confirma comercialmente 1 faixa e tipo Single. A
[sondagem #43055872997](https://github.com/jssantogit/BitChord/actions/runs/43055872997)
indica que [Discogs 37253067](https://www.discogs.com/release/37253067)
é `File`/Single em 2026, título `Fashion / ファッション`,
com uma faixa, sem barcode comum verificável nem ligação direta
Release→Discogs com a edição [MB `f9ec060a`](https://musicbrainz.org/release/f9ec060a-ca42-4e9c-b31a-eaa5bdd7ff21).
`match_state=candidate`, não duplicar grupo Single conhecido nem
inferir tradução para provar identidade de edição.

### B-04 — IVE / DARE ME (single digital conhecido; identidade exata pendente)

A [nota oficial Sony Music Japan de 21/04/2025](https://www.sonymusic.co.jp/artist/IVE/info/574055)
confirma `DARE ME` como **single digital** japonês. A
[sondagem #43055872997](https://github.com/jssantogit/BitChord/actions/runs/43055872997)
confirma [Discogs 34121731](https://www.discogs.com/release/34121731)
como `File`/Single 2025, `Dare Me / デアミー`, uma faixa,
sem link direto Release→Discogs ou barcode comum com a edição
[MB `5df7296a`](https://musicbrainz.org/release/5df7296a-92e2-48ae-a69e-eaed1f74d066),
do grupo MusicBrainz Single já existente. `match_state=candidate`;
não supor equivalência exata por grafia ou ano.

### Limites da evidência

`reviewed=true` significa **decisão editorial sobre estes IDs de
fonte** com justificativa datada — **não** confirmação no Android.
`app_type=Unknown` e `visible=false` representam ausência de ganho
verificado em campo e **não** são testes de UI negativos. A revisão
A/B/C completa continua pendente: A 0/10, B 5/10 (cinco vagas ainda
sem IDs), C 0/10, três C excluídos preliminarmente. O Discogs
continua complementar; nenhum MBID, MediaAsset ou stream é inventado.

## C: guarda conservadora para singles digitais com título latino / japonês (11/11/2026)

A revisão de B-03/B-04 revelou um falso caminho de descoberta C:
MusicBrainz já conhece os grupos Single `Fashion` e `DARE ME`,
mas a edição Discogs apresenta respectivamente
`Fashion / ファッション` e `Dare Me / デアミー`.
A filtragem inicial do índice não pode tomar a barra e a
segunda escrita como prova da mesma obra; entretanto, depois de
hidratar uma edição `File` explicitamente `Single`, com **uma
única faixa cujo título repete exatamente o título bilíngue do
lançamento**, o primeiro título latino já constar na discografia
completa do mesmo artista é motivo para **não afirmar ausência**.

A nova guarda no `DiscogsShadowCatalog.select` usa apenas essa
sinalização negativa, após a verificação da identidade do artista
e da completude do browse MusicBrainz: limita-se a
`<exact-known-Latin-title> / <Japanese-script>` sem títulos
adicionais, com uma faixa e formato digital Single. Não traduz,
não associa Recording, não cria equivalência de Release,
não altera a classificação editorial, não vincula MBIDs e não
remove o Release original das consultas de fonte. Ela impede
**apenas a publicação como suposta descoberta exclusivamente
Discogs** quando a evidência de ausência é insuficiente.

A exclusão deliberadamente não se aplica a CD/vinil,
coleções de duas faixas, outros tipos de formatos, textos
como `Fashion / Live`, variantes instrumental/remix,
nomes sem grupo MusicBrainz conhecido nem títulos em outra
ordem. A política de candidatos B permanece distinta do
critério source-only C: B-03/B-04 continuam `candidate`,
sem `Verified` da edição. Testes Kotlin verificam os dois
exemplos da API real e controles negativos, sem smoke físico.

## Regressão de formato misto e gate Discogs isolado (11/11/2026)

A prevenção de falso `Discogs-only` para `Fashion / ファッション` e
`Dare Me / デアミー` considera `File`/Single de uma faixa,
mas uma Release Discogs pode declarar **File + CD/Vinyl/DVD**
simultaneamente. Essa publicação não é digital-only: filtrá-la
com base na presença de `File` apagaria uma edição física potencial,
mesmo que o MusicBrainz já conheça o Single digital.

A guarda source-only passa a exigir a presença de `File`,
tipo `Single` e **ausência de mídia física declarada**.
Não inferir equivalência de edições: CD/vinil, mídias mistas,
lançamentos com duas faixas ou títulos desconhecidos continuam
sendo candidatos à investigação normal. Os testes JUnit
`knownSingleDigitalBilingualSlashCannotBeInventedAsDiscogsOnly`
agora verificam File+CD, File+Vinyl e File+DVD,
além dos controles anteriores.

Para não confundir falha de outro subsistema com regressão Discogs,
a workflow `Catalog #211 — Focused Discogs Android unit tests`
executa apenas `Discogs*Test` (incluindo bridges UI) no
`:app:testDevDebugUnitTest`, com `--tests`, **sem APK**.
A Android CI geral do HEAD anterior `299d5b87`
[falhou nos testes `MusicBrainzArtistAppearancesTest`](https://github.com/jssantogit/BitChord/actions/runs/43060820465),
mas compilou Kotlin: isso não prova que o Discogs esteja
vermelho ou verde. A correção MusicBrainz fica no chat/PR
separado. O gate Discogs isolado só prova essa suíte;
não substitui a compilação/testes de integração completos.

## Primeiro controle positivo **já vinculado** — EASY CRAZY HOT / CARBON BLACK (11/11/2026)

Para não confundir o corpus B de relações ausentes com o fluxo de
edições já vinculadas, adicionamos um **controle positivo distinto
do denominador A/B/C**:

- [MusicBrainz Release e6de9634-eb96-4b54-9568-755a99ec312b](https://musicbrainz.org/release/e6de9634-eb96-4b54-9568-755a99ec312b)
  é a versão **CARBON BLACK** em 12" Vinyl de LE SSERAFIM,
  `EASY CRAZY HOT`, **15 faixas**, barcode válido
  `198704525124`, editora SOURCE MUSIC, US, data 05/09/2025,
  ReleaseGroup do tipo Album+Compilation. A própria relação URL
  do MusicBrainz aponta ao **[Discogs Release 35021231](https://www.discogs.com/release/35021231)**.
- O [controle público de comparação na CI #43059656701](https://github.com/jssantogit/BitChord/actions/runs/43059656701)
  observou exatamente esse Release com vínculo direto, mesma ordem
  de 15 faixas e 14 títulos idênticos, com a diferença de hifenização
  `1‐800‐hot‐n‐fun` vs `1-800-hot-n-fun`;
  barcode idêntico nos provedores, ano consistente, label presente,
  artista confirmado por identidade de fonte. A ausência de país
  explícito em uma das respostas é **desconhecido**, não prova de
  país coincidente; o matcher aceita essa ausência na identidade
  **já vinculada**, desde que os outros campos batam.
- O MusicBrainz distingue ainda a versão **SILVER WHITE**, [Release
  281513a2-12d1-4bf5-887f-7ddfd64b9931](https://musicbrainz.org/release/281513a2-12d1-4bf5-887f-7ddfd64b9931),
  com Discogs Release **35025845** e código de barras
  **198704525131**. Não mesclar cores, prensas ou barcodes.

Teste de contrato:
`DiscogsEditionMatcherTest.realEasyCrazyHotCarbonBlackDirectLinkCorroboratesPunctuationWithoutMergingVinylVariants`.
Um vínculo **tipado** MB Release→Discogs Release, com artista, ano,
barcode válido e todas as faixas na mesma ordem permite ao matcher
retornar `VERIFIED` mesmo com pequenas diferenças puramente
tipográficas. Sem o vínculo tipado ou com barcode da outra cor, o
caso **não** é Verified. Uma faixa faltante também impede Verified.
**Isto verifica um exemplo de enriquecimento de uma edição já conhecida;
não é uma edição recuperada, uma correção de classificação ou medição
de interface.** O corpus B permanece 5/10 revisados e 0 candidatos
`Verified` sem vínculo.

### Respostas duplicadas contraditórias não podem provar uma edição

`DiscogsUnlinkedMatch.select` antes utilizava
`distinctBy(releaseId)` para descartar o segundo snapshot do
mesmo Discogs Release. Com um snapshot que confirma tracklist e
outro que contradiz o mesmo ID, a ordem das requisições poderia
transformar o primeiro em `Verified` — ou eliminar um bom
candidato diferente. O seletor passa a agrupar por ID e manter
**somente observações idênticas** para aquele ID; se diferirem,
o ID disputado continua `Candidates`, nunca `Verified`.
Se outro Release separado parecer verificado, a presença do ID
disputado bloqueia a alegação de **unicidade**, resultando em
`Ambiguous` para ambas as edições potenciais. Isso preserva
a chance de revisão sem transformar ausência de certeza em
uma correspondência automática. Os registros de fonte
originais continuam intactos; não se cria
MBID/URL nem se executa playback, e nenhuma divergência é tratada
como evidência de ausência MusicBrainz.

O teste `DiscogsEditorialCatalogPolicyTest.contradictorySnapshotsForSameUnlinkedReleaseCannotBeVerifiedByFirstObservation`
verifica ambas as ordens, repetição idêntica e outra edição
independente. O gate JUnit específico do Discogs executa o
regressionamento; Android CI geral permanece um gate à parte.

## Proteção contra edição Digital Media ↔ CD/Vinyl por meio físico (11/11/2026)

A sondagem B-01 de ENHYPEN / MEMORABILIA ilustra o problema
de confundir **lançamento/grupo de músicas** com **formato físico
da edição**: o MB Release `d0c0cfe6` representa Digital Media,
enquanto a edição Discogs `31008160` representa CD sul-coreano.
Um título, código de barras e ordem de faixas similares não bastam
para dizer que são a mesma edição.

Foi localizada uma lacuna de implementação: o grafo existente
`MusicBrainzCatalogGraph.lookupRelease` já fornece
`MusicBrainzCatalogReleaseDetail.editionFormats` a partir de
`media[].format`, mas `DiscogsCanonicalEditionCoordinator` não
repassava essa lista ao `CanonicalEditionEvidence`. Portanto o
`DiscogsEditionMatcher` não conseguia usar o formato na prova.

A correção transporta a lista de formatos de **cada Release MB**
para o matcher e compara apenas famílias **explicitamente
reconhecidas** nos dois provedores: `Digital Media` ↔ `File`,
CD, CDr, Vinyl (inclusive 12″), Cassette, SACD, MiniDisc, DVD,
Blu-ray e VHS. `Album`/`EP`/`Single` são descrições editoriais,
**não formatos físicos** e nunca entram nessa comparação.

A decisão permanece conservadora:
- Famílias reconhecidas e **disjuntas** ⇒ `CONFLICT` (por
  exemplo Digital Media vs CD, ou CD vs Vinyl).
- Famílias com interseção, mas **conjuntos distintos** ⇒
  `CANDIDATE`, jamais `VERIFIED` (por exemplo File versus
  publicação File+CD; uma mídia adicional poderia indicar
  outra prensagem ou pacote).
- Conjuntos iguais ou formato ausente/desconhecido em uma
  das fontes ⇒ não inventar conflito; continuam valendo os
  requisitos anteriores de Release tipado, artista,
  barcode, sequência de faixas, ano etc.

A verificação de formato é igualmente aplicada ao caminho de
vínculo direto e ao de descoberta sem vínculo. Essa proteção
não altera as regras de tradução de títulos, não adiciona GETs,
não muda MusicBrainz ReleaseGroup/Track/Recording MBIDs,
não escreve bibliotecas pessoais e não gera áudio ou capas.

Regressões `DiscogsEditionMatcherTest` cobrem mídia digital,
CD, vinil, mídia mista e formatos desconhecidos; a regressão
`DiscogsCanonicalEditionCoordinatorTest.releaseGraphDigitalFormatBlocksPhysicalDiscogsEditionEvenWithSameTracks`
cobre o caminho real JSON MB→edição Discogs→veredito.
Isso é **correção de integridade**, não prova de lançamento
Discogs-only recuperado nem aumento do corpus A/B/C, que
permanece 5/30 revisados sem ganhos comprovados.

## A busca B não pode certificar unicidade com hidratação incompleta (11/11/2026)

**Falha na descoberta autenticada (sem vínculo direto):** o método
`DiscogsAuthenticatedDiscovery.findForEdition` consulta dois índices
separados (barcode e título), coleta Release IDs únicos e limita a
oito consultas de detalhe. Entretanto usava `hydrate(ids)`, a
mesma função *best-effort* usada na exploração de artista: um
`404 Missing`, `503 Unavailable`, JSON Discogs malformado ou
limitação de taxa podia ser ignorado; se outra Release fosse
corroborada pelo matcher, o método devolvia `Verified`, apesar
de haver um candidato concorrente **sem detalhe verificado**.

A regra correta para o comparador exato é mais estrita do que
a exploração da discografia: **todos os IDs de Release encontrados
pelos índices completos devem ter resposta de detalhe válida** antes
de alegar que só uma edição corresponde. O novo
`hydrateCompleteEditionCandidates` mantém a consulta pública
sem token, o limite de oito Releases e o mesmo orçamento de HTTP,
mas retorna o estado `Unavailable` se qualquer detalhe falhar.
Um 404 de índice possivelmente desatualizado também não comprova
ausência ou a unicidade de outro Release.

`findForArtist` continua usando hidratação de descoberta
*best-effort*: uma capa ou pista de edição ausente não deve
interromper toda a navegação do artista, pois essa via não
certifica vínculo de identidade exato. A mudança não cria
novas Releases, nem expande resultados C, nem usa MusicBrainz
IDs inventados.

O teste
`DiscogsAuthenticatedDiscoveryTest.incompleteCandidateHydrationCannotVerifyOneRemainingEdition`
cobre dois candidatos Discogs, alternando a ordem em que o
único detalhe verificável chega e resultados adversos 404,
503 e HTTP 200 com JSON malformado. Em nenhum caso é
seguro promover `Verified`; o resultado esperado é
`DiscogsAuthenticatedMatch.Unavailable` (não `NoMatch`).
O corpus A/B/C segue 5/30 revisões, 0 correspondências
sem vínculo confirmadas ou recuperações Discogs-only.

## B sem token — comparar todas as versões de um Master antes de confirmar (11/11/2026)

**Lacuna que impedia encontrar correspondências reais sem vínculo:**
`DiscogsEditionRepository.observeViaReleaseGroupMaster` já reconhecia
a relação tipada `MusicBrainz ReleaseGroup → Discogs Master`,
mas examinava exclusivamente `master.main_release`.
Esse campo é uma **edição representativa do Master, não um
identificador do Release canônico nem garantia de unicidade**.
Isso podia produzir dois resultados incorretos: ignorar a
edição Discogs efetivamente correspondente por ser uma versão
alternativa; ou validar a principal, embora outra variante
Discogs também possuísse barcode, faixas e ano idênticos.

O exemplo verificável
[ILLIT / SUPER REAL ME no MusicBrainz](https://musicbrainz.org/artist/6b128dd7-e998-4edb-a4f5-400fef190445/releases)
contém versões físicas `SUPER ME` e `REAL ME`, ambas CD da
Coreia do Sul, data 25/03/2024, gravadora BELIFT LAB,
catálogo `CMDC12000` e UPC `8809704427821`.
**O mesmo UPC não distingue automaticamente essas duas embalagens.**

A consulta pública Discogs
`/masters/{id}/versions?page=1&per_page=8`
passa a ser utilizada apenas quando existe uma relação
ReleaseGroup→Master tipada no MusicBrainz. Há uma chamada
para o Master, uma para a lista paginada de versões e
até oito para os detalhes de Releases, sempre no limitador
compartilhado, sem credencial ou buscas arbitrárias.
A lista só é considerada completa quando a paginação
afirma **uma página, até oito versões, IDs únicos e
contagem coerente**, incluindo a `main_release`.
Mais de oito versões ou paginação incompleta ⇒
`Candidate`, **nunca** `Verified` por consultar
só a primeira versão. API indisponível ⇒ `Unavailable`.

Cada Release deve pertencer explicitamente ao Master da
relação MusicBrainz. A comparação usa o mesmo
`DiscogsEditionMatcher.assessUnlinked` validado
(artistas/ReleaseGroup, barcode com dígito verificador,
ordem completa de faixas, ano, país, meio digital/físico).
Somente **uma edição Verified e nenhuma outra Candidate**
autoriza o enriquecimento; duas Verified ou uma Verified
com outra Candidate ⇒ `Ambiguous`, com nenhuma promoção;
zero Verified e alguma Candidate ⇒ `Candidate`.
Releases com `Conflict` confirmado não podem contar
como outras prensagens equivalentes.

As regressões Kotlin verificam: duas edições físicas com
mesmo UPC/faixas/ano ⇒ Ambiguous; alternativa única
verificável apesar de main_release divergente ⇒ Verified;
mais de oito versões/paginação incompleta ⇒ Candidate;
versões duplicadas/malformadas não podem criar prova; e
um Master de versão única mantém a prova positiva do
caminho antigo. Não exportar essas relações para
MusicBrainz, não criar Release/Recording MBIDs, áudio ou
dados de biblioteca do usuário. Este avanço abre o
**caminho técnico de avaliação B sem token**, mas não
inventa ainda um novo `Verified` no corpus A/B/C (5/30).

### A busca autenticada não pode sobrescrever ambiguidade já demonstrada (11/11/2026)

Mesmo depois de enumerar a lista pública completa do Master,
a camada opcional de busca autenticada podia reconsultar uma
seleção menor por título/barcode e transformar um `Ambiguous`
do Master em `Verified`. Isso destruiria o consenso entre
as fontes: a lista completa tem precedência sobre índice parcial.
O fallback agora encerra com o resultado do Master quando há
`Verified`, `Ambiguous`, `Candidate`, `Unavailable`,
`Invalid` ou `Disabled`. A busca pessoal com token continua
possível quando não existe relação Master (`NoLink`) ou quando
a evidência de detalhe do Master foi explicitamente contraditória
(`Conflict`), sempre sujeita aos limites de hidratação.

O seletor público e autenticado de IDs exatos também exigirá
que um resultado `Verified` seja **único entre candidaturas
não descartadas**: uma segunda Release `Candidate` permanece
concorrente e produz `Ambiguous`; apenas uma Release
`Conflict` não compromete uma alternativa comprovada.
Não converter falta de informação em ausência. Regressões
`verifiedPlusUnresolvedDifferentReleaseMustStayAmbiguous` e
`masterVersionsMustAllBeExaminedBeforeClaimingOneUnlinkedEdition`
validam os dois caminhos com candidatos reais ou fixtures
baseadas em variantes físicas.

## Sobrecarga do seletor de candidatos: fail closed além de 32 (11/11/2026)

O seletor compartilhado `DiscogsUnlinkedMatch.select` recebia uma lista potencialmente
maior que 32 Releases e avaliava silenciosamente apenas `take(32)`. Se uma versão
concorrente ocupasse a posição 33 ou posterior, os primeiros 32 itens poderiam
resultar em `Verified` falso, mesmo sem uma lista completa. Isso é uma
lacuna no contrato reutilizável, apesar de a descoberta HTTP de hoje limitar
a hidratação a oito Releases.

A regressão `overBudgetCandidateListCannotCertifyOnlyTheFirst32Releases`
no commit [10418811](https://github.com/jssantogit/BitChord/commit/104188113292709c6c880248f4aca20391d53b07)
foi confirmada em RED pela [CI #43103503012](https://github.com/jssantogit/BitChord/actions/runs/43103503012):
165 testes, **1 falha apenas na regressão nova**. A implementação
[987d6416](https://github.com/jssantogit/BitChord/commit/987d641634b59c5e8d22616eb31e8dc2cc9702cf)
passa a devolver `Candidates` quando recebe mais de 32 observações,
antes de tentar verificar unicidade. Não expande a rede, não elimina
IDs de fonte nem cria vínculos novos. O [GREEN focado #43104371010](https://github.com/jssantogit/BitChord/actions/runs/43104371010)
concluiu **SUCCESS** no commit `987d6416` (`BUILD SUCCESSFUL in 5m 50s`),
com a suíte `Discogs*Test`. Isso não substitui Android CI geral nem smoke físico.

## Sondagem pública independente de Masters dos cinco controles B revisados (11/11/2026)

O script `scripts/catalog_211_discogs_master_versions_probe.py` foi
introduzido em [79b6739e](https://github.com/jssantogit/BitChord/commit/79b6739e981e307e7efcc1c02c0cd6572040316c),
com cinco regressões offline em [99a1cd7e](https://github.com/jssantogit/BitChord/commit/99a1cd7e9eb0164af8e68b62fe5fc63c171b81b7).
O workflow independente [2fbfdebd](https://github.com/jssantogit/BitChord/commit/2fbfdebd754fbecbc372865a1034c06bf31d4032)
consulta **somente B-01..B-05** já source-reviewed: primeiro verifica
a relação tipada MusicBrainz ReleaseGroup→Discogs Master e só depois
consulta Master, lista completa de até oito versões e, se possível,
todos os detalhes das edições. Máximo global de 55 GETs, intervalo
de 1,1 s, somente APIs públicas, sem token. Páginas incompletas,
Master incoerente e edição com master_id contraditório são inconclusivos.
O resultado é **observação de fonte**, nunca `Verified` por si só.

A [execução real #43104720316](https://github.com/jssantogit/BitChord/actions/runs/43104720316)
concluiu SUCCESS, inclusive **5/5 testes offline**. Foram feitos
**5 GETs MusicBrainz**, sem GET Discogs porque o requisito de relação
tipada não foi observado:

- **B-01 ENHYPEN / MEMORABILIA:** `no_typed_master_relation`.
- **B-02 TXT / Setsuna Hanabi:** `no_typed_master_relation`.
- **B-03 IVE / Fashion:** `no_typed_master_relation`.
- **B-04 IVE / DARE ME:** `no_typed_master_relation`.
- **B-05 TXT / 7TH YEAR CD:** `source_unavailable`, **não** ausência de Master.

A inexistência de relação tipada na resposta consultada **não significa**
ausência da edição Discogs, da obra ou de outros caminhos de descoberta.
O fluxo de Master sem token está tecnicamente válido, mas não se aplica
a esses quatro controles na evidência atual. O próximo passo útil é
identificar controles **B-06..B-10** com relação RG→Master comprovada
e lista de versões dentro do teto; quando não houver tal relação,
continuar as pistas de barcode/edição já existentes sem inventar
vínculo ou forçar `Verified`. O corpus permanece **5/30 casos revisados,
0 novos B sem vínculo Verified e 0 C recuperados**, sem APK, smoke físico,
merge, novas identidades MBID ou mudança no MusicBrainz #209.


## B-06..B-10 — novas relações RG→Master públicas, sem homologação (11/11/2026)

A triagem não poderia continuar procurando evidência de Master nos cinco pares
B-01..B-05: quatro RGs não tinham relação tipada nas respostas recentes e
o quinto ficou indisponível. Em vez de inventar relação ou insistir nos
mesmos controles, o novo `catalog_211_discogs_master_lead_scout.py`
consulta **apenas a primeira página de 25 RGs** de até dez artistas
conhecidos com `inc=artist-credits+url-rels`; exige crédito solo por MBID
exato e vínculo URL RG→Discogs Master, rejeita duplicatas e Masters
associados a grupos contraditórios, exclui grupos B já revisados, inspeciona
no máximo cinco Masters e mantém o limite compartilhado de **25 GETs**.
Falhas de um artista ficam visíveis, sem paralisar as pistas independentes,
mas **nenhuma lista de artista é declarada exaustiva**.

A primeira implementação truncava silenciosamente linhas já observadas
antes de verificar Masters compartilhados. A regressão com 102 linhas
foi [RED #43107121343](https://github.com/jssantogit/BitChord/actions/runs/43107121343);
a correção [6d783a6b](https://github.com/jssantogit/BitChord/commit/6d783a6b0ec4133250173d4d8177500c73e9b92b)
examina todas as no máximo 250 linhas observadas antes de escolher até cinco
leads. O bloqueio global após HTTP 503 também foi reproduzido em
[RED #43107359728](https://github.com/jssantogit/BitChord/actions/runs/43107359728)
e corrigido no [9e147df7](https://github.com/jssantogit/BitChord/commit/9e147df7277e58b150f3e7d60ccf09a98e0467b2).
[CI #43107430706](https://github.com/jssantogit/BitChord/actions/runs/43107430706)
passou os sete testes offline e conseguiu observar cinco leads reais,
três Masters pequenos e dois grandes naquela amostragem.

O passo seguinte adicionou uma consulta **completa ou inconclusiva**
de Releases oficiais do grupo por MBID, sem escolher prensagem por título,
mídia ou primeiro resultado. O teste de contrato foi
[RED #43107805527](https://github.com/jssantogit/BitChord/actions/runs/43107805527)
e a implementação [6b400a58](https://github.com/jssantogit/BitChord/commit/6b400a582752f7342b5fcced143d69939c87534a)
passou em [CI #43107919302](https://github.com/jssantogit/BitChord/actions/runs/43107919302):
**8/8 testes offline, 22 GETs**, nove índices de artista observados,
um indisponível. O limite é conservador: 25 Releases por RG;
uma paginação maior NÃO prova ausência de outras edições.

| Pista RG observada | Discogs Master | Resultado público |
|---|---:|---|
| ENHYPEN — BORDER : DAY ONE, RG `849d8582` | `1887999` | 3 versões Discogs completas, 3 MB Releases Official listadas |
| LE SSERAFIM — FEARLESS, RG `fb426d76` | `2653076` | 5 versões Discogs completas, 9 MB Releases Official listadas |
| LE SSERAFIM — ANTIFRAGILE, RG `cfc67890` | `2834228` | Master grande, não exaustivo |
| LE SSERAFIM — FEARLESS, RG `c54392ad` | `2986856` | Master grande, não exaustivo; outro RG, não fundir |
| LE SSERAFIM — HOT, RG `50bcea80` | `3782390` | Master grande, não exaustivo |

Os dois casos pequenos alimentam uma **matriz de pares completos de
fonte** em `catalog_211_discogs_master_pair_matrix.py`. Ela reafirma
o RG→Master, exige lista completa de versões <=8, enumera todos os
MB Releases Official do grupo (máximo 12), hidrata cada Release de ambos
os lados com IDs exatos e compara o produto cartesiano sem deduplicar
variantes. Qualquer resposta ausente bloqueia o conjunto; `Verified`
permanece **sempre false** neste scanner, mesmo com tracklist e UPC
literalmente coincidentes. Testes offline [CI #43108603679](https://github.com/jssantogit/BitChord/actions/runs/43108603679)
**4/4 GREEN**; o primeiro corte usou 23 GETs:
- ENHYPEN: hidratação parcial no primeiro corte, **0 pares publicados**.
- LE SSERAFIM: **9×5 = 45 pares de fonte comparados**, dos quais
  seis tinham todas as faixas e o barcode coincidentes no diagnóstico.
  Essa coincidência NÃO é prova de prensagem exclusiva, nem voto editorial
  independente: versões do mesmo Master compartilham frequentemente
  títulos e UPCs.
- Regressão de identificação de fonte indisponível
  [RED #43108896923](https://github.com/jssantogit/BitChord/actions/runs/43108896923)
  e correção [f82b028b](https://github.com/jssantogit/BitChord/commit/f82b028be7b3914d69fe480aa6b412ad983c8266)
  permitem retry limitado e diagnóstico preciso.
  [Retry ENHYPEN #43108964124](https://github.com/jssantogit/BitChord/actions/runs/43108964124)
  **SUCCESS (4/4 testes; 10 GETs)** completou as três edições MB
  versus três Discogs: **9 pares**, nenhum com simultaneamente
  tracklist completa e barcode idêntico. Não declarar mesma edição
  nem ausência de prensagem por esse resultado.

**Terceira fonte independente das variantes, não dos pares de IDs:**
- [BELIFT LAB](https://beliftlab.com/artist/discography/ENHYPEN/1?lang=en)
  e [aviso oficial ENHYPEN no Weverse](https://weverse.io/enhypen/notice/789)
  identificam BORDER : DAY ONE como **1º mini-álbum**,
  30/11/2020, **DAWN e DUSK**, CD e seis faixas.
- [SOURCE MUSIC, anúncio FEARLESS no Weverse](https://weverse.io/lesserafim/notice/2469)
  confirma o 1º mini-álbum de 02/05/2022 com versões **Vol.1
  e Vol.2**; há ainda anúncio de variante
  [Monochrome Bouquet](https://weverse.io/lesserafim/notice/2603).
  Esses anúncios tornam **especialmente inseguro** usar barcode/faixas
  como identidade de embalagem, mas não identificam qual MB Release
  equivale a qual Discogs Release.

**Estado medido:** a matriz e o scanner são instrumentos de
triagem, não o corpus adjudicado. **B-06..B-10 permanecem sem revisão
homologada**. A/B/C = **5/30 revisados**, sem novos B sem link
`Verified`, sem C recuperados, sem alteração de `main`, MusicBrainz
#209, UI, Room, playback, APK/smoke ou prioridade de provedores.
A próxima discriminação exige cotejar UPC válido por versão, número de
catálogo/gravadora *em par*, país, formato, arte da embalagem e relação
direta quando disponível, preservando concorrência entre versões.


### Diagnóstico discriminante ENHYPEN e recuperação de 503 (11/11/2026)

A investigação não deve transformar erro HTTP temporário em ausência. O
script da matriz registra o ID exato de fonte cuja hidratação falhou;
a regressão [RED #43108896923](https://github.com/jssantogit/BitChord/actions/runs/43108896923)
foi corrigida em `f82b028b`. Um único novo GET é permitido **somente**
após HTTP 503 para endpoint exato MusicBrainz (RG, browse de Releases
ou detalhe de Release); 404, 429 e outros erros não têm retry
automático. Cada tentativa utiliza o mesmo orçamento de até 40 GETs
e intervalo de 1,1 segundo, sem criar índice ou cache persistente.
Os contratos foram reproduzidos em
[RED #43109485576](https://github.com/jssantogit/BitChord/actions/runs/43109485576)
(Release) e [RED #43109666323](https://github.com/jssantogit/BitChord/actions/runs/43109666323)
(RG), corrigidos em [ea00ddc6](https://github.com/jssantogit/BitChord/commit/ea00ddc64ff7a10f09940f61cc616b02c7071faa)
e [a635bf0c](https://github.com/jssantogit/BitChord/commit/a635bf0c2a7f3f7df215667bd6ad6e0592896e7a).

[CI #43109735646](https://github.com/jssantogit/BitChord/actions/runs/43109735646)
concluiu **SUCCESS, 6/6 testes offline, 12 GETs reais**, com matriz
completa ENHYPEN `BORDER : DAY ONE`: 3 MB Releases x 3 Discogs
Releases = 9 pares. Nenhum tem simultaneamente tracklist integral e
barcode iguais:

- MB Digital Media `5af3b323` e Discogs File `16296898`:
  6/6 títulos em ordem, **barcode não idêntico**; as duas edições
  físicas Discogs `16620135` e `16620078` também têm seis títulos,
  mas o meio físico diverge da edição MB digital.
- MB CDs `6ca81526` e `cb4114dc`: nos Discogs CDs
  `16620135`/`16620078`, o barcode coincide e país/ano são
  compatíveis, mas apenas **4/6 títulos** coincidem literalmente.
  As duas variantes permanecem concorrentes, como esperado pelas
  embalagens DAWN/DUSK da gravadora.
- Nenhum dos nove pares tem vínculo direto MB Release→Discogs Release
  no payload observado. **Sem `Verified` e sem alegação de edição ausente
  no MusicBrainz.** Diferença de grafia pode ser revisada, mas não
  traduzida automaticamente em identidade de prensagem.

O reprocessamento focado não reconsulta FEARLESS desnecessariamente.
O [controle FEARLESS #43108603679](https://github.com/jssantogit/BitChord/actions/runs/43108603679)
continua a prova de 45 pares completos e seis coincidências de
barcode/faixas, **sem identidade de embalagem**. Resultados
contingentes de 503 subsequentes não apagam o sucesso datado;
tampouco permitem declarar que toda execução futura estará disponível.
Corpus segue 5/30, B-06..B-10 ainda não homologados.


## Prensagens com código de barras igual, mas catálogos contraditórios (11/11/2026)

A inspeção real das matrizes completas de `BORDER : DAY ONE` e
`FEARLESS` revelou uma prova adicional que a lógica Android **não
utilizava**: o grafo MusicBrainz já expunha o nome da gravadora com
número de catálogo, mas somente numa string de apresentação
`"Label (Catalog)"`; `DiscogsCanonicalEditionCoordinator` não
transportava o **par tipado** para `CanonicalEditionEvidence`.
Consequentemente `DiscogsEditionMatcher` poderia considerar
`Verified` uma prensagem com mesmo artista, UPC e faixa, mesmo que
ambos os provedores afirmassem **números de catálogo diferentes para
a mesma gravadora**.

- Primeiro teste de regressão Android publicado em
  [cf2cfd8d](https://github.com/jssantogit/BitChord/commit/cf2cfd8dfc72bbbb20c9ab626ce7013af0cfd47d)
  e integração JSON MB→Discogs em
  [4c05cf47](https://github.com/jssantogit/BitChord/commit/4c05cf47322f68f4df290a38748d9b94fccbffaa).
  [CI RED #43114346635](https://github.com/jssantogit/BitChord/actions/runs/43114346635)
  falhou somente na compilação dos testes novos:
  `Unresolved reference editionLabelCatalogPairs`
  (`compileDevDebugUnitTestKotlin`). Isso demonstra que o campo
  ainda não existia no contrato antes da correção.
- Implementação em [f1f6afc0](https://github.com/jssantogit/BitChord/commit/f1f6afc0b285a154d079e2809884404f0a62bbf3),
  [eb951f11](https://github.com/jssantogit/BitChord/commit/eb951f110dae6a0430afc62fc4ee6e3cf3995dc5)
  e [aacb7ed8](https://github.com/jssantogit/BitChord/commit/aacb7ed8555b5e6fe4573de406395b72e89408ea):
  preserva `(label, catalog-number)` no grafo de **exata Release MB**,
  transporta pelo coordenador e reconhece contradição somente
  quando o **mesmo nome literal de gravadora**, normalizado em
  case/whitespace, apresenta conjuntos disjuntos de catálogos
  explicitamente conhecidos. Resultado seguro = `Candidate`, não
  `Conflict`: aliases de gravadora, números faltantes e maneiras
  de descrever a mesma prensagem ainda podem explicar a divergência.
  Não traduz nome de selo, não usa heurísticas de catalog aliases,
  não descarta relações e não promove equivalências.
  **GREEN da suíte Android/JVM no novo HEAD deve ser verificado
  separadamente**; não confundir código publicado com teste aprovado.

**Provas de fonte reproduzíveis:** o scanner público
`catalog_211_discogs_master_pair_matrix.py` passou a incluir o
diagnóstico `label_catalog` em cada par de edição, sem autenticação,
sem writes e com `verified=false` obrigatório. A regressão
[RED #43114709845](https://github.com/jssantogit/BitChord/actions/runs/43114709845)
falhou por ausência desse campo; o novo helper preserva contradições
de label/catalog na [correção dcd31a8d](https://github.com/jssantogit/BitChord/commit/dcd31a8d664d256b6b9b89d005fd32d221746908).
O scanner [GREEN #43115086741](https://github.com/jssantogit/BitChord/actions/runs/43115086741)
concluiu **7/7 regressões e 29 GETs**, comparando 9 pares
ENHYPEN e 45 FEARLESS completos no mesmo corte.

A fonte ENHYPEN fornece **quatro contradições concretas** para os
dois MB CDs `6ca81526`/`cb4114dc` e Discogs CDs
`16620135`/`16620078`: ambos apresentam selo **BELIFT LAB**,
mas o MusicBrainz declara `L200001786`, enquanto o Discogs
declara `CMCD 11590`. Mesmo quando UPC coincide e 4/6 faixas
são literalmente iguais, **não afirmar a mesma prensagem**.
Esses IDs e números são observações de API, não conclusão
sobre erro editorial de qualquer provedor.

Em FEARLESS, seis pares satisfaziam a igualdade literal
tracklist+UPC no diagnóstico simples; a inspeção dos vínculos
mostra:
- **3** pares com relação direta MB Release→mesmo Discogs Release:
  `41f071ef→23594660`, `55092c59→23316113`,
  `cd7fa327→23314901`. São controles **já ligados**, não
  recuperações B sem relação.
- **2** pares apontam para **outra** edição Discogs, apesar de
  coincidirem UPC/faixas: `55092c59→23314901`,
  `cd7fa327→23316113`. Nunca sobrepor a relação tipada do MB.
- **1** par sem relação direta (`cf60fbce→23314901`) diverge
  em **país e ano** do Discogs e, por isso, não comprova identidade.

Um segundo teste da matriz
[RED #43115582484](https://github.com/jssantogit/BitChord/actions/runs/43115582484)
demonstrou a ausência de um aviso para Releases Discogs já
**explicitamente vinculadas a outro MB Release no mesmo grupo**.
O campo `competing_direct_release_owner_mbids` foi incluído
em [75b957a9](https://github.com/jssantogit/BitChord/commit/75b957a979b2b2d41dfa3a48c7f5a09b04f12dcc),
sem novos GETs ou inferência reversa de propriedade.
[CI #43115670023](https://github.com/jssantogit/BitChord/actions/runs/43115670023)
concluiu **8/8 testes e 28 GETs**, comparações completas.
O campo é um **aviso de concorrência de IDs**, não uma
atribuição canônica no BitChord.

**Estado:** a nova evidência reforça falsos candidatos, mas ainda
não homologa B-06..B-10. Corpus **5/30 revisados**, sem nova edição
não vinculada `Verified` ou recuperação C, sem APK/smoke,
merge, alteração na #209 ou mudança de provedor primário.


## Revisão B concluída sem correspondências inventadas — 10/10 pares (11/11/2026)

A comparação repetida dos Masters pequenos expôs um problema de
**integridade em produção**, não apenas do scanner: o grafo MusicBrainz
exibia gravadora e catálogo como texto de apresentação, mas o matcher
de edição exata não recebia o **par de fonte** `(label, catalog-number)`.
Mesmo UPC e lista de músicas idênticos podem coexistir em embalagens
diferentes, portanto é incorreto declarar `Verified` quando **a mesma
gravadora declara números de catálogo contraditórios**.

O contrato agora mantém `editionLabelCatalogPairs` estruturado no
`MusicBrainzCatalogReleaseDetail`, passa os pares pelo
`DiscogsCanonicalEditionCoordinator` e compara no
`DiscogsEditionMatcher` tanto com vínculo tipado quanto sem ele.
Nome de gravadora e catálogo são comparados apenas por case/whitespace;
aliases, catálogos ausentes e nomes de gravadoras divergentes não
inventam contradições. Conflito de números sob **o mesmo label literal**
resulta em `Candidate`, nunca `Verified` nem prova categórica de
outra prensagem.

Foi fechada ainda a perda silenciosa da sétima/17ª relação de catálogo:
a lista de evidência MB agora mantém até 64 pares estruturados; quando
a fonte informa mais que o teto ou `label-info` malformado, transporta
`editionLabelCatalogComplete=false`. O matcher não declara
`Verified` com evidência explicitamente incompleta e não utiliza
`take(16)` para ocultar uma linha que pode contrariar as anteriores.
O limite de seis strings da apresentação permanece separado da
prova de edição. Regressões Kotlin cobrem a contradição de selo,
um par ao final da lista, o teto >64, integração do JSON do grafo e
controles em que a evidência é desconhecida. [CI Android/JVM Discogs
#43116317156](https://github.com/jssantogit/BitChord/actions/runs/43116317156)
concluiu **SUCCESS** (`BUILD SUCCESSFUL in 5m 59s`).
A primeira tentativa [#43115011842](https://github.com/jssantogit/BitChord/actions/runs/43115011842)
havia falhado em **1 de 167 testes** porque a fixture do
matcher `assessUnlinked` omitiu o ano, campo que ele exige;
foi corrigida a fixture, não relaxada a regra de produção.
Isso é uma suíte Discogs focada, **não** full Android ou smoke.

### IVE — comparações públicas sem token (prova adicional de variantes)

O novo scanner de Masters [#43117680744](https://github.com/jssantogit/BitChord/actions/runs/43117680744)
passou **9/9 testes**, examinou 22 solicitações, excluiu
as sete revisões B anteriores e localizou os Masters tipados
de IVE `ELEVEN` (`2531575`, duas versões Discogs)
e `ELEVEN -Japanese ver.-` (`2911144`, quatro versões).

A [matriz pública #43117923979](https://github.com/jssantogit/BitChord/actions/runs/43117923979)
terminou **SUCCESS (9/9 testes, 26 GETs)**:
- `ELEVEN` Coreia: 4 MB Official × 2 Discogs = **8 pares**.
  Quatro pares de CDs têm mesmo UPC e títulos, mas todas essas
  coincidências possuem relação direta MB→Discogs Release
  **deste** ou de **outro** Release. Não são ganho de ligação B.
  O candidato digital MB `47106725` versus CD Discogs
  `21255514` tem meio e contagem distintos (1 MB Track
  contra 2 Discogs Tracks), embora os UPCs reportados coincidam.
- `ELEVEN -Japanese ver.-`: 6 MB Official × 4 Discogs
  = **24 pares**, **zero pares** simultaneamente iguais em
  lista inteira de faixas e UPC. Dez combinações apresentaram
  divergência literal de catálogo sob o mesmo selo Ki/oon.
  A apresentação `KSCL-3394` versus `KSCL 3394`
  também é registrada como diferença textual e mantida
  inconclusiva; não converter formatação automaticamente
  em equivalência de edição.

As referências **independentes** são a
[STARSHIP Entertainment, ELEVEN em 01/12/2021](https://www.starship-ent.com/board/newrelease/466),
a [IVE Japan, ELEVEN original](https://ive-official.jp/mob/news/diarKijiShw.php?id=154826&ima=2150&site=DIVE),
e a [Sony Music Japan, ELEVEN -Japanese ver.-](https://www.sonymusic.co.jp/artist/IVE/info/544910).
A [discografia oficial IVE Japan](https://ive-official.jp/mob/news/diarKijiShw.php?id=197538&ima=0411&site=DIVE)
registra 19/10/2022, **duas faixas** e quatro
embalagens distintas: I (CD+livreto, `KSCL-3390~91`),
V (CD+Blu-ray, `KSCL-3392~93`),
E (CD, `KSCL-3394`) e FC (CD+espelho,
`KSC8-20~21`). O parentesco de conteúdo NÃO identifica
uma das embalagens automaticamente.

### Corpus B-08, B-09 e B-10 (revisões negativas e candidata)

A seleção dos três pares restantes utilizou a matriz completa
de 32 pares e as fontes editoriais oficiais, **não** uma busca
por título simples:

- **B-08** — MB `47106725-be2e-4af7-9d27-4610f4116e0b`
  (`ELEVEN`, edição Digital Media com uma faixa) versus
  Discogs `21255514` (CD duas faixas), sem vínculo direto:
  `conflict` de identidade exata mesmo com UPC reportado igual.
- **B-09** — MB `251ad807-4bf0-4810-a4f9-34673d6d2ff8`
  (`ELEVEN -Japanese ver.-`, Ki/oon `KSCL-3390`,
  CD da versão I) versus Discogs `25759165`
  (Ki/oon `KSCL-3392`, CD+Blu-ray versão V):
  `conflict` da embalagem, com catalogação diferente
  confirmada pela Sony, UPC divergente e apenas um dos
  dois títulos literalmente igual.
- **B-10** — MB `b9290e0d-be3a-41d0-b145-88fe342aa5da`
  versus Discogs `25524349`, ambos possíveis
  `ELEVEN -Japanese ver.-` versão E:
  `candidate`, com UPC igual e label Ki/oon
  `KSCL-3394`/`KSCL 3394`, mas apenas
  1/2 títulos literais e nenhum vínculo exato MB Release URL
  na observação. Plausível não significa `Verified`.

Todos permanecem *não Verified* e `app_type=Unknown` /
`visible=false` significam que o Android não foi medido,
não uma falha de interface comprovada. B-06 e B-07, também
não Verified, eram respectivamente BORDER : DAY ONE e
FEARLESS. O corpus foi ampliado de sete para **10/10
casos B source-reviewed**, sem promover coincidências de
barcodes a acertos de edição e sem preencher outros grupos.
[CI de corpus #43118511020](https://github.com/jssantogit/BitChord/actions/runs/43118511020)
**SUCCESS**, 12/12 regressões do relatório e 5/5 do
baseline; `10 reviewed B, none Verified`, sem métricas
globais fabricadas. A e C seguem **0/10 revistos**
cada; três exclusões C são preliminares, não
recuperações. **Total: 10/30 casos revisados,
0 correspondências B unlinked Verified, 0 recuperações C.**

Não foram criados MBIDs, áudios, gravações locais,
caches de usuário, APKs, smoke, merge, alterações na
#209/main ou prioridade Discogs sobre MusicBrainz.


### Encerramento operacional do scanner B (11/11/2026)

O caso de uso do scanner não justifica novas consultas quando os dez
slots B já foram **revisados, embora nenhum `Verified` tenha sido
homologado**. `scout_corpus` verifica a integridade do manifest
e só chama o scanner real se existe uma vaga B não revisada. Com
B=10/10 retorna `b_cohort_all_slots_reviewed`, zero GET,
zero novos IDs/candidatos `Verified` e aviso explícito de que
uma amostra concluída não é cobertura real de catálogo.
Regressão [290ef3f8](https://github.com/jssantogit/BitChord/commit/290ef3f8b70d2c61007acb2a1e1885c0831b5e47)
e implementação [f2713ae7](https://github.com/jssantogit/BitChord/commit/f2713ae77da7a0581f9b1022b3e071a1022773ad).
[CI #43118898086](https://github.com/jssantogit/BitChord/actions/runs/43118898086)
**SUCCESS, 10/10 testes offline, zero solicitações externas**.

Próximas frentes efetivas do corpus são **A (classificação Album/
EP/Single)** e **C (descoberta Discogs-only sem duplicar MusicBrainz)**.
Exigir fonte editorial independente e vínculo exato de grupo/edição.
Não inverter fontes, não ampliar automaticamente a descoberta para
Masters grandes, não transformar revisões negativas B em
precisão/ganho de cobertura nem confundir tipo de obra com
formato de uma embalagem. **A=0/10; B=10/10; C=0/10;
corpus total 10/30; 0 edições B sem vínculo Verified; 0 C
com ausência comprovada e recuperação de UI medida.**


## Retomada A/C — 11/30 revisões, correção de falso negativo de título (11/11/2026)

### A — tipo editorial não é propriedade de uma embalagem Discogs

Os seis controles exatos anteriores
([candidate CI #43118510232](https://github.com/jssantogit/BitChord/actions/runs/43118510232))
produziram **zero divergências Album/EP/Single elegíveis**: quatro
controles sem vínculo direto com mesma edição e duas observações
sem `discogs_explicit_type` suportado (uma destas com relação direta).
Isso não prova que não há classificação errada no catálogo.

O classificador offline `catalog_211_discogs_type_triage.py` antes
marcava como `linked_type_disagreement_needs_review` qualquer
diferença de tipo com link direto, mesmo quando ano, país, barcode,
artista, título ou faixa da exata edição discordavam. O TDD
[RED #43119996843](https://github.com/jssantogit/BitChord/actions/runs/43119996843)
e [GREEN #43120089674](https://github.com/jssantogit/BitChord/actions/runs/43120089674)
introduziram `linked_edition_evidence_inconclusive` e a lista
auditável `missing_editional_proofs`. A inconsistência impede até
a *pista* de revisão de tipo, quanto mais uma correção automática.
Barcodes não presentes permanecem desconhecidos, sem serem inventados
como correspondências.

O [scanner de 4 Masters #43120411787](https://github.com/jssantogit/BitChord/actions/runs/43120411787)
foi **GREEN (11/11 regressões, 56 GETs)** e comparou **86 pares**
(ENHYPEN 3×3, LE SSERAFIM 9×5, IVE 4×2 e 6×4), preservando
`source_type_disagreement` e
`a_type_reviewer_lead_not_adjudicated` separados. Resultado:
**0 leads A qualificados**, **0 correções**, **0 revisões A**.
Essa etapa não muda tipos, MBIDs ou estantes.

### A — resolução da amostragem enviesada e do limite de Masters

Uma falha de amostragem impedia explorar artistas diferentes: ordenar
todos os grupos pelo nome do artista consumia a cota de cinco Masters
com os primeiros cinco álbuns de IVE. O novo escalonador preserva
**todos os candidatos** para checar Master compartilhado, mas
seleciona um por artista por rodada; dentro de cada artista, prioriza
Single/EP sobre Album como *agenda de requisições*, não como
inferência editorial. [RED #43121516719](https://github.com/jssantogit/BitChord/actions/runs/43121516719)
→ [GREEN #43121658790](https://github.com/jssantogit/BitChord/actions/runs/43121658790)
(cota entre artistas); [RED #43122071843](https://github.com/jssantogit/BitChord/actions/runs/43122071843)
→ [GREEN #43122136152](https://github.com/jssantogit/BitChord/actions/runs/43122136152)
(prioridade de Singles), **13/13 regressões**, 20 GETs.
A coorte B concluída agora bloqueia só a exploração *B*:
`scout_type_leads` é separado, com `--scope A`; não promove um
caso B nem abre uma revisão A sem fonte independente.

O novo corte real A resultou em cinco artistas distintos:
**IVE — After LIKE**, **LE SSERAFIM — FEARLESS**,
**NMIXX — Fe3O4: BREAK**, **NewJeans — How Sweet & Bubble Gum**,
**Stray Kids — ATE**. Todos os Masters ultrapassam o limite
seguro de oito versões; retornam
`master_too_large_not_exhaustive` com zero correspondência/edição
confirmada. Isso é um **limite de evidência conhecido**, não
uma falha de CI e não uma razão para relaxar segurança.
Próxima investigação A deve priorizar relações de edição exata já
existentes ou Masters menores com prova editorial, sem ampliar
indefinidamente a varredura.

### C-04 — falso negativo real de título, não novo EP

A [candidate CI #43118510232](https://github.com/jssantogit/BitChord/actions/runs/43118510232)
inspecionou Discogs Release `38526540` de Stray Kids, artista
Discogs ID `6838809` ligado ao MusicBrainz Artist
`142b343d-bf5a-428c-a64f-6d1a7566bbe9`. O nome Discogs
`Social Path / Superbowl (Japanese Ver.)` sem barcode forneceu
**zero resposta** na pesquisa literal MusicBrainz `release:`.
Isto **não demonstrava ausência**. A página oficial
[MusicBrainz ReleaseGroup e248dce6](https://musicbrainz.org/release-group/e248dce6-bef8-4d1f-a6d8-164de112151f)
já classifica `Social Path / Super Bowl -Japanese ver.-` como
EP de Stray Kids, com uma edição oficial digital de 5 faixas.
A [Sony Music Japan](https://www.sonymusic.co.jp/artist/StrayKids/info/554372)
confirmou independentemente o JAPAN 1st EP, lançamento
06/09/2023 e as cinco músicas. A fonte Discogs observada não
estabelece correspondência **da mesma edição**, apenas que o
produto editorial não está ausente do MB.

Uma segunda consulta opcional, somente depois de um zero no
título completo, usa **o prefixo literal do primeiro segmento
separado por uma única barra**, exigindo 2–4 tokens Unicode
com 3–24 caracteres. Ex. `Social Path` gera
`releasegroup:"Social Path" AND arid:<MBID exato>`.
Nunca translitera `Superbowl` para `Super Bowl`, não usa
fuzzy matching, não consulta outro artista, e rejeita retornos
inválidos. O estado de saída é
`mb_title_prefix_lead_not_edition`, **não**
`Verified` ou ausência. O teto de consultas C por lote
é agora 2 por candidato e máximo 16; pode continuar
inconclusivo por limitação do índice. [RED #43120874468](https://github.com/jssantogit/BitChord/actions/runs/43120874468)
→ [GREEN #43121276768](https://github.com/jssantogit/BitChord/actions/runs/43121276768),
com a suíte pública completa aprovada. O fallback foi
comprovado com *fixtures*, não homologado como recuperação
em produção.

O [manifest de corpus](https://github.com/jssantogit/BitChord/blob/catalog/210-discogs-provider-evidence-20261107/docs/plans/catalog-211-discogs-corpus-manifest.json)
registra **C-04 Reviewed negativo**:
`match_state=candidate`, `absent_in_musicbrainz=false`,
`visible=false`, `app_type=Unknown`. Os dois últimos
valores são **não medidos em Android**, não falhas observadas.
[Corpus CI #43121153349](https://github.com/jssantogit/BitChord/actions/runs/43121153349)
**SUCCESS**; 30 slots, A 0/10, B 10/10, C 1/10,
**total 11/30 revisões independentes, zero C
recuperações realmente demonstradas e nenhuma métrica global
calculável**.

Sem APK, smoke físico, merge, prioridade Discogs-first,
alteração da #209/main, gravação de MBID inventado
ou mudança da autoridade de MusicBrainz.


## Proteção source-only nativa e primeiro controle editorial A (11/11/2026, Bahia)

### C-04 — corrigido também no fluxo real do BitChord, não só no diagnóstico

O primeiro fallback Python de título fornecia pistas de ReleaseGroup
apenas ao scanner de investigação. O app, por sua vez, ainda aplicava
`DiscogsShadowCatalog.knownTitlePredicate` ao índice público e às
edições hidratadas. Ele não reconhecia a diferença de separação
`"Superbowl"` versus `"Super Bowl"`, apesar de ambos os nomes
compartilharem o mesmo prefixo `"Social Path"` e o mesmo sufixo
`"Japanese Ver."`. Isso poderia produzir **falsa novidade na
estante source-only**, sem comprovar uma ausência MusicBrainz.

A regra nova é local, determinística e usada **somente para
suprimir duplicata de grupo conhecido**: título normalizado possui
uma única barra; a primeira parte deve ser rigorosamente igual
e conter 2–4 palavras latinas distintas (cada uma de 3–24
caracteres); a segunda deve ter de 2–8 tokens latinos (2–24
caracteres) cujas letras e números concatenados sejam
**integralmente idênticos**, no mesmo ordenamento, com 12–100
caracteres. Não supõe tradução, fuzzy matching, distância de
edição, nem identifica Recording, Track, áudio ou prensagem.
Remix, Live, Acoustic, Instrumental, Sped Up e Slowed são
expressamente **excluídos dessa equivalência**, assim como
outra versão (English/Japanese), prefixo genérico de uma palavra
e título com múltiplas barras. Só se aplica contra o conjunto
de grupos MusicBrainz já obtidos em uma consulta completa
para o **mesmo artista**.

- **Android:** testes RED
  [`d87b390a`](https://github.com/jssantogit/BitChord/commit/d87b390afd2923eec6ca7087dd5f674881f2df48),
  [CI RED #43123188253](https://github.com/jssantogit/BitChord/actions/runs/43123188253)
  — 171 testes, uma falha esperada no caso C-04. Implementação
  [`efa69b56`](https://github.com/jssantogit/BitChord/commit/efa69b56e8bd9bea072f98587b8b2014c09682a7).
  [CI Android/JVM #43123899508](https://github.com/jssantogit/BitChord/actions/runs/43123899508)
  **SUCCESS**, `BUILD SUCCESSFUL in 5m 54s`; a seleção
  `DiscogsShadowCatalog.select` não reintroduz C-04 no
  catálogo source-only sob título variante.
- **Scanner público:** regressão RED
  [`310982c5`](https://github.com/jssantogit/BitChord/commit/310982c5680f7824a7b72110c925ad78817aea8d)
  e [CI RED #43123559722](https://github.com/jssantogit/BitChord/actions/runs/43123559722)
  reproduziram a falsa novidade em
  `known_group_title`. A correção Python
  [`fa7451e1`](https://github.com/jssantogit/BitChord/commit/fa7451e11389dbc2348c98dd276196c151820efa)
  usa a mesma condição estrita; a
  [CI #43123668213](https://github.com/jssantogit/BitChord/actions/runs/43123668213)
  **SUCCESS**. A prevenção de reciclagem da
  edição C-04 já revisada também passou em
  [CI #43122645293](https://github.com/jssantogit/BitChord/actions/runs/43122645293).
  Essas regressões não equivalem a teste em dispositivo físico
  ou a uma recuperação C, ainda igual a zero.

### A-01 — FEARLESS já é EP; Discogs não corrigiu nada

O primeiro controle A independentemente revisto é
**LE SSERAFIM — FEARLESS**, ReleaseGroup MusicBrainz
[`fb426d76-525d-4b25-90d1-fa4d1b3140c9`](https://musicbrainz.org/release-group/fb426d76-525d-4b25-90d1-fa4d1b3140c9)
com tipo primário **EP**. A
[SOURCE MUSIC](https://sourcemusic.com/artist/discography/LE%20SSERAFIM/8)
o define explicitamente como **1st Mini Album**,
cinco faixas; o
[anúncio de pré-venda](https://weverse.io/lesserafim/notice/2469)
de 13/04/2022 registra 02/05/2022 e **duas
embalagens físicas** Vol.1 e Vol.2. A matriz real
[#43120411787](https://github.com/jssantogit/BitChord/actions/runs/43120411787)
confirma relação tipada entre o ReleaseGroup e Discogs
Master 2653076; Discogs Release 23314901 é uma
edição física vinculada diretamente a **outro exato
Release MB** que não o par B-07 negativo. Não
inferir que a edição source-only foi recuperada nem
que uma simples versão do Master altera o tipo do
grupo. A revisão A-01 é **concordância
editorial EP/EP**, nenhuma correção. O
`match_state=candidate` conserva inconclusiva a
identidade da prensagem, `app_type=Unknown` e
`visible=false` significam Android não medido,
não erro de UI. TDD RED
[CI #43124399182](https://github.com/jssantogit/BitChord/actions/runs/43124399182)
→ registro e baseline GREEN
[CI #43124562605](https://github.com/jssantogit/BitChord/actions/runs/43124562605)
**SUCCESS**.

**Corpus resultante:** 30 vagas, **12 revisões**
(A 1/10: tipo correto, B 10/10: sem novas
ligações `Verified`, C 1/10: não ausência),
18 ainda não revisadas, métricas globais ainda
indisponíveis. Zero correções efetivas, zero
recuperações C comprovadas, zero ganho UI medido.
PR #211 continua Draft/unmerged; nenhuma alteração
em #209/main, nenhuma troca da autoridade MusicBrainz,
APK ou smoke físico.

## Coorte A — inspeção de relações Release exatas em Masters grandes (11/11/2026, Bahia)

A investigação anterior identificara 52 MB Releases oficiais distribuídos em
quatro ReleaseGroups, embora seus Discogs Masters tivessem **mais de oito
versões** e, corretamente, não fossem comparáveis de forma exaustiva. O
workflow agora roda uma sonda separada, versionada e sem token:
`scripts/catalog_211_discogs_exact_release_link_probe.py`. Ela verifica
novamente Artist MBID → Discogs Artist ID, ReleaseGroup MBID → Discogs Master
ID, browse integral de até 25 MB Releases, status `Official` e identidade
individual de Release. Examina **somente três MB Releases por grupo**, com
prioridade operacional para mídia física, sem assumir que a seleção contém
a edição correta. Segue exclusivamente URL tipada
`MusicBrainz Release → Discogs Release`, se única; confere artista da
edição Discogs e seu `master_id` contra o Master tipado observado. Dados de
títulos, faixas, datas, país, barcode e gravadora/catálogo são diagnósticos
de revisão, **não promotores a Verified**.

Testes adversariais RED
[CI #43135044232](https://github.com/jssantogit/BitChord/actions/runs/43135044232)
(reproducibilidade de API nova ausente)
→ implementação e
[GREEN #43135307603](https://github.com/jssantogit/BitChord/actions/runs/43135307603)
confirmada: 29 GETs HTTP públicos em quatro ReleaseGroups e
12 edições amostradas.

| RG (tipo MusicBrainz) | MB exatas amostradas | relações diretas válidas para mesmo Master/Artist | sem URL direta |
|---|---:|---:|---:|
| IVE — After LIKE (Single), Master 2803793 | 3 | 3 | 0 |
| LE SSERAFIM — FEARLESS 2023 Japão (Single), Master 2986856 | 3 | 1 | 2 |
| NewJeans — How Sweet & Bubble Gum (Single), Master 3499101 | 3 | 1 | 2 |
| Stray Kids — ATE (EP), Master 3554257 | 3 | 0 | 3 |
| **Total** | **12** | **5** | **7** |

Cinco pares têm **URL de edição direta e Master/Artist tipados corroborados**:
IVE MB Releases `0b971001`→Discogs `25758679`,
`0e3b2edc`→`25758685`,
`2115801c`→`24725813`;
LE SSERAFIM `49f40a51`→`26168966`;
NewJeans `bec858c4`→`30848370`.
Todos receberam o estado diagnóstico
`direct_release_in_typed_master_not_adjudicated`.
Não os contar como novos `Verified` de B (são **vinculados**), nem como
recuperação C ou melhora da UI. Os sete casos sem URL direta não provaram
ausência de edição Discogs. Esta amostragem não substitui a leitura de
todas as versões de um Master nem prova correção de tipo.

### A-02 — FEARLESS de estreia japonesa já é Single; não confundir com o EP coreano

O [anúncio oficial de 25/11/2022 da Universal Music Japan](https://www.universal-music.co.jp/lesserafim/news/2022-11-25-2/)
e o [site oficial do artista](https://www.le-sserafim.jp/news/ujbjnf)
definem FEARLESS de **25/01/2023** como primeiro **Japanese Single**
com FEARLESS -Japanese ver.-, Blue Flame -Japanese ver.- e Choices.
As embalagens CD têm catálogos próprios (UPCH-89511, UPCH-89512,
UPCH-89513 etc.), não os volumes do EP de estreia de 2022. O ReleaseGroup
MusicBrainz `c54392ad-7d95-447b-8b1a-bc5668d2f00d` já está corretamente
tipado **Single**, e a sonda pública
[CI #43135307603](https://github.com/jssantogit/BitChord/actions/runs/43135307603)
achou o vínculo da MB Release oficial exata
`49f40a51-d69c-4ef9-9ecf-c069fc70c11f` para o Discogs Release
`26168966` no Master `2986856`, com artista confirmado por ID
de ambas as fontes. Essa evidência separa o single japonês do
**FEARLESS coreano EP** (A-01, ReleaseGroup `fb426d76`) sem inferir identidade
por mesmo título. O corpus A-02 foi revisado como **concordância
Single/Single**, não correção. Nenhuma equivalência de embalagem individual
foi homologada nem houve validação visual Android; `match_state=candidate`,
`app_type=Unknown` e `visible=false` registram essas limitações.

Reproduzido o corpus RED em
[CI #43135716003](https://github.com/jssantogit/BitChord/actions/runs/43135716003);
os testes auxiliares ainda esperavam o total antigo de 12 na primeira CI
após a alteração e foram atualizados. A
[CI final do corpus #43135988008](https://github.com/jssantogit/BitChord/actions/runs/43135988008)
encerrou em **COMPLETED/SUCCESS**.

**Corpus vigente: 13/30**, A 2/10 (ambos já corretamente classificados),
B 10/10 (zero novos Verified sem link), C 1/10 (não ausência); 17 slots
não revisados. **Zero correções reais de tipo, zero recuperações C
comprovadas e zero ganho de UI medido.** Sem APK/smoke,
merge ou mudanças na #209/main. Discogs continua complementar a
MusicBrainz no projeto.

### C-05 — ENHYPEN *ROMANCE : UNTOLD -daydream-* não está ausente do MusicBrainz

O relatório público do follow-up C
[CI #43124506344](https://github.com/jssantogit/BitChord/actions/runs/43124506344)
observou a edição Discogs `32437206` sob ENHYPEN, com ID de artista
Discogs válido. A reconciliação por barcode nessa execução estava
indisponível, não negativa: não havia evidência para chamar a
edição de ausente nem para certificar uma prensagem idêntica.

A [discografia oficial BELIFT LAB](https://beliftlab.com/artist/discography/ENHYPEN/12)
identifica o disco como **2nd Studio Repackage Album**, lançado em
11/11/2024, com *Daydream* e *No Doubt* acrescentados ao álbum anterior.
O [anúncio oficial](https://weverse.io/enhypen/notice/22943) comprova
a cronologia.

O [MusicBrainz ReleaseGroup `0c668bdb`](https://musicbrainz.org/release-group/0c668bdb-328c-4dba-8407-8eddb5373a5d)
continua chamado `ROMANCE : UNTOLD` e classificado **Album**, mas
**inclui explicitamente** o [MusicBrainz Release oficial
`4c42b226`](https://musicbrainz.org/release/4c42b226-24c8-4481-91ce-744ae59eaa5b)
com título `ROMANCE : UNTOLD -daydream-`, mídia CD,
11 faixas e lançamento em novembro de 2024. Assim, uma
comparação que use somente títulos de ReleaseGroups terá uma
pista nova falsa, embora a edição já esteja registrada no MB.

**C-05 foi marcado `reviewed=true` como negativo para ausência MB**:
`absent_in_musicbrainz=false`, `musicbrainz_type=Album`,
`reference_type=Album`, `match_state=candidate`.
O estado candidato não afirma que Discogs Release `32437206`
seja a MESMA prensagem física do MB Release `4c42b226`:
os atributos de embalagem/catálogo não foram homologados.
Os campos `app_type=Unknown`/`visible=false` continuam
não medidos no Android, sem alegação de falha visual.

A regressão em
`scripts/test_catalog_211_discogs_fresh_c_followup.py`
garante que um C-05 já revisado não reentre numa nova fila como
Discogs-only inédito. Essa proteção usa o ID de Release conhecido
somente no **scanner de investigação**; a estante Android atual
compara primariamente títulos dos ReleaseGroups, e o problema
genérico de `Release.title != ReleaseGroup.title` precisa de uma
solução separada, com prova de existência de edição, sem suprimir
legítimos álbuns/Singles de mesmo prefixo (como os dois FEARLESS).
Nenhuma alteração de lógica nativa foi feita nesta rodada.

TDD de corpus RED
[CI #43136595058](https://github.com/jssantogit/BitChord/actions/runs/43136595058)
→ [CI do corpus #43136777851](https://github.com/jssantogit/BitChord/actions/runs/43136777851)
**COMPLETED/SUCCESS**. A CI pública de follow-up foi acionada
separadamente para validar a não reciclagem de C-05 no conjunto
de regressões e sondagens.

**Novo corpus: 14/30** — A 2/10 (concordâncias), B 10/10
(sem novos unlinked `Verified`), C 2/10 (dois negativos de
ausência aparente). **Zero recuperação C comprovada, zero correção
de tipo, zero ganho UI medido.** Não inferir métricas globais
com 16 slots não revisados.

## C — busca por título de edição, além do nome do ReleaseGroup (11/11/2026, Bahia)

### Problema observado depois de C-05

O catálogo MusicBrainz registra ENHYPEN `ROMANCE : UNTOLD -daydream-` como
**Release Official**, enquanto seu ReleaseGroup tem o nome do álbum original.
Isso comprovou um falso candidato de ausência Discogs-only no scanner de
títulos de ReleaseGroups; a revisão C-05 foi aprovada como negativa.
Entretanto, uma consulta literal `release:"Romance: Untold -Daydream-" AND
arid:<ENHYPEN Artist MBID>` ao índice MusicBrainz de **Releases** também
não retornou o Release conhecido na primeira página, conforme
[CI #43138407037](https://github.com/jssantogit/BitChord/actions/runs/43138407037):
`no_c05_exact_release_in_first_page_not_absence`, um GET real e zero
correspondências. O Release `4c42b226` continua comprovado por sua
página específica no MusicBrainz; **zero resultados de busca não é
evidência de ausência física, nem de ausência no banco de dados.**
A sintaxe `release` é prevista no índice de Release e ReleaseGroup
do MusicBrainz, mas resultados de índice não constituem enumeração
canônica exaustiva de edições.

### Correção progressiva do diagnóstico, sem promover provas

O script `scripts/catalog_211_discogs_source_only_title_triage.py`
passou a distinguir buscas *ReleaseGroup* e *Release*. Após busca
de RG por título e, quando aplicável, por prefixo literal de barra,
ele pode consultar o índice de **MusicBrainz Release**, sempre com
`arid` do mesmo Artist MBID. A resposta precisa validar cada MBID de
Release e pai ReleaseGroup, artista creditado exato e título de
Release normalizado **integralmente idêntico** ao título Discogs;
um acerto nunca autoriza identidade da prensagem Discogs.

Após um índice de frase literal não encontrar a edição conhecida,
foi adicionado um **último fallback opcional** de *termo final distintivo*
do próprio título: pelo menos três tokens no título, último token
ASCII com 6–24 letras, sem descritores genéricos como `remix`,
`version`, `japanese`, `deluxe` ou `instrumental`. O termo só
seleciona a primeira página de até dez resultados sob o mesmo
artista, e não gera correspondência sem uma checagem posterior
de **título inteiro + Artist MBID + Release/ReleaseGroup MBIDs
tipados**. Resultados do mesmo artista com outros títulos são
ignorados, sem fusão de remixes/versões; crédito incorreto,
IDs repetidos e páginas inconsistentes são rejeitados. O
orçamento máximo do pipeline investigativo foi explicitado como
**quatro GETs MusicBrainz por candidato** (máximo oito candidatos;
a pesquisa por barcode tem orçamento separado), não busca
indefinida. Falhas HTTP e ausência de hit continuam inconclusivas;
nenhum marcador `absent_in_musicbrainz` vira `true` por causa
de uma pesquisa vazia.

A regressão de ReleaseGroup diferente já havia passado em
[CI #43138056041](https://github.com/jssantogit/BitChord/actions/runs/43138056041).
A insuficiência do literal foi reproduzida em
[RED #43138903490](https://github.com/jssantogit/BitChord/actions/runs/43138903490).
O workflow público executou os dois GETs (frase literal e
termo distintivo) no controle C-05 e preservou
`discogs-211-c05-exact-release-search.json`.
[CI pública #43139235151](https://github.com/jssantogit/BitChord/actions/runs/43139235151)
encerrou **COMPLETED/SUCCESS** no HEAD `0a651eed`.
A pesquisa da frase literal isolada continuou sem o Release;
o termo `release:daydream` restrito ao mesmo artista retornou,
após a comparação literal dos títulos e créditos, **cinco
MusicBrainz Release MBIDs**: `4c42b226-24c8-4481-91ce-744ae59eaa5b`,
`6315e724-cf49-40cb-9096-6c427396bfe6`,
`97d384e6-372e-4ac4-a32c-abcbdc0abb60`,
`da0f5bf1-959b-44ec-8504-69871f122b40` e
`f91ebc0a-d6e0-4ff4-bd21-2921ea2c4065`.
Esse resultado comprova a capacidade de **localizar registros
MusicBrainz previamente existentes por busca mais robusta**,
não recuperar um lançamento ausente, nem confirmar a exata
prensagem Discogs. O corpus C-05 permanece negativo e
`reviewed=false` no artefato da consulta isolada, que não pode
alterar sozinho a adjudicação do manifesto.

O follow-up C reproduz a mesma decisão sem reciclar os
IDs Discogs já revisados. **O novo scanner é pesquisa
investigativa; a estante Android ainda não usa esses resultados.**
Nenhuma mudança de UI foi feita e o corpus permanece
**14/30 (A2, B10, C2), zero recuperações C comprovadas**.

### Integridade do relatório C após o novo índice

O diagnóstico `source_only_title_triage` agora distingue
`mb_release_candidate_mbids` (IDs de **edições MusicBrainz
exatas**, com título completo e artista validados) de
`mb_group_candidate_mbids` (grupos que contêm essas edições).
Quando um resultado positivo vem da consulta por termo
distintivo, o registro traz também
`lead_index_scope=artist_scoped_distinctive_term`.

Foi encontrada uma segunda lacuna independente: o
`catalog_211_discogs_fresh_c_followup.py` copiava apenas os
grupos e descartava a lista de MBIDs de Release na saída
agregada. A regressão
[RED #43139571373](https://github.com/jssantogit/BitChord/actions/runs/43139571373)
reproduziu `KeyError: mb_release_candidate_mbids` depois de
uma busca positiva. A correção [`44d6dea7`](https://github.com/jssantogit/BitChord/commit/44d6dea760763bbb2d0426a99f03a6804e6f1ca9)
preserva os dois conjuntos de MBIDs e o escopo da busca
sem alterar `reviewed=false` ou
`confirmed_missing_from_musicbrainz=false`.
É correção de **rastreabilidade de evidência**, não
uma recuperação de Discogs-only ou modificação do app.

## Revisão em lote A/C — dez casos numa sessão, corpus 24/30 (12/11/2026, Bahia)

A avaliação editorial deixou de revisar apenas dois exemplos por sessão.
A prospecção A agora examina **até oito Discogs Masters de uma vez**
com escalonamento **round-robin por artista**, usando orçamento máximo de
40 GETs públicos, sem token. RED
[CI #43190463227](https://github.com/jssantogit/BitChord/actions/runs/43190463227)
→ [GREEN #43190713479](https://github.com/jssantogit/BitChord/actions/runs/43190713479):
34 GETs, oito grupos tipados, nenhum casamento de edição/falso Verified.
Foram identificados cinco Masters >8 versões (After LIKE,
FEARLESS Japão, NewJeans How Sweet, Stray Kids ATE, NMIXX BREAK) e
outros três leads (TXT ETERNITY com **duas versões Discogs**
examinadas integralmente, IVE LOVE DIVE, LE SSERAFIM ANTIFRAGILE).
A sondagem preserva ainda `Discogs Master.main_release` como **ID de
edição Discogs candidato**, sem assumi-lo como correspondente físico a
um MB Release. RED
[CI #43191582443](https://github.com/jssantogit/BitChord/actions/runs/43191582443)
→ [GREEN #43191673198](https://github.com/jssantogit/BitChord/actions/runs/43191673198).
NMIXX possui mais de 25 MB Releases, portanto o browse continua
**incompleto/inconclusivo**, e não zero edições.

### A: sete revisões editoriais em lote (A-03…A-09)

| Caso | Grupo MusicBrainz / Discogs Release | Tipo MB → fonte independente | Conclusão |
|---|---|---|---|
| **A-03: Stray Kids — Christmas EveL** | RG `62812a62`; Discogs `22076131` | **EP → JYP Holiday Special Single** | **DIVERGÊNCIA editorial real entre declarações de fontes; revisão necessária; zero correção automática** |
| A-04: IVE — After LIKE | RG `1c8c9fa5`; Discogs `24725813` | Single → Starship 3rd Single Album | Concordância |
| A-05: NewJeans — How Sweet & Bubble Gum | RG `9750354e`; Discogs `30848370` | Single → comunicado ADOR/lançamento oficial | Concordância |
| A-06: NMIXX — Fe3O4: BREAK | RG `48e4a94a`; Discogs Master principal `35165884` | EP → JYP 2nd EP | Concordância; MB browse >25 incompleto |
| A-07: IVE — LOVE DIVE | RG `58e44e7a`; Discogs Master principal `25179088` | Single → Starship 2nd Single Album | Concordância |
| A-08: TXT — The Dream Chapter: ETERNITY | RG `b389c57b`; Discogs Master principal `15387239` | EP → BIGHIT 2nd Mini Album / EP | Concordância; Master 2 versões |
| A-09: LE SSERAFIM — ANTIFRAGILE | RG `cfc67890`; Discogs Master principal `24854783` | EP → Universal 2nd Mini Album | Concordância |

**A-03 merece atenção.** [JYP oficial](https://straykids.jype.com/Default/NoticeList?PgIndex=94)
chama `Christmas EveL` de **Holiday Special Single**, datado de
29/11/2021, e o [MusicBrainz RG](https://musicbrainz.org/release-group/62812a62-f532-490a-9b52-1feccb3fdaba)
é **EP** com quatro faixas; MB Release
[`ba0e42d5`](https://musicbrainz.org/release/ba0e42d5-a720-4f86-b00f-502292f1a8e2)
tem relação direta para a edição Discogs `22076131`, JYP e
barcode `8809755509491`. O rótulo de marketing "Single" e os
critérios editoriais MusicBrainz podem diferir; **não presumir erro
canônico**, não inferir inversão de autoridade, e não produzir
`DISCOGS_CORRECTION` a partir de uma declaração única. Mantido
`match_state=candidate`, `app_type=Unknown`, `visible=false`,
`match_correct=false` por ausência de homologação de prensagem/UI,
mesmo com relação direta MB Release→Discogs Release.

As concordâncias estão ancoradas em fontes primárias:
[Starship (IVE)](https://www.starship-ent.com/musician/ive),
[ADOR/NewJeans](https://www.newjeans.jp/news/dwmgij),
[JYP NMIXX](https://nmixx.jype.com/Mobile/NoticeView?AnSeq=6193&NoticeNumber=361),
[BIGHIT TXT](https://ibighit.com/legacy/txt/the_dream_chapter-eternity-en.html),
[Universal LE SSERAFIM](https://www.universal-music.co.jp/lesserafim/news/2022-09-19/).
Nenhuma linha prova que a edição principal de um Master Discogs é
a mesma prensagem física que uma edição MB. Dois casos A (After LIKE,
How Sweet) possuem relações MB Release→Discogs Release exatas
independentemente sondadas na
[CI #43135307603](https://github.com/jssantogit/BitChord/actions/runs/43135307603);
mesmo assim sem `Verified` de prensagem. `A-10` continua livre.

### C: três revisões negativas em lote (C-06…C-08)

| Caso | Descoberta Discogs | Prova contra ausência MusicBrainz |
|---|---|---|
| **C-06: LE SSERAFIM — Jewelry** | Discogs Release `28089088` | MusicBrainz já tem Single RG `14e1a869`, conferido por índice artístico/título; [Universal anúncio](https://www.universal-music.co.jp/lesserafim/news/2023-07-25-2/) |
| **C-07: TXT — Setsuna Hanabi** | Discogs Release `38302455` | MusicBrainz já tem Single RG `cb79234e`, e edição digital Official `6770b265`; [Universal anúncio](https://www.universal-music.co.jp/txt/news/2026-07-23/) |
| **C-08: ENHYPEN — MEMORABILIA** | Discogs Release `31008160` | MusicBrainz já tem EP RG `4e219d4e` e edição `d0c0cfe6`; [BELIFT anúncio](https://weverse.io/enhypen/notice/19177) |

As edições Discogs não foram certificadas como a mesma prensagem
física das edições MB. C-08 tem `reference_type=Unknown`
porque o título comercial `SPECIAL ALBUM` não comprova sozinho
uma divergência EP→Album. Todos permanecem
`absent_in_musicbrainz=false`, `match_state=candidate`,
`visible=false` (Android não medido), não são recuperações.

### Gates, total e continuidade

Os três C e sete A novos foram registrados em **duas atualizações
em lote** no manifesto de 30 vagas, com testes RED antes dos dados.
A URL oficial JYP contém parâmetros de consulta e por isso é
preservada em `independent_label_announcement`; o campo obrigatório
`independent_editorial` usa uma URL independente, pública e estável
sem query, de acordo com a validação fail-closed existente do corpus.
O teste do corpus
[GREEN #43192985029](https://github.com/jssantogit/BitChord/actions/runs/43192985029)
**SUCCESS** após actualizar as expectativas de revisão e referências.

**Denominador vigente: 24/30** — **A 9/10**, **B 10/10**,
**C 5/10**, seis ainda pendentes (A-10 e cinco C).
A-03 é uma **divergência de nomenclatura editorial para reavaliar**,
mas **zero correções efetivamente aplicadas**. B tem zero novos
`Verified`; C tem zero Discogs-only comprovadamente ausentes
do MusicBrainz, portanto zero recuperações. Nenhum APK/smoke
ou ganho de UI Android medido. A PR #211 permanece Draft/unmerged,
MusicBrainz canônico e #209/main preservados. O relatório de
cobertura final não deve calcular precisão/ganho antes dos 30 casos
revisados e validados.

### A-10 — Stray Kids *ATE* é Mini Album / EP; coorte A completa

Depois do lote A-03…A-09, o último controle de classificação A
foi revisto: **Stray Kids — ATE** (19/07/2024).
A discografia oficial da JYP, em
[Falling Up](https://straykids.jype.com/discography/37ab301d)
e em [GIANT](https://straykids.jype.com/discography/6hldjvn5),
chama o ATE de **Mini Album**. MusicBrainz
ReleaseGroup `350ea536-dbf2-437d-913f-40040a3932ab`
já é **EP**, e a [CI pública A de oito Masters
#43191673198](https://github.com/jssantogit/BitChord/actions/runs/43191673198)
revalidou Master Discogs `3554257` com Release principal
`31284695`. O Master excede oito versões, portanto sua
edição principal NÃO deve ser tratada como a prensagem
MusicBrainz correspondente. Treze MB Releases Official
foram observadas como pistas, mas nenhuma foi homologada
como mesma edição física Discogs.

A-10 agora é `reviewed=true`, `reference_type=EP`,
`musicbrainz_type=EP`, `match_state=candidate`,
`app_type=Unknown`, `visible=false`, sem mudança real da
classificação.

**Denominador atualizado: 25/30, A 10/10 concluída**:
- 9 concordâncias de tipo com editoras/representantes;
- 1 divergência editorial fonte JYP Single vs MB EP
  (`Christmas EveL`, A-03), **sem aplicação automática**
  enquanto não resolvida nos critérios editoriais;
- B 10/10 segue com **0 unlinked `Verified`**;
- C 5/10 continua com **0 ausências MB comprovadas /
  0 Discogs-only recuperados**; C-01…03 e C-09…10 pendentes.
- Sem APK/smoke físico, alterações em #209/main, inversão de
  provedores nem ganhos de UI medidos.

## Encerramento do corpus editorial — 30/30 edições únicas após auditoria B/C (12/11/2026, Bahia)

### Correção de integridade: edições B indevidamente contadas novamente em C

Uma auditoria ao aumentar o lote C encontrou que os antigos C-07
(TXT *Setsuna Hanabi*, Discogs **38302455**) e C-08 (ENHYPEN
*MEMORABILIA*, Discogs **31008160**) reutilizavam **exatamente os
mesmos Discogs Release IDs** de B-02 e B-01. Essa duplicação havia
passado por uma checagem anterior que só exigia identidade única
**dentro de cada coorte**, inflando o 25/30 relatado anteriormente.
As revisões históricas dessas obras não foram apagadas: foram movidas
para `superseded_cross_cohort_reviews` com notas do par B correspondente,
**fora do novo denominador**. O validador
`catalog_211_discogs_corpus_report.py` agora rejeita edição Discogs
reutilizada entre B e C, inclusive em amostras sintéticas de regressão.

Três antigos candidatos C-01 (aespa *World Tour in Cinemas*,
Blu-ray), C-02 (Stray Kids *The DominATE Experience*, DVD) e
C-03 (ENHYPEN *Confessions*, créditos de três artistas) nunca
foram exemplos elegíveis de álbum/single/EP só do artista. Seus
IDs Discogs **35180680, 38613330, 33375572** e os fatos
observados ficam conservados em `screened_out_sources`, validados
mas não contados como trabalhos musicais ausentes. O parser
verifica identidade e URL de cada exclusão; duplicações com
qualquer B/C revisado causam erro.

### Descoberta adicional em lote — sete casos realmente distintos

Para preencher slots independentes, `catalog_211_discogs_c_second_page_probe.py`
verificou no máximo **uma página 2 por artista validado** (9 GETs
públicos, [CI #43195818037](https://github.com/jssantogit/BitChord/actions/runs/43195818037)),
sem token e sem pressupor ausência MusicBrainz. A hidratação
`catalog_211_discogs_c_audio_batch.py` realizou **7 GETs**
de IDs de *Discogs Release* tipados, validando crédito de **um
único Discogs Artist ID correto**, faixa(s) de áudio, formato
e ano; [CI #43197529440](https://github.com/jssantogit/BitChord/actions/runs/43197529440)
passou nesses controles públicos. Essa CI falhou mais tarde nos
testes de follow-up C por causa da duplicação detectada no
manifesto antigo; não declarar a execução inteira GREEN.
Após a substituição independente, o
[scanner público #43198079285](https://github.com/jssantogit/BitChord/actions/runs/43198079285)
foi **GREEN** sem promoções automáticas.

| Caso novo | Edição Discogs e tipo | Evidência de que a obra já existia no MusicBrainz | Fonte editorial adicional |
|---|---|---|---|
| C-01 aespa — *SYNK : PARALLEL LINE* | **31961039**, arquivo FLAC, 4 tracks, crédito solo aespa | RG `b51a484e` (Single), MB Release `4a1b8bc3` | SM via Korea JoongAng Daily e distribuição oficial |
| C-02 NMIXX — *Funky Glitter Christmas* | **31781147**, MP3, 1 track, JYP | RG `56384077` (Single), Release `2b4e2a89` | [JYP oficial](https://www.enmixx.com/en/discography/funky-glitter-christmas) |
| C-03 IVE — *CRUSH* | **31422908**, FLAC promocional, 1 track, Starship | RG `518945b0` (Single), distinto de *ALIVE* EP | [Sony Music Japan](https://www.sonymusic.co.jp/artist/IVE/info/566706) |
| C-07 IVE — *Kitsch* | **27107343**, FLAC promocional, 1 track, Starship | RG `829216ed` (Single), Release `a34cdc42` | [Comunicado Starship repercutido](https://www.soompi.com/article/1573203wpp/ive-confirms-april-comeback-date-in-teaser-for-1st-full-album-ive-ive) |
| C-08 NMIXX — *Roller Coaster* | **34417612**, AIFF, 1 track, JYP | RG `f95117e6` (Single), separado do EP *A Midsummer NMIXX's Dream* | [JYP oficial](https://www.enmixx.com/en/discography/a-midsummer-nmixx-dream) |
| C-09 LE SSERAFIM — *EASY (English ver.)* | **29892898**, AAC, 3 tracks, SOURCE MUSIC | RG `b64d0fbf` (Single), não o EP coreano *EASY* | [NME](https://www.nme.com/news/music/le-sserafim-english-version-easy-3591757) |
| C-10 IVE — *I WANT* | **27690417**, FLAC, 1 track, Starship/Pepsi | RG `72f51e2e` (Single), Pepsi 2023 | [Soompi](https://www.soompi.com/article/1600451wpp/watch-ive-drops-fun-and-refreshing-i-want-mv-for-2023-pepsi-campaign) |

As três revisões independentes C-04 Stray Kids *Social Path*,
C-05 ENHYPEN *daydream* e C-06 LE SSERAFIM *Jewelry*
continuam, sem alteração. O conjunto C tem **dez Discogs
Release IDs distintos, zero sobreposição com os dez B**.
Todos dez são obras já documentadas no MusicBrainz; nenhum
é um lançamento faltante recuperado pelo Discogs. Os pares
digitais com títulos iguais **não foram certificados como
a mesma edição física/digital** por barcode ou relação exata.
Nenhum foi promovido a `Verified`, criado com MBID sintético
ou medido no Android.

### Gate final, autoridade e implicações

[**CI de corpus #43198518126**](https://github.com/jssantogit/BitChord/actions/runs/43198518126)
terminou **COMPLETED/SUCCESS**, com as trinta revisões aceitas
pelos testes de identidade, fontes editoriais, datas e ausência
de reutilização de B em C. Contagens:
**A 10/10**, **B 10/10**, **C 10/10**, mais **3 exclusões
conservadas fora da amostra**. Os denominadores existem, mas as
medições realmente demonstradas são **0/10 novas edições sem
vínculo Verified**, **0/10 obras Discogs-only confirmadas
ausentes do MusicBrainz**, **0 recuperações visualmente confirmadas**,
**0 correções de tipo aplicadas**. A-03 Stray Kids *Christmas EveL*
continua **divergência semântica editorial** Single promocional
JYP versus EP MusicBrainz, não prova de alteração necessária segundo
regras editoriais MB. **Não relatar taxas percentuais de precisão
em amostras sem positivos homologados**.

### Proteção Android em implementação

A estante complementar de artista consultava apenas títulos
completos de MusicBrainz **ReleaseGroups**; por isso reedições
com título próprio dentro de RG antigo (por exemplo ENHYPEN
`ROMANCE : UNTOLD -daydream-`) podiam escapar da deduplicação.

A proteção proposta é ampliar o lookup apenas quando o
usuário abre o artista e a cache de sugestões não fornece
uma entrada válida: buscar também todos os títulos **Official
MusicBrainz Releases** sob Artist MBID, em no máximo três
páginas de 100, validando count/offset, MBID de cada Release,
sem repetição ou página parcial. A estante compara a **união
dos títulos de RG e Release**, preservando a autorização original
por Artist→Discogs Artist. Se o índice for maior que o limite ou
inconsistente, **não mostrar como novidade uma edição potencialmente
existente**. Isso é fail-closed: pode ocultar temporariamente
sugestões em discografias grandes, mas não fabrica descoberta.
A implementação não altera o catálogo MusicBrainz, tags, Room,
playback, listas ou seleção automática de prensagem. A validação
da compilação Android e teste físico permanece separada do
corpus Python.

### Fechamento do gate Android e correção do denominador A-03

A implementação de proteção source-only para títulos de **MusicBrainz
Official Releases**, com até 3 × 100 IDs e paginação/identidades
fail-closed, passou em
[**CI Android focada #43199315323**](https://github.com/jssantogit/BitChord/actions/runs/43199315323)
**COMPLETED/SUCCESS**, inclusive o teste de repackage de nome
diferente do ReleaseGroup e os controles que preservam remixes
ou versões ao vivo distintas. Esse gate comprova a compilação e
testes JVM focados; **não substitui APK/smoke em dispositivo**.

A divergência A-03 *Christmas EveL* foi formalmente distinguida
de uma correção confirmada. `editorial_type_dispute_only=true`
exige coorte A, fontes explícitas e tipos MB/fonte diferentes.
O relatório expõe `editorial_type_disputes=1`, mas
`type_correction.eligible=0` e `corrected=0`:
marketing JYP "Holiday Special Single" pode coexistir com as
regras de EP do MusicBrainz para uma obra de quatro faixas.
Um caso com tipos já iguais não pode usar a marca para ocultar
uma divergência. [**CI final de corpus
#43200460173**](https://github.com/jssantogit/BitChord/actions/runs/43200460173)
**SUCCESS**, 30/30 revisões distintas e nenhum resultado
falso positivo inventado.

A PR #211 permanece deliberadamente **Draft/unmerged** e
MusicBrainz é o provedor de identidade/obra canônico.
A coorte C não demonstrou recuperações realmente ausentes da
fonte canônica; o avanço de código que efetivamente fecha uma
lacuna identificada é a **redução dos falsos inéditos** no
shelf complementar de artista, ainda não validada visualmente
no Android físico. A decisão de merge exige testes de dispositivo
e avaliação do proprietário.

## Smoke A54 — HOT duplicado em LE SSERAFIM (12/11/2026)

O proprietário instalou o APK smoke da [execução #43208196723](https://github.com/jssantogit/BitChord/actions/runs/43208196723), marcou **todos os dez checks anteriores como aprovados** (instalação/navegação, ENHYPEN daydream, LE SSERAFIM EASY, IVE CRUSH/ALIVE, estantes, rodapé, configuração, navegação e modo offline), mas identificou **defeito novo** na comparação com a referência Apple Music: a estante do artista LE SSERAFIM exibe simultaneamente cards `HOT` marcados `Single` e `EP`, apesar de o Apple Music apresentar o EP principal e produtos ingleses distintos sem o single-título redundante.

### Causa identificada em fonte MusicBrainz, não classificação Discogs

A discografia MusicBrainz tem **dois ReleaseGroups tipados e distintos**:
- [HOT Single, MBID `44adb7df-66bd-4ebe-a43a-3ea45a93529d`](https://musicbrainz.org/release-group/44adb7df-66bd-4ebe-a43a-3ea45a93529d), com CD single/versões de integrantes e uma edição digital derivada; sua relação MusicBrainz explícita **`single/EP which was taken from`** aponta ao HOT EP.
- [HOT EP, MBID `50bcea80-692f-4e36-a7a5-825a3233cb13`](https://musicbrainz.org/release-group/50bcea80-692f-4e36-a7a5-825a3233cb13), minialbum principal de cinco faixas, com relação de saída para o single `HOT`.
- Apple Music também possui produtos **separados** [HOT English ver. - Single](https://music.apple.com/us/album/hot-english-ver-single/1807948718) e [HOT English ver. - EP](https://music.apple.com/us/album/hot-english-ver-ep/1802579829), com 2 e 5 faixas: **não devem ser fundidos por mero título igual**. HOT (KAZUHA ver.) também é versão diferenciada, não duplicata genérica.

### Correção restrita e regressão

A projeção read-only `DiscogsEditorialNativeShelves.kt` apresenta só o EP principal quando ambas as obras aparecem **simultaneamente no grid tipado do artista**, possuem os MBIDs exatos ligados em MusicBrainz, tipos `Single`/`EP`, título e artista exatos. Não mexe no catálogo MusicBrainz, ReleaseIds, busca, biblioteca, listas, mídia, avaliações ou edição física; se o EP ainda não chegou por paginação, o single **não é escondido**. Não afeta prateleiras independentes como `Para descobrir`, nem versões inglesas/solo/remixes. A regra é **source-linked ID**, não heurística de nome ou número de faixas.

Testes TDD em `DiscogsEditorialArtistShelvesTest.kt` cobrem os três estados (ambos visíveis, EP ausente, fonte/tipo incompatível), a preservação de inglês/remix e a separação de seleções independentes. Workflow focused Android passou a observar a mudança de projeção de estante. **A compilação/CI ainda precisa aprovação no novo HEAD**; nenhuma afirmação de APK/smoke novo antes da execução.
