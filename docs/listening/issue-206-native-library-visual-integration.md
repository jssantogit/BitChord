# Biblioteca — integração visual nativa e smoke acumulado (PR #206)

## Estado

- Implementação em **draft**, baseada na [PR #205](https://github.com/jssantogit/BitChord/pull/205); por sua vez #205 depende de #193.
- **Nenhum APK, smoke físico novo ou merge autorizado nesta etapa.** Aprovação de CI não equivale a paridade visual no Galaxy A54.
- Referências externas (Apple Music, Symfonium) indicam apenas interações/estrutura. A UI é composta com os elementos já presentes no BitChord, sem novas escalas/paletas/componentes genéricos.

## Inventário de reaproveitamento — conferir em cada revisão

| Funcionalidade | Elemento nativo reaproveitado | Regra de integridade |
| --- | --- | --- |
| ⋮ de músicas, álbuns, artistas | `RowMoreButton`, `BrowseActionsSheet`, `ActionRow`, `CanonicalUnifiedArtwork` | Playback por RecordingId+MediaAsset elegível, não por URL/Song fictício |
| Escolha de playlist | `SettingsGroupSurface`, `SettingsRow`, `RowDivider`, `PlaylistWizardSearchEntry`, `PlaylistWizardTextEntry`, `PlaylistWizardPrimaryAction`, capa `CoverPlaceholder`/Coil | Só playlists normais. Salvar ordem canônica, deduplicar RecordingId, bloquear fonte indisponível |
| Seleção múltipla | `CanonicalTrackRow`, `LibraryRow`, `NativeLibraryEntityCard` e indicadores `IconButton` nativos | Seleção tipada por entidade, preservada ao trocar filtros, sem deduplicar por nome |
| Bandeja contextual | `GroupShape`, `MaterialTheme`, `PlaylistWizardCompactAction` existentes | Recolhível, contador acessível e posicionamento respeitando padding do host/miniplayer |
| Avaliação de álbum/artista | `CanonicalRecordingFeedbackPanel`, `ActionRow` do cabeçalho, `Star` nativa | ArtistId/ReleaseId reais; notas próprias em `canonical_entity_rating`, separadas das gravações |
| Adições recentes → Ver todos | `UnifiedLibraryToolbarActions`, `FrostedUnifiedLibrarySourceMenu`/`SortMenu`, `SearchField`, `LibraryRow`, `gridRows` | Somente adições MediaStore comprovadas; filtros sobre cache, timestamps reais e rating RecordingId |

## Auditoria anti-paralelismo — revisão de reaproveitamento após #206

**Critério de bloqueio:** nenhuma superfície nova é aceita apenas por parecer com a do
BitChord. Toda UI abaixo deve demonstrar (a) qual componente canônico a implementa,
(b) qual rota/ID permanece o mesmo e (c) por que um componente aparentemente similar
não pode ser reutilizado sem introduzir contrato legado.

| Superfície anterior ou risco | Resultado da auditoria | Fonte da verdade |
| --- | --- | --- |
| Novo `Dialog` independente de playlist no menu da Biblioteca | **Eliminado.** `CanonicalLibraryPlaylistPicker` usa `PlaylistWizardSheetChrome`, extraído do próprio `WizardChoiceDialog`. O wizard de playlist reutiliza exatamente esse shell também. | `SmartPlaylistV2WizardUi.kt`, `CanonicalLibraryPlaylistPicker.kt` |
| `PlaylistPickerSheet` do YouTube Music | **Não acoplar diretamente:** opera com `UserPlaylist`, `Song.videoId`, privacidade e conta YouTube; isso inventaria um adaptador legado entre a biblioteca canônica e o YouTube. A parte visual reutilizável vem do wizard atual, não da estrutura de dados YouTube. | `ui/components/PlaylistPickerSheet.kt`, `CanonicalStaticPlaylistRepository` |
| Miniaturas de playlist repetidas com `AsyncImage` e placeholder próprio | **Eliminado.** A lista de Biblioteca e o seletor usam `CanonicalStaticPlaylistCoverArt`, que delega a `CanonicalPlaylistCoverArt` e lê até quatro `RecordingIds` realmente associados. | `UnifiedPlaylistLibraryShelf.kt`, `CanonicalPlaylistDetailScreen.kt` |
| Painel de seleção com nova superfície/medidas inconsistentes | **Consolidado.** Usa `SettingsGroupSurface`, `PlaylistWizardCompactAction`, tipografia e ícones existentes; a bandeja agora tem cabeçalho e linha de ações, para não extrapolar 320dp no Galaxy A54. Contagem vem de gravações canônicas expandidas, não número de álbuns/artistas. | `CanonicalLibraryBatchActionBar.kt`, `CanonicalLibraryActions.kt` |
| Ordenações independentes entre Músicas e Adições recentes | **Eliminado.** Recents chama `sortCanonicalUnifiedEntities` e utiliza `CanonicalEntitySortDateRow` para ordenar por datas originais do MediaStore, sem comparador novo. | `CanonicalLibraryStarSourceFilters.kt`, `CanonicalLocalRecentAdditions.kt` |
| Campo de busca sem glass/efeito padrão | **Eliminado.** Adições recentes → Ver todos reutiliza `DetailSearchField`, a mesma superfície da Biblioteca, com as mesmas dimensões e sem campo Material3 paralelo. | `DetailScreen.kt`, `CanonicalLocalRecentAdditions.kt` |
| ⋮ nativo e dropdown legado compostos ao mesmo tempo | **Eliminado.** `CanonicalCatalogRatingMenu` usa somente `RowMoreButton` e a folha de ações quando ela está disponível; o dropdown legado fica apenas no fluxo anterior sem folha. | `CanonicalRemoteLibraryScreen.kt` |
| Nova página ou coleção de catálogo segregada | **Não introduzida nesta PR.** `MainActivity` continua usando a mesma rota `unified_library` e `CanonicalRemoteLibraryScreen`. A PR injeta callbacks, menus e filtros na tela existente, não um novo destino ou catálogo. | `MainActivity.kt`, `CanonicalRemoteLibraryScreen.kt` |

**Ainda há interfaces por função**, como a bandeja contextual e o seletor de playlist,
mas elas não recriam abas ou listas de biblioteca independentes. O picker rápido de
"Nova playlist" usa os campos/ações de wizard existentes e o mesmo
`CanonicalStaticPlaylistRepository`, sem criar editor/catálogo separado. A criação
com todas as opções avançadas de fonte/capa segue no editor normal já existente:
não considerar o picker rápido um substituto daquele wizard.

Testes de regressão incluem `PlaylistNativeSheetReuseTest`,
`PlaylistLibraryVisualReuseTest`, `CanonicalLibraryActionsTest` e
`CanonicalRecentAdditionsFiltersTest`. Aceite final depende de CI no HEAD e
verificação visual física; testes de composição não provam paridade de efeitos.

## Testes físicos no Galaxy A54 — após autorização de APK

1. **Biblioteca → Músicas:** menu ⋮ oferece ações contextuais sem regressão de espaçamento/alinhamento. Avaliação abre o editor existente. Fila usa apenas faixa disponível.
2. **Álbuns e Artistas:** lista e grade usam o mesmo overflow, arte e tipografia. Abrir páginas continua nativo. Em AtHeart/aespa/ablume, cards de lançamentos e linhas compactas aceitam ações sem navegar ao fluxo legado.
3. **Playlist normal:** ao selecionar faixa, álbum ou artista, o seletor mostra playlists normais, pesquisa e Nova playlist. Artista expande todas as gravações elegíveis; álbum expande seus membros. Não inserir entrada simbólica nem perder ordem/versões corretas. Playlist inteligente não aparece como destino manual.
4. **Indisponibilidade:** desconectar Navidrome ou trocar filtro de fonte depois de selecionar. Seleção deve persistir até descarte explícito. Salvar com fonte incompatível deve exigir revisão, sem alegar sucesso de itens descartados.
5. **Seleção múltipla:** marcar/desmarcar música, álbum e artista; conferir indicador, contador por entidade, navegação, scroll, filtros e bandeja recolhível acima do player, safe-area/IME e alvos de toque.
6. **Avaliações:** atribuir notas 0,5 em 0,5 a artista e álbum; voltar e conferir persistência/ícone; alterar nota de artista não altera músicas. Artista apenas por nome de exibição, sem ArtistId canônico, não pode ser avaliado.
7. **Adições recentes:** preservar inicialização/caching já aprovados; Ver todos tem pesquisa, ordenação por títulos e timestamps MediaStore reais, filtro de estrelas locais e lista/grade. Não anunciar eventos recentes do Navidrome sem evidência desse provedor.
8. **Regressões anteriores:** 594 músicas/29h20m, Logs internos, adições recentes visíveis, artistas com fotografia/degradê e Álbuns/Singles/EPs separados.

## Pendências não implementadas nesta PR

- **Fixar itens gerais da Biblioteca:** o mecanismo atual `AppSettings.pinnedPlaylists` só suporta playlists; não apresentar uma ação Pin que não tenha destino/canonização realmente implementados.
- Paridade de **Adições recentes remotas:** a projeção atual é MediaStore local. Exibir Navidrome nessa seção exige evidência de data de inclusão no provedor e arte autenticada, não ordenar por data de sincronização.
- Validar a **semântica de seleção parcialmente disponível** entre fontes com QA físico; o salvamento já bloqueia seleção totalmente indisponível ou gravações elegíveis ausentes, mas não reivindicar exaustividade para toda edição parcialmente disponível sem teste específico.
- Paginador e revisão formal de playlists conforme `docs/design/playlists/02-adicionar-musicas/spec.md`, que é outra tela; não confundir esta bandeja contextual de Biblioteca com a tela inteira do wizard.

**Fonte de verdade da implementação:** commits/CI da PR #206. Este documento é checklist e rationale, não prova de aprovação física.

## 03/11/2026 — loading nativo e seleção por segurar + arrastar

**Solicitação:** toda tela em estado de carregamento deve preferir o indicador circular nativo a uma mensagem textual de espera; segurar e arrastar seleciona sucessivos itens somente na raiz das categorias Músicas/Álbuns/Artistas. A implementação e os testes ficam nesta mesma PR #206.

- `NativeLoadingSpinner` centraliza o `CircularProgressIndicator` já existente. Estados Loading textuais foram substituídos na Biblioteca canônica (incluindo filtros e catálogo legado), Adições recentes (shelf e Ver todos), playlists normais/inteligentes (wizard, editor e detalhes), Minha avaliação, descoberta canônica, Coleções adaptativas e Redescobertas. O diálogo de seleção de canal já apresentava spinner, portanto só eliminamos o texto redundante. Mensagens de **erro** e **vazio** permanecem textuais e distintas do carregamento.
- `NativeUnifiedLibraryBody` usa `LazyColumn` e `gridRows` nativos com reconhecimento de arrasto após segurar. Mapeia os `RecordingId`/`ReleaseId`/`ArtistId` já filtrados às chaves reais das linhas/grades, inclusive quando o dedo cruza várias linhas entre quadros. A seleção de múltiplos itens é **aditiva**, sem desmarcar outros acidentalmente, sem permitir o espaço vazio da grade, cabeçalho, busca ou detalhes como origem válida.
- O gesto só é instalado quando `canSelectCanonicalLibraryByLongPress` autoriza a categoria raiz. As seis callbacks de toque longo por card/linha deixam de concorrer com o detector do contêiner. Clique normal e o menu ⋮ continuam nos mesmos componentes. Seleção, criação de playlist, avaliações e reprodução mantêm IDs canônicos.
- Cobertura de regressão atualizada: `CanonicalLibraryActionsTest` (mapas das chaves reais, seleção de intervalos e itens vazios), `PlaylistLibraryVisualReuseTest` (spinner sem mensagem na Biblioteca e Adições recentes), `CanonicalMyRatingsVisibilityTest` e `LibraryRediscoveryShelfComposeTest` (espera versus vazio). Conferir o **HEAD exato** na CI Android antes de considerar aprovado.
- **Aceite físico ainda necessário:** no Galaxy A54, segurar uma música e arrastar por várias linhas; repetir com Álbuns e Artistas, em lista e grade; verificar soltura, retorno, filtros, ⋮, navegação, nenhum long press em detalhes; testar espera, vazio e erro em todas as superfícies alteradas. Verificar também o comportamento ao alcançar a borda da lista durante o arrasto (rolagem assistida não foi reivindicada).
- **Preparação de merge, sem merge prematuro:** a comparação GitHub no início desta revisão confirmou que `main` está ancestral à cabeça #206 (`ahead_by=1268`, `behind_by=0` no checkpoint). Não usar `main` antiga para sobrescrever UI, nem descartar os commits de #193/#205. Manter encadeamento `main` → #193 → #205 → #206 até os gates e integrar respeitando dependências. Todo trabalho desta solicitação está **somente na #206**; sem PR extra e sem alegar aceite físico antes do smoke.


## 2026-11-03 — Paridade visual dos detalhes de álbum (comparação Original × Biblioteca)

O screenshot de referência segue o `DetailScreen` nativo; o detalhe de álbum da Biblioteca canônica ainda herdava o corpo genérico, incluindo dois `ActionPill` lado a lado, thumbnails em todas as linhas e o perfil na barra superior.

**Correção na mesma PR #206, sem criar uma tela paralela:**
- `NativeReleaseHeaderLayout` continua sendo o cabeçalho compartilhado, incluindo capa/paleta/crédito. `NativeReleaseActionRow` foi extraído do `ReleaseHeader` original e agora serve ambas as rotas: shuffle circular, Play primário e ícone contextual de biblioteca (desabilitado quando o álbum canônico já pertence ao catálogo sem uma ação legítima para "adicionar à biblioteca").
- Faixas do detalhe são **numeradas**, sem miniaturas repetidas, usando o mesmo `NativeDetailTrackRowFrame`, `NativeDetailTrackNumber`, `NativeTrackRowText`, `NUMBERED_ROW_DIVIDER_INSET` e menu ⋮ existentes em `SongRow`. A listagem normal de Músicas permanece intacta, inclusive notas, miniaturas e seleção por arrasto.
- A ordem padrão do álbum é a ordem verificada do vínculo `ReleaseTrack.trackNumber` consultado por `providerScopedReleaseRecordingIds`, preservada entre MediaStore e Navidrome. Só muda se o usuário escolher uma ordenação no mesmo menu `FrostedSortMenu` de `DetailScreen`.
- O cabeçalho usa `ReleaseGroup.primaryType`/`firstReleaseDate` e data de edição MusicBrainz quando realmente presentes. Um sufixo visual `- EP` só é retirado quando o tipo canônico comprovado é EP; o título original e o ReleaseId no banco **não são alterados**. Não inventar ano ou tipo. A quantidade de faixas representa **somente ativos reproduzíveis na(s) fonte(s) selecionada(s)**: diferenças para a referência (ex.: 5 localmente vs 6 na fonte original) não autorizam criar uma sexta faixa fictícia.
- No detalhe de álbum, a toolbar principal mostra pesquisa, ordenação e ⋮, **não** as ações de fonte/grade/avaliação da raiz nem o perfil. ⋮ utiliza `BrowseActionsSheet` com ReleaseId canônico. O detalhe de artista e as abas raiz mantêm suas toolbars próprias.

**Validação física pendente:** comparar novamente com screenshot original verificando capa, título/crédito/tipo/ano, alinhamento do Play e círculos laterais, numeração/separadores/altura das seis (ou cinco disponíveis) linhas, ⋮ das faixas, busca/ordenação e miniplayer. Testar album vindo somente do Navidrome, somente do MediaStore e com fontes mistas; confirmar que tocar/sortear músicas segue a ordem exibida e que as notas não são perdidas. A reprodução e o Catálogo continuam canônicos, sem vídeos sintéticos.

**Regressão automatizada:** `CanonicalAlbumDetailParityTest` prova ordenação de `ReleaseTrack`, fallback, classificação/ano com evidência e preservação do título quando incerto; `UnifiedLibraryToolbarPolicyTest` cobre o escopo da barra de álbum. Não marcar aprovado antes de CI exata do HEAD e inspeção visual real.


## 2026-11-03 — Adicionar álbum à playlist diretamente dos detalhes

Decisão do owner: a pílula de ações no canto superior direito do detalhe de álbum recebe um ícone **PlaylistAdd** próprio, que abre **exatamente** o seletor nativo `CanonicalLibraryPlaylistPicker` das músicas. O ícone `+` na linha Shuffle/Play/`+` fica **reservado para o futuro pin na página inicial da Biblioteca**: sem implementar uma fixação fictícia e sem transformar o `+` em ação de playlist.

**Implementação:** `MainActivity` (toolbar `UnifiedLibraryRouteToolbar` + estado `UnifiedAlbumDetailUiState`) envia token de abertura ao `CanonicalRemoteLibraryScreen`. A rota Álbuns → Detalhes usa `canonicalAlbumDetailPlaylistSelection(ALBUMS, releaseId)`; a rota Artistas → Lançamento abre o mesmo seletor com `ReleaseEntity.id` real. As duas compartilham o mesmo ícone da pílula, sem menu ou modal paralelo. O escopo é *somente* uma página real de álbum; a raiz de Músicas/Álbuns/Artistas, os detalhes de artista e as telas de playlist não ganham esse ícone.

**Semântica:** é o álbum inteiro, não as faixas filtradas temporariamente pela pesquisa e não as músicas de um artista inteiro. A fonte filtrada é preservada. O picker revalida as relações `ReleaseTrack` e `RecordingId`, evita duplicatas, rejeita qualquer faixa indisponível no destino e só permite inserção manual em playlists normais. Criar uma nova playlist reutiliza `SmartPlaylistBuilderScreen` nativo com a seleção inicial. Uma playlist inteligente mantém somente seu editor de regras.

**Botão `+`:** `NativeReleaseActionRow(reservePinAction=true)` indica “Fixar álbum na Biblioteca (em breve)” e permanece não interativo. As rotas legadas com ação real de salvar na biblioteca não tiveram essa ação silenciosamente substituída.

**CI/diagnóstico:** `CanonicalLibraryActionsTest` confirma que o target de playlist é tipado como ÁLBUM e não vaza para outra categoria/identidade vazia; `NativeAlbumPinReservationTest` verifica o `+` desativado; `UnifiedLibraryToolbarPolicyTest` cobre o álbum aninhado em artista. `album_playlist_toolbar_v1` registra origem e escopo de fonte sem dados pessoais. Os menus de ordenação e a barra da Biblioteca foram extraídos parcialmente de `BitChordApp` para remediar `MethodTooLargeException` da CI anterior.

**Smoke manual:** abrir álbum da aba Álbuns e de Artistas → lançamento; tocar o PlaylistAdd na pílula (não `+`); escolher playlist normal, validar faixas novas e pré-existentes, duplicatas e fonte local/Navidrome; repetir via “Nova playlist”; verificar que o botão `+` não muda nada, que ⋮ e ordenação continuam funcionando e que a toolbar volta a seu estado correto ao fechar o álbum. Testar Android após CI verde do HEAD. Nenhum APK disparado por esta etapa.
