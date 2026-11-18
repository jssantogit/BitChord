# REA #212 — inventário de pressionamento longo e destaque contextual (Android)

Checkpoint de origem: `66727ea4`. Esta rodada altera somente a branch experimental da PR #212. Não implica smoke físico, CI aprovada, merge, nem incorporação das PRs de providers.

## Contrato de UI

- As entidades musicais com ações secundárias utilizam pressionamento longo e **o menu elevado compartilhado**, não um novo menu específico do catálogo.
- O cartão levantado mantém o mesmo destaque de contorno branco das listas da Biblioteca; a placa de ações permanece neutra e opaca, inclusive sobre fundos dinâmicos.
- Toque simples continua navegando/abrindo o item. O gesto deve preservar o ID e a proveniência reais (metadata-only não vira faixa reproduzível).
- Três pontos da toolbar continuam menus ancorados. No Now Playing o vetor original MorePlain é compartilhado com o botão destacado no diálogo Android; a borda branca acompanha a âncora.
- Toda ação deve respeitar origem do item, seleção ativa, permissões de reprodução, blur reduzido, animação reduzida e restauração da prévia.

## Matriz de código — pontos confirmados

| Superfície | Origem | Estado nesta rodada | Responsável |
| --- | --- | --- | --- |
| Biblioteca: músicas, álbuns e artistas, lista/grade | Local/servidor | Já utilizava hold; unificado contorno da prévia | `CanonicalRemoteLibraryScreen.kt`, `HeldContextMenu.kt` |
| Artista: faixas e catálogo top | Recording MBID | Já utilizava hold | `NativeArtistPublicTopSection.kt`, `NativeCanonicalArtistContent.kt` |
| Artista: lançamentos de discografia | ReleaseEntity | Já utilizava hold | `NativeCanonicalArtistContent.kt` |
| Artista: Artistas semelhantes | MBID artista | **Novo**: card envia hold ao menu elevado canônico | `NativeArtistSimilarShelf.kt` |
| Artista: Incluído em | ReleaseGroup MBID | **Novo**: menu do lançamento comprovado, não um Song fabricado | `NativeArtistIncludedInShelf.kt` |
| Artista: Playlists públicas | Playlist MBID ListenBrainz | **Novo**: menu elevado de playlist pública | `NativeArtistPublicPlaylistsShelf.kt` |
| Artista: lançamento em destaque | ReleaseGroup MBID | **Novo**: envio ao menu elevado canônico | `NativeArtistPublicFeaturedRelease.kt` |
| Pesquisa: entidades do catálogo e playlists públicas | MBID | Já existia `CanonicalSearchHeldMenus`; reutilizado para os novos pontos | `CanonicalSearchHeldMenus.kt`, `MainActivity.kt` |
| Álbum: linhas de faixas | gravação/local | Já utilizava hold | `NativeCanonicalAlbumRows.kt` |
| Playlist normal: linhas de faixas | Track | Já possuía hold; verificar indisponíveis/metadata-only | `CanonicalPlaylistDetailScreen.kt` |
| Playlist pública: faixas | Recording MBID | Já possuía hold, mas há menus internos distintos a conciliar | `CanonicalPublicPlaylistDetailScreen.kt` |
| Criador de playlists: filtros salvos | filtro | Já possuía hold | `SmartPlaylistV2BuilderScreen.kt` |
| Home: cards de descoberta | catálogo | Já existe caminho contextual; requer verificação por subseção | `MainActivity.kt`, feed canônico |
| Explorar: gêneros, grades e resultados | categoria/entidade | **Pendente auditoria por componente** | rotas Explorar / componentes de cards |
| Now Playing: ⋯ | música em reprodução | **Atualizado** vetor compartilhado e contorno ativo | `PlayerAnchoredActionsMenu.kt` |
| Álbum MusicBrainz: ⋯ na toolbar | ReleaseGroup MBID | Menu compacto ainda separado; precisa convergência de aparência e ações | `CanonicalCatalogReleaseOverflowMenu.kt` |
| Seleção múltipla | múltiplas entidades | Mantém dock de seleção próprio; não usar hold do item para substituir seleção | `CanonicalLibraryBatchActionBar.kt` |

## Fora do escopo de gesto

Filtros, ordenação, avaliação, editor, campo de busca e configurações não precisam ganhar pressão longa só porque usam cards. Compartilham materiais e padrões de destaque, mas conservam semântica, acessibilidade e seus próprios estados.

## Critério de aceite ainda pendente

1. Rodar compilação Android e testes focados no HEAD **posterior** às mudanças desta rodada, além de `sharedUi:jvmTest`.
2. Testar fisicamente o pressionamento longo nas quatro seções novas, fechamento com animação reversa, imagem elevada e cartão original, origem e ações, além de TalkBack.
3. Confirmar player com menu aberto em Now Playing normal e fila (capa clara/escura) e contorno no mesmo lugar do botão original.
4. Inspecionar a cobertura ainda não classificada de Home/Explorar e os menus compactos legados, sem substituir indiscriminadamente folhas editoriais.
5. Não mesclar a PR #212 antes do aceite físico.


## Continuação — settings globais e Home/Explorar (15/11/2026)

A auditoria do lançamento global encontrou dois pontos ligados ao mesmo popup `AppleSettingsPopup`: `AppleRootSettingsOverflow` (raízes Explorar/Biblioteca) e `TopBarAccountButton` (Home e telas empilhadas). Ambos preservam o vetor `PlaylistEditorIcons.More`, o tamanho de 40dp e as ações existentes; agora recebem borda branca de 1,5dp **apenas enquanto expandidos**, reutilizando `heldContextSelectionOutline()`.

Evidência funcional de rota: `MainActivity.kt` entrega `onItemLongPress = onShelfLongPress` tanto a `HomeScreen` quanto à `HomeScreen` usada nas páginas de gênero em Explorar. A raiz `ExploreScreen` exibe navegação por categoria — não se deve criar menu de entidade musical para categoria sem ações específicas documentadas.

Os menus de detalhes de biblioteca com `optionsToken`/`artistOptionsToken` merecem uma verificação própria: o token é um evento de abertura consumido no componente de detalhe, **não** um booleano persistente de popup. Não inventar `expanded = token > 0` para o contorno, pois ficaria preso após o fechamento. Acompanhamento deve ser feito no dono do menu.

Testes adicionados em `GlobalAppleTopBarChromeTest` cobrem o token de contorno ativo/inativo. Como não há Gradle funcional nem workflow dispatch disponível nesta sessão, são testes ainda não executados neste HEAD; a CI e o smoke físico continuam bloqueando a conclusão e o merge.
