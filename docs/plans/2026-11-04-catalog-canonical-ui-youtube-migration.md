# Catálogo independente do YouTube e detalhes nativos compartilhados — #208

Status em 04/11/2026: **migração iniciada**, não concluída. Primeira PR [#209](https://github.com/jssantogit/BitChord/pull/209) em draft a partir da `main` integrada pela #205. Esta página diferencia decisões, fatos comprovados no código e próximas etapas. Android é o alvo imediato; não estender automaticamente a Desktop/TV/Web.

## Contratos aprovados

1. **Identidade musical não pertence ao provedor:** `ArtistId`, `ReleaseGroup`, `ReleaseId`, `RecordingId`, `ReleaseTrack` e `MediaAssetId` são conceitos separados. MusicBrainz fornece MBIDs e metadados, nunca uma URI reproduzível.
2. **Fonte de áudio não define a tela:** MediaStore, OpenSubsonic/Navidrome e futuros provedores autorizados fornecem disponibilidade; um resultado somente de catálogo pode ser navegável sem botão de Play.
3. **Uma composição visual por entidade:** abrir o mesmo artista/álbum pela Biblioteca, Pesquisa, Home, Playlist ou Player deve acionar o mesmo componente nativo, sem novos layouts por fonte. Seções variam somente por evidência/capacidade.
4. **IDs externos nas bordas:** `ExternalIdentity(provider, entityType, externalId, entityId)` já existe; `videoId`, `browseId` e MBID não podem ser promovidos a ID canônico por nomes ou semântica de strings. Homônimos, participações e edições distintas devem permanecer distintos.
5. **Sem migração destrutiva:** preservação de fila, ratings, playlists, pins, histórico, links externos e cache. Um mapeamento não comprovado é UNKNOWN, não uma entidade nova inventada.
6. **Comportamento musical honesto:** 'Mais tocadas' exige uma métrica real, indicando se o ranking é pessoal (Listening Event Store) ou externo. MusicBrainz não fornece popularidade. Na ausência, mostrar apenas seções comprováveis como 'Faixas' ou lançamentos verificados.

## Dependências atuais mapeadas na main #205

| Superfície | Principal caminho atual | Dependência/observação |
| --- | --- | --- |
| Home/Explorar | `MainActivity` → `HomeScreen` nativo com `canonicalHomeState`/`canonicalCuratedHomeShelves` ou feed legado; `MainViewModel.loadHome/loadExplore` decide dados | O layout do Home já é compartilhado, enquanto a escolha de feed era opt-in; #209 torna o catálogo canônico obrigatório, inclusive instalações existentes |
| Pesquisa Geral | `MainViewModel.SearchSource`, `SearchScreen`, `MusicBrainzCatalogSearch` | `SearchSource.YOUTUBE` era default/fallback; #209 converte para `CANONICAL`, preservando origem Local/Server |
| Artista da Biblioteca | `CanonicalRemoteLibraryScreen` → `CanonicalNativeArtistDetail` | Já possui hero, ações, faixas, discografia e ratings nativos; referência de composição |
| Artista online | `MainViewModel.openCanonicalNativeDetail` → `DetailPage` → `DetailScreen` | Mesmo quando MBID é válido, UI mantém `Song`, `browseId` e shelves de origem histórica |
| Álbuns da Biblioteca | `CanonicalRemoteLibraryScreen`; álbuns aninhados em `CanonicalNativeArtistDetail` | Compartilhamento parcial com `NativeReleaseHeaderLayout`, `NativeReleaseActionRow`, `CanonicalTrackRow`; falta única composição de detalhe |
| Álbuns online | `DetailScreen` / `CanonicalCatalogDetailPage` / `SearchScreen` | Duplicação de apresentação e ordenação, objetos de páginas e IDs de browse distintos |
| Vídeos, links, login e execução | `MusicLink`, `YtMusicRepository`, `SourceResolver`, fila e modelos legados | Não eliminar no corte de descoberta. Links compartilhados explicitamente YT continuam numa exceção de compatibilidade até migração |
| Catálogo persistido | `CanonicalCatalogEntities.kt`, `CanonicalLibraryBrowse.kt`, MusicBrainz index | `ExternalIdentity`, `MediaAsset` e relações canônicas já fornecem base para adaptação sem fonte dona da tela |

## Primeiro corte funcional — PR #209

- `shouldUseCanonicalDiscovery`: Home e Explorar usam catálogo canônico independentemente da preferência antiga e da presença do provedor YouTube.
- `defaultDiscoverySearchSource`, `searchSourceForOrigin`, `effectiveSearchSource`: Geral → `CANONICAL` mesmo que o chamador tente selecionar `YOUTUBE`; pesquisas Local/Server permanecem.
- Bloqueio de prefetch automático de biblioteca/conta YouTube e de probes/catálogo implícitos.
- Toggle de descoberta legado não é exposto nas Configurações; preferência persistida fica intacta, sem controlar mais a UI.
- Link explícito do YouTube permanece como compatibilidade temporária **se o provedor estiver habilitado**. Pesquisa textual compartilhada continua canônica; nenhuma URL/ID externo vira identidade canônica.
- Testes: `CanonicalCatalogCutoverTest`, `CanonicalDiscoveryModeTest`, `CanonicalSearchOriginsTest`, `DisabledYoutubeCanonicalSearchGateTest`, `SettingsNavigationTest`.
- **Não incluso:** remoção do motor YouTube, autenticação, playback, download, playlists ou dados existentes; novas integrações Last.fm/Apple Music/Deezer; unificação completa de detalhes.

## Interface-alvo para próximas etapas (contrato de desenho; não código implementado)

- **Entrada tipada:** `DetailTarget` representa `Artist`, `ReleaseGroup`, `Release` ou `Recording`, com ID canônico *ou* referência externa tipada ainda não resolvida. Não aceitar uma string genérica que misture `catalog:...`, IDs de playlist e `videoId`.
- **Resolução:** adapter devolve estado de identidade verificada, evidência por fonte, lista de `MediaAsset` reproduzíveis, loading/cache/erro e ações disponíveis. Falhas remotas não apagam disponibilidade local.
- **Apresentação única — referência visual obrigatória:** `DetailScreen.kt` é a fonte visual nativa para artistas e álbuns, mas **precisa perder seu acoplamento a `DetailPage`/`Song.videoId` no compositor**. Extrair dele os compositores compartilhados (`ArtistDetailContent`, `AlbumDetailContent`) com contratos de estado tipados, reutilizando `ArtistHeader`, `PageBackground`, `NativeReleaseHeaderLayout`, `ActionRow`, `CanonicalTrackRow`, `NativeLibraryEntityCard` e `SectionHeading`. Biblioteca, pesquisa online, detalhes aninhados e itens fixados passam adaptadores de dados para esses MESMOS compositores; não criar um novo visual paralelo.
- **Eliminação explícita de duplicação:** `CanonicalNativeArtistDetail.kt` é uma tela duplicada e deve ser **apagada depois da migração efetiva** de `CanonicalRemoteLibraryScreen` para o compositor nativo compartilhado. Qualquer consulta Room, vínculo de Recording→Release, avaliação, pin, artwork, filtros, playlist, navegação de álbum aninhado e back stack que atualmente viva nesse arquivo passa a adaptadores/controladores ou a componentes compartilhados. Nunca produzir `Song.videoId` fictício para encaixar dados canônicos na tela antiga. O mesmo critério vale para layouts duplicados de álbum, sem apagar recursos aprovados na #205.
- **Seções tipadas:** discografia, álbuns recentes, singles/EPs, faixas disponíveis, destaques/ranking com proveniência, metadados editoriais, notas e botões. Uma seção ausente não vira lista vazia com título enganoso.
- **Ações:** Play/Shuffle/Queue somente com `MediaAsset` resolvido; Pin/Playlist/Rating sobre IDs canônicos ou identidade externa validada; inscrições específicas de provedores nunca se confundem com favoritos ou avaliações do app.

## Ordem e gates de migração

1. **D0: discovery cutover (#209)** — prova de ausência de chamadas ocultas YT em Home/Explorar/Pesquisa, sem regredir biblioteca/servidor; Android CI e smoke.
2. **D1: rotas e identidade** — mapa de todos os chamadores de artista/álbum, conversão segura de referências externas, casos de homônimos e ambiguidade; compatibilidade de back stack/links.
3. **D2: compositor de artista único** — extrair da tela nativa `DetailScreen` (não da alternativa da Biblioteca), adaptar Biblioteca + MusicBrainz ao mesmo `ArtistDetailUiState` e às mesmas ações; migrar `CanonicalRemoteLibraryScreen` e **remover `CanonicalNativeArtistDetail.kt`** após equivalência funcional e visual verificada. Preservar todas as seções, classificação/relacionamentos verificados, busca, avaliações, pin, álbum aninhado e retorno; sem ranking fictício.
4. **D3: compositor de álbum único** — Biblioteca, artista aninhado, pesquisa e pins; assegurar `ReleaseGroup` vs edição, número de faixas, rodapé, ratings, pin e PlaylistAdd.
5. **D4: demais entradas** — Home/Explorar, player, recomendações, músicas, playlists, histórico e links. Reduzir rotas legadas à borda de compatibilidade.
6. **D5: retirada do backend** — inventário completo de código, contas, playback e dados, com plano não destrutivo e rollback; só remover depois que nenhum recurso depende dele e após verificação física.

### Matriz mínima de validação

- Mesmo `ArtistId`/MBID comprovadamente relacionado aberto de Biblioteca, MusicBrainz, Home e playlist apresenta o **mesmo componente**; sem duplicação visual.
- Artist homônimo e edição homônima não podem ser juntados por texto.
- Resultado MusicBrainz sem asset tem navegação e artwork quando comprovados, mas **não** exibe ação de reprodução falsa.
- MediaStore + Navidrome aparecem como fontes disponíveis, independentemente de internet pública; MusicBrainz inacessível mantém cache/offline.
- Reinício conserva pins, ratings, histórico, playlists e fila; links YT explícitos são tratados por caminho de compatibilidade, sem recuperar descoberta YT.
- Android CI no SHA final e smoke Galaxy A54 com screenshots comparando layout e gestos; diagnóstico sem URLs/segredos de provedores.


### Gate anti-duplicação — aceite obrigatório

- Não se considera unificado apenas por compartilhar `ArtistHeader`, `ActionRow` ou paleta: precisa existir **uma árvore de composição de detalhe de artista**, com seções condicionais por dados reais e sem renderer alternativo na Biblioteca.
- `CanonicalNativeArtistDetail` não é o novo padrão: o seu estado/casos funcionais são fonte para migração, e seu compositor será retirado. **Deletar o arquivo somente após** testes da Biblioteca local/Navidrome, MusicBrainz online/cache/offline e álbuns aninhados passarem sem regressão.
- Mesmo princípio para álbum e demais entidades, evitando um "CompositorUniversal" que vire uma tela genérica com casos especiais para cada provedor.

## Consolidação visual transversal — inventário de preservação (04/11/2026)

**Decisão do owner:** a limpeza não está restrita ao artista ou a álbuns. Depois do primeiro compositor, repetir o inventário/extração nas demais telas de entidades e entradas do app, sempre **adicionando ao compositor nativo recursos que ainda estejam nas implementações secundárias**, e nunca descartando esses recursos para conseguir remover arquivos.

| Área | Compositor/fonte atual | Comportamentos que o compositor consolidado deve absorver |
| --- | --- | --- |
| Artista | `DetailScreen` vs `CanonicalNativeArtistDetail` e prévias MusicBrainz de `SearchScreen` | Header e fotografia nativos; Faixas e, onde há ranking verificável, Mais tocadas; álbum recente, Álbuns, Singles/EPs, Lançamentos; busca, ordenação, filtro por origem, rating nativo (incluindo display-artist), pin, seleção/long-press, menus de faixa/álbum, adicionar álbum à playlist, nested album e back |
| Álbum/edição | `DetailScreen.ReleaseHeader`, `NativeUnifiedLibraryBody`, nested album de `CanonicalNativeArtistDetail`, prévias MusicBrainz | `ReleaseGroup` vs `Release` sem fusão, capa/hero/paleta, créditos, número/ordem das faixas, avaliação, qualidade, filtro/ordenação, pin, PlaylistAdd, menus, footer e ações somente para `MediaAsset` real |
| Faixa/Recording | `SongRow`, `CanonicalTrackRow`, `CanonicalPlaylistTrackRow`, busca e fila | Mesma linguagem de linha, fonte disponível/qualidade, reprodução/fila, avaliação, menu, long-press, seleção, metadados e contagem; não converter RecordingId em videoId |
| Playlists normais/inteligentes | `DetailScreen` legado, `CanonicalPlaylistDetailScreen`, editor/preview existentes | Layout nativo aprovado, capa, filtros inteligentes, regras, reordenação, seleção, menus, downloads, edição, pin, reprodução de ativos disponíveis; sem misturar regras próprias de playlists |
| Home, Explorar e Pesquisa | `HomeScreen` visual já compartilhado; `SearchScreen` origens Geral/Servidor/Local e telas de prévia | Um card por tipo de entidade com identificação tipada, estado de disponibilidade, filtros, menus, pin, navegação, sem reativar descoberta ou playback YouTube implicitamente |

### Primeira extração de código (na PR #209)

- `NativeArtistSharedSections.kt` é a **extração do carrossel horizontal existente**, não uma nova tela: `NativeArtistTracksCarousel` e `NativeArtistReleaseCarousel` agora são usados tanto por `DetailScreen` quanto por `CanonicalNativeArtistDetail`, com tipos de faixa/card distintos e callbacks próprios.
- Comportamentos preservados no caminho da Biblioteca: `CanonicalTrackRow` ainda recebe `onTrackPlay`, `onTrackEditRating`, `onTrackOptions`, seleção e `onToggleSelection`; cards de lançamento mantêm `onReleaseOptions`, seleção e abertura de álbum aninhado. O compositor genérico não conhece `Song.videoId`, `RecordingId`, providerKeys ou MBIDs.
- Sem alterações do contrato de avaliação, filtros de fonte, seleção, regras das playlists, downloads, fila ou playback. Sem remoção de `CanonicalNativeArtistDetail.kt` nesta etapa: ela ocorrerá após migração das outras seções/ações ao compositor nativo e validação completa.
- Testes `NativeArtistSharedSectionsTest` verificam 20 faixas/4 por coluna, ordem de origem e grupos incompletos; `NativeArtistSharedSectionsComposeTest` verifica passagem dos callbacks de avaliação/menu/seleção de faixa e menu de álbum.
- **Gate de refatoração:** o desaparecimento de um componente só conta como progresso quando seus comportamentos passam a existir e são testados na estrutura nativa compartilhada. Usar [`DESIGN.md`](../../DESIGN.md) e `tensions.md` como referências de visual, gestos e espaçamento.

### Contrato de contexto para as seções de artista — aprovado em 04/11/2026

A composição visual é única, porém **seções, ações e nomes exibidos dependem de capacidades reais por contexto**. Local (MediaStore) e servidor de biblioteca pessoal (Navidrome) representam a mesma categoria **Biblioteca**; pesquisa MusicBrainz/serviços públicos representam **catálogo online**. Não confundir provedores de metadados e provedores de arquivos reproduzíveis.

| Contexto / evidência | Prateleira de músicas | Outras seções |
| --- | --- | --- |
| Biblioteca local/Navidrome com gravações | **Faixas** | Álbum recente somente com data e tipo comprovados; Álbuns, Singles/EPs, Lançamentos apenas quando presentes |
| Biblioteca sem gravações elegíveis | Nenhuma | Somente dados reais ainda disponíveis |
| Online com ranking genuíno fornecido por provedor | **Mais tocadas** | Lançamentos e dados enriquecidos presentes, sem duplicar Faixas |
| Online sem ranking, mas com gravações identificadas | **Faixas** (sem atribuir popularidade) | Lançamentos/metadata reais; nenhuma estatística fictícia |
| Online sem gravações e sem ranking | Nenhuma | Só as outras seções comprovadas |

**Invariantes:** nunca renderizar **Faixas** e **Mais tocadas** simultaneamente; fonte local não gera ranking global a partir de quantidade de arquivos, duração ou IDs; MusicBrainz isoladamente **não** fornece popularidade. Não criar seções vazias, nem transformar álbuns em Singles por nome. Os elementos ativam/desativam conforme prova, não conforme uma cópia fixa da tela ou uma simples chave booleana de 'online'. Os controles (avaliações, ⋮, pin, playlist, filtros) somente aparecem quando identidade/capability/ação são válidas.

**Implementação inicial na PR #209:** `NativeArtistContext`, `NativeArtistSectionEvidence`, `NativeArtistSection` e `nativeArtistVisibleSections` em `NativeArtistSharedSections.kt`; aplicados ao `DetailScreen` e `CanonicalNativeArtistDetail`. A compatibilidade de ranking YouTube permanece **restrita** à rota de artista `UC...`, enquanto os resultados `catalog:...` não podem ganhar 'Mais tocadas' sem evidência de ranking futura. Testes protegem exclusividade, estados vazios, contexto Biblioteca mesmo quando um flag incorreto vaza e bloqueio de rankings falsos para MusicBrainz/servidor.

**Próxima evolução:** converter essa disponibilidade em um contrato de detalhe tipado com proveniência por seção (popularidade: fonte e escopo; data: prova; asset: disponibilidade); registrar ações independentes, inclusive offline. Contrato deve abranger também álbum, faixa e playlist, sem reaproveitar indiscriminadamente seções próprias de artistas.

### Segundo corte transversal — composição independente de modelos legados (04/11/2026)

O escopo não se encerra na tela de artista. O compositor deixa de receber DTOs do legado; **adaptadores de borda** continuam interpretando `DetailPage`, `Song` ou `ShelfItem` até a navegação se tornar tipada. Remover os adaptadores antes dessa substituição causaria regressões em reprodução, menus, ratings, filtros e playlists.

**Extrações realizadas:**
- `NativeArtistBackdrop.kt`: `PageBackground`, gradiente, scroll e Canvas nativos; `NativeArtistDetailFrame` recebe apenas URL de arte e `ArtworkPalette`, não `DetailPage`.
- `NativeReleaseComponents.kt`: `NativeReleaseHeaderLayout`, `NativeReleaseActionRow` e medidas nativas agora independentes do enorme `DetailScreen.kt`. O adaptador `ReleaseHeader` mantém créditos, arte/placeholder, Canvas e conta, mas a composição é a mesma de álbuns locais, Navidrome e playlists.
- `NativeDetailControls.kt`: botões `ActionPill` e `CircleIconButton`, mantendo haptics e Liquid Glass. `NativeDetailSections.kt`: títulos de prateleiras e botão Ver todos, sem `browseId`.
- `NativeCanonicalAlbumRows.kt`: faixas numeradas compartilhadas entre álbum raiz e álbum dentro de artista; `RecordingId` como chave, número original de álbum preservado após busca/ordenação, mesmo menu/rating e separador. Teste `CanonicalAlbumDetailParityTest` reforçado.
- `LegacyArtistDetailAdapter.kt`: heurística de ranking da antiga rota UC removida do compositor nativo. A visibilidade usa evidência/contexto, não `browseId` específico.
- `sharedUi/NativeSearchRow.kt`: **mesma linha nativa de Pesquisa** (52 dp, arte, título, subtítulo, duração, badge) sem `BrowseItem`, `Song` ou provider ID. Busca MusicBrainz e Servidor passam dados tipados diretamente à linha; `BrowseRow` antigo permanece como adaptador para fontes ainda legadas. Os callbacks preservam bloqueio de play de `Recording` sem asset verificado e os badges apenas de arquivo comprovado. Testes em `sharedUi/src/jvmTest/NativeSearchRowTest.kt`.
- CI `.github/workflows/check.yml` recebeu execução de `:sharedUi:jvmTest`, pois `:app:testDevDebugUnitTest` compila as composições, mas não executa os testes puros do módulo sharedUi.

**Auditoria estática indicativa — não equivale à remoção total:** `NativeArtistBackdrop`, `NativeDetailControls`, `NativeDetailSections`, `NativeReleaseComponents`, `NativeCanonicalAlbumRows` e `NativeSearchRow` não exigem import de `DetailPage`/YtMusicRepository nem `videoId` como identidade. Os adaptadores de `DetailScreen` e `SearchScreen`, assim como `MainActivity`/`MainViewModel`, ainda contêm compatibilidade por origem.

**Pendências explícitas e ordem de migração:** (1) consolidar toda a árvore de seções de artista e filtro/ações da Biblioteca, eliminar `CanonicalNativeArtistDetail` após paridade; (2) separar a camada de detalhe de álbum da navegação raiz da Biblioteca, preservando qualidade/pin/playlist/sort/números; (3) finalizar rows de faixa/playlists e contextualizar menus; (4) adaptar `HomeScreen`, `ExploreScreen` e rotas da Pesquisa para modelos de entidade tipados sem `ShelfItem`/`BrowseItem` na composição; (5) fazer `MainActivity` e `MainViewModel` usarem apenas roteamento de origem tipado, retirando legacy YT depois que links explicitamente YT tiverem substituto seguro; (6) suíte Android + sharedUi, smoke A54 e comparação com main #205 antes de merge.

**Gate:** [CI anterior #41827977284](https://github.com/jssantogit/BitChord/actions/runs/41827977284) compilou o app, mas encontrou 3 testes Compose incorretamente dependentes de `PlayerPlatform`. Os testes agora usam o `themeArtworkPalette()` existente. Novo HEAD exige CI verde, sem antecipar status; workflow de emulador A0 pode ser `skipped` e **não substitui smoke físico**.

### Home e Explorar — início da mesma extração (04/11/2026)

- `sharedUi/NativeShelfCard.kt` contém a apresentação do card Home/Explore já aprovada: mesmo artwork slot, título, subtítulo, ícone de fixado, playing-accent, dimensões e gesto de clique/menu/long press. Não recebe `ShelfItem`, `browseId`, `Song.videoId` ou identidade de provedor.
- `HomeScreen.ShelfCard` **continua adaptador**: resolve `local:downloads`, `local:all`, `local:cache`, WebDAV, SMB e capas comuns usando a mesma implementação de arte anterior. `ExploreScreen` herda o componente de `ShelfCard` sem criar outra grade.
- A remoção completa de `ShelfItem` do estado de Home/Explorar só é segura quando `HomeShelf`, clique/menu/long press, replay/playing indicator e navegação estiverem tipados de ponta a ponta. A extração visual não significa migração completa dessas rotas.
- **Paridade obrigatória:** preservar capas gradientes, blur, mini player, capa local/remote, indicadores de execução, pinos, títulos e comportamento de acesso offline. Nenhum `browseId` canônico pode ser interpretado como ID de vídeo.
- A CI atual deve validar produção e `:sharedUi:jvmTest`; smoke físico somente depois do corte coerente completo.

### Exclusões verificáveis de legado — passo seguinte (04/11/2026)

**Regra operacional:** apagar funções, classes e IDs legados somente depois de todos os consumidores migrarem e de haver testes equivalentes; **não** eliminar IDs persistidos, links explícitos ou um renderer ainda acessível para conseguir reduzir a contagem de arquivos.

- **Deletados** `canonicalRowAsBrowseItem` e `canonicalServerRowAsBrowseItem` de `sharedUi/SearchScreen.kt`: criavam `BrowseItem` e navegação sintética `catalog:...`/`server:asset:...` sem função depois que a Pesquisa MusicBrainz e Servidor começou a renderizar diretamente `NativeSearchRowUi`. Seus testes antigos foram substituídos por contratos de roteamento canônico tipado/`MediaAssetId` e testes `NativeSearchRowTest` (incluindo a proibição de `browseId`, `videoId`, providerKey e URI privada no modelo visual). A **compatibilidade legítima** de `catalog:...` em `canonicalNativeBrowseId` e em links previamente salvos continua preservada, pois ainda tem consumidores ativos.
- Sem crédito, uma Recording pública passa a usar o tipo localizado `Gravação`, e não o antigo fallback `BrowseType.ALBUM`. Nenhum áudio ou identidade inventado.
- `NativeArtistActions.kt` recebeu `ActionRow` e `PlayCircle` extraídos de `DetailScreen.kt`; não recebe mais `SubscriptionState` do legado. Adaptadores enviam apenas `isSubscribed` quando existe follow real; caso contrário rating nativo ou estado desabilitado. `nativeArtistStarKind` e novos testes impedem exibir follow e avaliação simultaneamente na mesma estrela.
- `NativeDetailSections.kt` passou a conter `AboutSection` (textos expansíveis e slots de estatísticas/rodapé), e a função duplicada foi **deletada** de `DetailScreen.kt`. Artistas e playlists continuam usando a composição original, sem alteração de gestos ou aparência.
- **Próxima deleção estrutural:** somente depois de extrair todo o conteúdo condicional, filtros e menus do detalhe do artista da Biblioteca para uma composição única poderá ser removido `CanonicalNativeArtistDetail.kt`; idem no fluxo raiz de álbuns/Playlists. Eliminar o componente antes disso seria regressão, não avanço.

### Redução de renderers legados — identidade de artista e álbum aninhado (04/11/2026)

- **`NativeArtistIdentityHeader.kt`** recebeu, sem redesenho, o header visual original de artista (logos Apple Music, assinaturas, texto de fallback, transformação de tinta, retries de imagem), `SignedByCaption` e `AboutSignature`. As cópias do antigo `DetailScreen.kt` foram **deletadas**, com constantes visualmente equivalentes transferidas e teste Compose cobrindo nome de fallback sem imagem.
- **`NativeArtistActions.kt`** contém a antiga linha de Play/Shuffle/Estrela e `PlayCircle` com a mesma geometria/haptics. O compositor agora recebe capacidades independentes `isSubscribed` e `onRateArtist`; o adaptador transforma `SubscriptionState` antes da fronteira nativa. Testes unitários cobrem exclusividade da estrela Follow/Rating/Unavailable.
- **`NativeDetailSections.kt`** é dona de `AboutSection` e `SectionHeading`, incluindo expansão e slots, sem `DetailPage` ou `Song`; funções originais foram **removidas** de `DetailScreen.kt`.
- **`NativeCanonicalNestedAlbumDetail.kt`** agora contém a composição antes embutida em `CanonicalNativeArtistDetail.kt`: Release header nativo, rating/qualidade, pin, busca, lista numerada de gravações disponíveis e rodapé. `CanonicalArtistDetailState.kt` passou a fornecer rating reativo independente de renderer; `NativeCanonicalAlbumRows.kt` calcula a busca sem modificar a ordem/números originais. O componente artist apenas passa ReleaseId validado, `RecordingIds`, arte/evidência e callbacks, preservando seleção, menus, pin, playlist, volta, ordenação e ações. Aproximadamente 85 linhas da UI aninhada foram retiradas do renderer do artista.
- **Remoção definitiva de `CanonicalNativeArtistDetail.kt` ainda bloqueada** até a migração das seções exclusivas de artista para uma árvore de composição nativa única com teste de contexto local/servidor/online e smoke físico. Não remover rotas `catalog:...` que ainda indexam `DetailPage` ou URLs/caches legítimos.
- **Gate de CI:** teste/compilação Android e `:sharedUi:jvmTest` para o último SHA; diferença de UI/gestos e reentrada em álbum aninhado precisam do smoke A54. Nenhum merge antecipado.

## Evidências

- [#205 — Biblioteca/Playlists integrada](https://github.com/jssantogit/BitChord/pull/205)
- [#208 — contrato de migração e unificação](https://github.com/jssantogit/BitChord/issues/208)
- [#178 — auditoria inicial de substituição YouTube](https://github.com/jssantogit/BitChord/issues/178)
- [DESIGN.md](../../DESIGN.md) e [CanonicalNativeArtistDetail.kt](../../app/src/main/java/com/music/bitchord/ui/screens/CanonicalNativeArtistDetail.kt)
