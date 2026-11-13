# REA #212 — convergência dos menus Android (13/11/2026)

Contexto: seis capturas de smoke físico exibiram menus elevados corretos em partes da Biblioteca, porém menus pretos sobre fundos de capas coloridas, opções reduzidas em Adições recentes, ausência de blur nas Playlists, ⋯ em linhas de artista/álbum e o player ainda abrindo ações em uma folha inferior.

## Contrato visual adotado

- **Pressionamento longo em listas:** capturar a geometria do item e exibir seu preview elevado com ações compactas, desfoque da lista por trás, e retorno animado. A ação mantém identidade canônica; não fabrica links de áudio, IDs ou botões de play quando o item é apenas metadado.
- **⋯ em botões de toolbar:** popup contextual junto ao botão; manter os comandos e sua posição de origem em vez de transportar um menu genérico para o rodapé.
- **Cor:** superfícies pairando sobre artista/álbum recebem `ArtworkPalette.elevated`; a base neutra preto/branco só vale para páginas sem paleta de capa. O contraste do texto acompanha a paleta da página.
- **Blur:** respeitar `AppSettings.reduceDynamicBlur`; o conteúdo atrás deve suavizar sem duplicar imagem ou texto dentro da prévia. Arte borrada dentro de ações do player usa o componente `ArtworkBackdrop`.
- **Compatibilidade:** ações locais continuam disponíveis, mesmo quando não há playback de catálogo online; baixar não é mostrado para arquivos já locais.

## Implementação desta rodada

- `HeldContextMenu.kt`: opção `plateTint` e helper `heldContextPlateColor` compartilham fundo entre preview elevado e ações.
- `NativeCompactOverflowMenu.kt`: opção `plateTint` e `nativeCompactPlateColor`, mantendo o estilo neutro para contextos sem arte.
- `MainActivity.kt`: menus elevados de faixas, cartões e gravações MusicBrainz respeitam a paleta de detalhe. O player Apple Queue usa novo `PlayerAnchoredActionsMenu` com referência às coordenadas do botão ⋯, em vez de uma folha inferior.
- `NowPlayingScreen.kt`: a chamada do botão de overflow fornece seus limites em pixels para alinhamento do novo diálogo, inclusive na fila. O host aplica blur ao player com a configuração permitida.
- `NativeCanonicalArtistContent.kt`, `NativeCanonicalAlbumRows.kt`, `DetailScreen.kt`, `NativeArtistPublicTopSection.kt`: as linhas de faixas em páginas de artista e álbum usam long press em vez de botões ⋯. Gravações MusicBrainz verificadas reutilizam as ações de catálogo com a mesma prévia elevada.
- `CanonicalMetadataTrackMore.kt` e `CanonicalCatalogReleaseOverflowMenu.kt`: fallback compactado sem botão ⋯ nas faixas detalhadas; superfície colorida por `ArtworkPalette.elevated`.
- `CanonicalRecentHeldActions.kt`: novas ações de adicionar à playlist, editar avaliação e fixar/desafixar item canônico, além de duas opções de fila, sem trocar `RecordingId` por texto/nome.
- `UnifiedPlaylistLibraryShelf.kt`: blur da lista de Playlists durante o menu elevado, sem dupla aplicação da haze.
- `CanonicalRemoteLibraryScreen.kt` e `CanonicalSearchHeldMenus.kt`: menus elevados em detalhes de artista/álbum e em pesquisas recebem o tom dinâmico quando presente.

## Inventário estático

Varredura GitHub Actions **#43521041453**, nos componentes de telas Android e JVM shared UI: 49 ocorrências de entrypoints (`HeldContextMenu`, `NativeCompactOverflowMenu`, `Popup` e `ModalBottomSheet`), incluindo nomes auxiliares. Não foi encontrado uso direto da função `DropdownMenu(` nos diretórios abrangidos por essa varredura. Isso **não significa** que todos os menus estejam visualmente equivalentes: há bottom sheets intencionais em formulários e editores, além de entradas de ações sem preview e popups cujo acabamento depende da tela.

## Evidências e validação

- O primeiro gate (#43518521269) compilou a produção; um teste novo de múltiplos cliques foi instável. A regressão foi restringida ao clique direto no item sem exigir várias ativações de uma mesma abertura de menu.
- Gate #43520832466 encontrou import incorreto do `Modifier.testTag` no novo menu do player. Correção registrada no código de produção antes do gate final.
- O gate completo e a verificação física devem ser registrados após execução; não inferir aprovação pela compilação de uma PR anterior.

## Confirmação física ainda necessária

Verificar pressão longa em álbum de NewJeans com fundo colorido; menus das páginas de artista; playlists locais e canônicas; Adições recentes; menu do player sobre uma capa clara/escura e com fila aberta; teclado ativo, redução de blur, orientação paisagem e acesso via TalkBack. Verificar que ações de menu não abrem páginas atrás do player, nem duplicam diálogos. Não foi emitido APK nesta rodada.

## Limites do corte

O player usa diálogo ancorado com desfoque de capa e do conteúdo de fundo; isso demanda verificação no Android físico de posição e insets. A troca de *todos* os fluxos de ações de `ModalBottomSheet` fora desses caminhos fica sujeita à validação de cada contexto (edição, seleção múltipla, configurações), não a uma substituição textual global: alguns desses modais não são menus de ⋯ e devem continuar como fluxos dedicados.

## Continuação após o smoke — gate de regressões e lavagem cromática do player

- **Retomada real:** o HEAD avançou de `f344ec2` até `1477e85`. Nesse intervalo, a faixa MusicBrainz sem áudio ganhou gesto de pressionamento longo sem inventar `onClick` reproduzível (`LongPressOrigin.longPressMenuClickable` + `NativeDetailTrackRowFrame`), e o teste de ações de Adições recentes foi posto em uma `Column` como na composição real.
- **Causa das falhas anteriores:** o gate [#43521477184](https://github.com/jssantogit/BitChord/actions/runs/43521477184) falhara somente em duas regressões: o teste sem layout de linhas de ação clicava a área sobreposta de `Fixar` ao procurar `Adicionar à playlist`; a linha MusicBrainz sem `onClick` perdia o detector de pressionamento longo. O código de `1477e85` conserva a distinção entre metadados e reprodução.
- **Primeiro gate definitivo:** [#43527681866](https://github.com/jssantogit/BitChord/actions/runs/43527681866) **SUCCESS**, código exato `1477e85`, regressões focadas, toda a suíte Android e `sharedUi:jvmTest` aprovados.
- **Correção adicional no player:** `ArtworkBackdrop` escolhia os coeficientes do gradiente pelo `isSystemInDarkTheme()`, embora `ArtworkPalette` respeite o tema do próprio aplicativo. Isso produz wash claro/escuro incompatível quando tema BitChord e tema Android divergem. Em [`a6579d9`](https://github.com/jssantogit/BitChord/commit/a6579d91235a69509e5430461f6a65fa0f2ac67b), `artworkBackdropWashAlphas(palette.background)` tornou as intensidades dependentes da paleta final do aplicativo, sem mudar os valores de gradiente existentes. Novas regressões claro/escuro em [`3b8cf2d`](https://github.com/jssantogit/BitChord/commit/3b8cf2da3b9acb08432d29ad03cc6644af355e01).
- **TDD da correção:** [#43528585442](https://github.com/jssantogit/BitChord/actions/runs/43528585442) falhou *como esperado* ao compilar os testes antes da existência do contrato `artworkBackdropWashAlphas`. O gate verde [#43528971951](https://github.com/jssantogit/BitChord/actions/runs/43528971951) terminou **SUCCESS** com o código exato `3b8cf2d`, testes focados de menus e gradiente, suíte Android integral e JVM compartilhada.
- **Aceite visual restante:** testar em dispositivo os menus elevados das páginas de artista/álbum, Playlists, Adições recentes, os gradientes e blur do player com tema do app distinto do tema Android, redução de blur, TalkBack e teclado. Não declarar pixel-perfect nem substituição de todos os modais. **Nenhum APK novo, merge ou aprovação de smoke físico nesta continuação**; a PR segue draft.
