# Discogs #211 — corpus de avaliação da utilidade editorial

Data: 2026-11-08. Escopo: Android mobile, PR #211, sem APK/smoke físico até o conjunto passar pela CI.

## Pergunta que o corpus precisa responder

O enriquecimento Discogs torna a discografia do BitChord materialmente mais completa ou mais correta do que MusicBrainz isolado? A resposta exige casos reais, comparados por ReleaseGroup, não contagem de chamadas HTTP ou presença de um rodapé de metadados.

## Amostra reprodutível (30 grupos/lançamentos, a selecionar com MBIDs/Discogs IDs verificados)

- **A — Tipo editorial questionável (10):** MusicBrainz marca Album/EP/Single, mas gravadora/artista e/ou Discogs fornecem evidência contrária. Registrar a fonte da avaliação manual e se há duas edições Discogs de fato distintas. Não inferir tipo pela duração, número de faixas ou rótulo LP.
- **B — Release sem relação direta MB→Discogs (10):** MusicBrainz conhece o grupo e uma edição oficial com tracklist completa, mas essa edição não tem relação Release→Discogs. Comparar vínculo de Master, barcode e busca autenticada por título; classificar Verified, Ambiguous, Candidate, NoLink ou Unavailable, sem tratar falha de rede como ausência.
- **C — Discogs-only aparente (10):** um artista com MBID confirmado e discografia completa, pequena o suficiente para leitura paginada integral, contém no Discogs um lançamento sem correspondência MusicBrainz. Exigir identidade de artista, edição verificável, faixas, gravadora e classificação explícita. Uma primeira página MusicBrainz ou artista homônimo não prova ausência.

Os exemplos observados no A54 em 2026-11-07 (ILLIT BREAK EVEN e álbum BTS) são candidatos de observação, **não** casos previamente classificados como verdade editorial. Confirmar IDs e as fontes antes de contá-los.

## Campos por caso

`case_id` (ID público neutro), `cohort`, `musicbrainz_group_mbid` (opcional no grupo C), `musicbrainz_release_mbid` (opcional), `discogs_release_id` (se encontrado), `observed_mb_type`, `verified_discogs_type`, `adjudicated_type`, `adjudication_sources`, `match_state`, `classification_state`, `native_shelf_before`, `native_shelf_after`, `attribution_visible`, `can_play` (deve ser falso sem MediaAsset independente), `false_positive`, `checked_at_utc`.

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

Status: **protocolo definido; amostra e resultados reais pendentes**. Não publicar números até coleta.
