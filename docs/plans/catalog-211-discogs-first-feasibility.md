# BitChord / Discogs #211 — hipótese de prioridade editorial Discogs-first

**Estado:** exploração técnica, **não aprovada como migração**. Data: 11/11/2026.
**Branch/PR:** [#211](https://github.com/jssantogit/BitChord/pull/211), empilhada sobre #209.
**Escopo nesta etapa:** auditoria estática e baseline offline, **sem alterar** persistência, fontes em runtime, preferências, navegação, mídia, APK ou classificação.

## Pergunta

Vale tornar Discogs a fonte predominante da **discografia exibida** (lançamentos, edições, formatos, créditos), mantendo MusicBrainz como auxiliar? Não confundir prioridade de apresentação com identidade da gravação. O dono do app propôs explorar a hipótese; isso **não constitui autorização para inverter a arquitetura**.

A política candidata para um experimento futuro é **precedência por campo e entidade**:

| Necessidade | Hipótese para teste | Risco / prova necessária |
| --- | --- | --- |
| Listagem editorial de álbuns, singles e EPs | Discogs Master/Release como candidato de exibição, com comparação a MusicBrainz | Master não equivale 1:1 a ReleaseGroup; edições/remixes podem compartilhar Master |
| Selo, nº catálogo, prensagem, região, formato | Discogs Release com atribuição e expiração | Detalhes de duas edições não podem ser misturados |
| Identidade de Recording e Track, obras e relações | Permanecer com MusicBrainz no grafo canônico | Discogs tracklist não provê automaticamente MBIDs, ISRCs nem a mesma granularidade |
| Categoria Album/EP/Single | Evidência editorial cruzada e tipos explícitos, sem promoção automática | 0/6 controles A elegíveis para correção no corte atual |
| Reprodução, mídia local, downloads, ratings, playlists | Identidades locais/MediaAsset e links canônicos continuam | Nenhum item Discogs cria áudio, RecordingId ou avaliação sintética |
| Popularidade, histórico e personalização | ListenBrainz/dados locais, nunca deduzir do Discogs | Contagem de versões/colecionadores não equivale a número de plays |
| Capa | Prioridade às fontes autorizadas já existentes; Discogs somente com direitos separados | Imagens têm condições distintas dos metadados CC0 |

## O que o código atual demonstra

O utilitário read-only
\`scripts/catalog_211_discogs_primary_baseline.py\` verifica cinco contratos, sem inferir que sua ausência significa migração concluída:

1. \`DiscogsPublicArtistDiscovery.findForArtist\` recebe obrigatoriamente \`musicBrainzArtistMbid\`.
2. A mesma descoberta recebe \`completedMusicBrainzTitles\` para suprimir grupos conhecidos.
3. \`DiscogsCanonicalEditionCoordinator.observeRelease\` depende do MusicBrainz Release MBID e consulta o grafo MB primeiro.
4. \`MusicBrainzCatalogGraph.MusicBrainzCatalogTrack\` modela \`recordingMbid\`/Track MBID de maneira separada.
5. \`CanonicalProviderEnrichmentPolicy\` rejeita \`RELEASE_PRIMARY_TYPE\` fornecido por enriquecimento externo.

Isso confirma uma **dependência de bootstrap e identidade**, não defeito do Discogs. Trocar apenas a ordem de chamadas não oferece uma busca de artistas independente, nem substitui a identidade da faixa ou os vínculos persistidos.

## Medição real, não impressão de catálogo

O corpus de 30 casos da PR ainda tem **0/30 avaliações independentes homologadas**: cinco B pré-selecionados, três C excluídos e nenhum A selecionado. Os controles iniciais encontraram falsos candidatos C por grafia, collab e formatos de vídeo; não comprovaram recuperação de lançamento ausente ou correção Album/EP/Single. Uma amostra com predominância de artistas K-pop e lançamentos atuais não autoriza afirmar superioridade global de nenhum banco.

Para um piloto de precedência editorial, exigir **os mesmos artistas e datas** nos dois cenários, comparar precisão e completude por ReleaseGroup e edição, publicar ids e fontes independentes e reportar separadamente:

- edição já conhecida, mas com melhor apresentação Discogs;
- novo grupo realmente ausente do MusicBrainz;
- categoria explicitamente corrigida com evidência externa;
- duplicata, tradução, reedição, colaboração, bootleg/vídeo e falso positivo;
- latência, quantidade de requests, disponibilidade sem token, atribuição e expiração;
- paridade de busca/Home/Explorar/biblioteca, MediaStore/Navidrome, navegação, avaliação e playlist.

**Critério de segurança:** nenhuma identidade incorreta de artista/edição e nenhuma perda de referência local. Resultado inconclusivo fica inconclusivo; nenhuma sondagem vazia vira ausência. Cobertura 30/30 não é sozinha autorização para migrar.

## Dependências externas reais

- [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API): entidades separadas Artist, ReleaseGroup, Release, Track dentro de Release e Recording; ISRC e relacionamentos; API sem chave para leitura, respeitando rate limit.
- [Discogs Master guidelines](https://support.discogs.com/hc/en-us/articles/360005055493-Database-Guidelines-16-Master-Release): Masters são agrupamentos de edições, podendo ter remixes e versões diferentes.
- [Discogs API Terms](https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use): metadados de título/tracklist/gravadora são descritos como CC0; imagens, dados de usuário e marketplace possuem restrições diferentes. Há exigência de atribuição, aviso de não afiliação, regras de atualização (inclusive conteúdo não exibido com mais de seis horas de defasagem) e possibilidade de limites e mudanças de acesso. Uso pessoal **não elimina** os termos.
- A descoberta anônima atual não é equivalente a uma API Discogs-first global: depende de relação explícita Artist MBID→Discogs Artist; busca Discogs database/search é tratada pelo app como autenticada e opt-in.

## Plano proposto, caso a hipótese seja aprovada depois

**P0 — somente medição:** concluir corpus, estudar falhas de cobertura e preparar comparação offline Discogs/MusicBrainz. **Nenhuma alteração de prioridade de usuário.**

**P1 — protótipo limitado:** criar projeção *read-only*, independente de identidade MB no início da descoberta Discogs, com IDs namespaced. Reutilizar exatamente os compositores nativos e o cache com atribuição; não tocar Room ou os IDs persistidos.

**P2 — ligação segura ao grafo:** relacionar entidades quando houver prova, sem criar fake MBIDs, sem apagar duplicatas de usuário, sem inferir áudio e sem deslocar ListenBrainz.

**P3 — comparação e decisão:** gate real de UI/cobertura/precisão/custos e aceite explícito do dono do app. Somente então considerar preferência experimental de precedência editorial, reversível e desativável.

## Conclusão neste checkpoint

**Discogs-first como apresentação editorial: plausível, merece piloto.**
**Discogs-first como substituição integral da fonte canônica: não demonstrado e não autorizado.**

O relatório automatizado registra este estado sem converter a hipótese em decisão. O próximo avanço na PR #211 segue sendo completar evidências reais dos grupos A/B/C; não trocar provedores como atalho para uma avaliação inconclusiva.

## Atualização do estado de evidências (11/11/2026)

A revisão independente de B-02 (TXT / Setsuna Hanabi) confirmou o produto
CD com fonte primária da gravadora e que o Release MBID comparado é uma
**edição digital diferente**, portanto não há `Verified` de edição nem
recuperação de um grupo ausente. É uma revisão **negativa do par exato**,
não uma validação de UI. O corpus passa a 1/30 revisado, 4 B ainda
pré-selecionados, 3 exclusões C preliminares; métricas completas e ganhos
permanecem indisponíveis. Isso **não altera** a conclusão de não autorizar
Discogs-first sem comprovação de cobertura.

## Segundo controle B revisado (11/11/2026)

O par B-05, TXT / 7TH YEAR, foi comparado à [nota oficial BIGHIT
MUSIC](https://weverse.io/txt/notice/33998) e ao registro
MusicBrainz CD/EP: a categoria EP já estava correta. A edição
Discogs inspecionada compartilha número de catálogo, mas não o
barcode, e a lista de faixas possui diferenças textuais. É
**conflito de identidade de edição (barcode divergente)**, não ganho de
Discogs nem razão para mudar a autoridade do MusicBrainz.
Corpus 2/30 revisados (ambos pares B negativos), 3 outros B pré-selecionados,
3 C excluídos preliminarmente, 0 ganhos verificados, métricas
globais não publicáveis.

## Fechamento dos cinco pares B inicialmente pré-selecionados (11/11/2026)

Com apoio de notas oficiais BELIFT LAB e Sony Music Japan,
mais consultas públicas por ID da CI Discogs #211, foram revisados
B-01 MEMORABILIA como `conflict` de edição digital Japão versus
CD Coreia do Sul; B-03 Fashion e B-04 DARE ME como `candidate`
de singles digitais com título Discogs bilíngue e sem evidência de
mesma edição exata. B-02 permanece `candidate`, B-05
`conflict`; **nenhum dos cinco é Verified**, e todos os
ReleaseGroups já estão presentes no MusicBrainz.

Corpus 5/30 revisados, sendo apenas 5/10 da coorte B:
A/B/C incompletos e `metrics=null`. O resultado reforça a
prioridade de **recuperação real comprovada**, não a troca da
autoridade do MusicBrainz com base na maior quantidade de
edições exibidas pelo Discogs. Não alterar status Draft nem
autorizar Discogs-first automaticamente.
