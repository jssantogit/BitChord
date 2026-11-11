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
