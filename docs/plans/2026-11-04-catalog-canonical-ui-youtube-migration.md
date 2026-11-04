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
| Home/Explorar | `MainActivity` → `MainViewModel.loadHome/loadExplore` → `CanonicalDiscoveryScreen` ou feed legado | Gate `shouldUseCanonicalDiscovery` era opt-in; #209 torna canônico obrigatório, inclusive instalações existentes |
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
- **Apresentação única:** `ArtistDetailContent` e `AlbumDetailContent` serão extraídos dos componentes existentes (incluindo `ArtistHeader`, `PageBackground`, `NativeReleaseHeaderLayout`, `ActionRow`, `CanonicalTrackRow`, `NativeLibraryEntityCard`, `SectionHeading`). O mesmo componente renderiza biblioteca, pesquisa online, detalhes aninhados e itens fixados.
- **Seções tipadas:** discografia, álbuns recentes, singles/EPs, faixas disponíveis, destaques/ranking com proveniência, metadados editoriais, notas e botões. Uma seção ausente não vira lista vazia com título enganoso.
- **Ações:** Play/Shuffle/Queue somente com `MediaAsset` resolvido; Pin/Playlist/Rating sobre IDs canônicos ou identidade externa validada; inscrições específicas de provedores nunca se confundem com favoritos ou avaliações do app.

## Ordem e gates de migração

1. **D0: discovery cutover (#209)** — prova de ausência de chamadas ocultas YT em Home/Explorar/Pesquisa, sem regredir biblioteca/servidor; Android CI e smoke.
2. **D1: rotas e identidade** — mapa de todos os chamadores de artista/álbum, conversão segura de referências externas, casos de homônimos e ambiguidade; compatibilidade de back stack/links.
3. **D2: compositor de artista único** — Biblioteca + MusicBrainz + eventual provedor de enriquecimento; mesmas dimensões, paleta, seções e ações nativas; sem ranking fictício.
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

## Evidências

- [#205 — Biblioteca/Playlists integrada](https://github.com/jssantogit/BitChord/pull/205)
- [#208 — contrato de migração e unificação](https://github.com/jssantogit/BitChord/issues/208)
- [#178 — auditoria inicial de substituição YouTube](https://github.com/jssantogit/BitChord/issues/178)
- [DESIGN.md](../../DESIGN.md) e [CanonicalNativeArtistDetail.kt](../../app/src/main/java/com/music/bitchord/ui/screens/CanonicalNativeArtistDetail.kt)
