# Provider Engine 2.0 — Implementação, Auditoria e Matriz de Conformidade (Issue #218)

Status: **draft, integração backend/domínio concluída na PR #217; UI Fontes condicionada ao merge da PR #212**. Escopo exclusivo: **Android**.

---

## 1. Resumo Arquitetural

A **Provider Engine 2.0** substitui o acoplamento binário por fonte por um motor *fail-closed* orientado a **capacidades tipadas** (`ProviderCapability`) e **instâncias independentes** (`ProviderKey(providerId, instanceId)`):

- **Independência e desligamento total:** Qualquer provedor — inclusive o último ativo em uma capacidade — pode ser desligado sem fallback oculto.
- **Autoridade única:** `ProviderCapabilitySession` + `ProviderPreferenceStore` (JSON versionado sem segredos) governam a autorização; `ProviderAuthorityCoordinator` e `LegacyProviderCompatibility` eliminam *dual-write* entre stores antigas e projetam o estado autoritativo para consumidores legados.
- **Granularidade por `(capacidade, instância)` e cancelamento ativo:** `ProviderPolicyGate` emite tickets e registra callbacks de cancelamento por par `(ProviderCapability, ProviderKey)`, abortando coroutines/sockets *in-flight* (`ProviderQueryExecutor`, `ProviderSingleFlight.runGuarded`, `RateLimitedProviderTransport`, `ArtworkProbePolicyGuard`) sem afetar instâncias irmãs na mesma capacidade.
- **Preservação de dados pessoais e proveniência:** `CatalogProvenanceFilter` e `MusicBrainzCachedSearchRepository` ocultam apenas linhas exclusivamente editoriais quando um catálogo é desativado, mantendo visíveis arquivos locais/pessoais (`LocalOrPersonalAsset`), avaliações do usuário (`UserCurated`) e fatos corroborados por outras fontes ativas, sem apagar registros do Room.
- **Honestidade operacional:** Capacidades ainda não implementadas no binário (rankings, identificação acústica, playlists públicas ou adaptadores iTunes/Discogs aguardando reconciliação com a PR #216) permanecem explicitamente `ProviderReadiness.NOT_IMPLEMENTED`, nunca `READY`.

---

## 2. Matriz Técnica (`Provider × Capacidade × Implementação × Consumidores × Estado × Testes`)

| Provider (`ProviderKey`) | Capacidade (`ProviderCapability`) | Implementação Real | Consumidores Reais | Configuração / Persistência | Estado Atual | Cobertura de Testes | Pendências / Fronteira |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `musicbrainz` | `CATALOG_SEARCH` | `MusicBrainzCatalogSearch`, `MusicBrainzCapabilitySearch`, `MusicBrainzCachedSearchRepository` | Busca canônica, cache offline Room | `ProviderPreferenceStore` (migrado no startup via `ProviderRuntime`) | **READY & Conectado** (HTTP + Room + Proveniência) | `MusicBrainzCapabilitySearchTest`, `MusicBrainzTransportAdmissionTest`, `MusicBrainzCachedSearchRepositoryTest`, `ProviderEngineEndToEndIntegrationTest` | Conectar chamadas legadas restantes de `MainViewModel` após reconciliação `#209`/`#216` |
| `musicbrainz` | `EDITORIAL_DISCOGRAPHY` | `MusicBrainzCatalogGraph`, `MusicBrainzCatalogIndex` | Discografia de artista, edições e detalhes de release | `ProviderPreferenceStore` (migrado no startup) | **READY & Conectado** (HTTP + Room cache) | `MusicBrainzTransportAdmissionTest`, `ProviderCachedReadAccessTest`, `ProviderEngineEndToEndIntegrationTest` | Nenhuma no motor |
| `musicbrainz` | `IDENTITY` | `MusicBrainzClient` (default), `MusicBrainzMetadataEnrichmentProvider`, `MusicBrainzRecordingIdentityDiscoveryProvider` | Resolução ISRC e enriquecimento canônico | `ProviderPreferenceStore` (migrado no startup) | **READY & Conectado** | `MusicBrainzTransportAdmissionTest`, `ProviderEngineEndToEndIntegrationTest` | Nenhuma no motor |
| `coverartarchive` | `RELEASE_ARTWORK` | `CoverArtArchive` + `ArtworkProbePolicyGuard` | Capas de álbum/grupo MusicBrainz | `ProviderPreferenceStore` / `ProviderCapabilityRegistry` | **READY (Contrato & Guard preparados)** | `ProviderCatalogAdaptersTest`, `ProviderCapabilitySessionTest` | Ligar `ArtworkProbePolicyGuard` diretamente dentro de `CoverArtArchive` após sync com `#209` |
| `apple_artist_art` | `ARTIST_ARTWORK` | `AppleArtistArtRepository` + `ArtworkProbePolicyGuard` | Retratos/hero de artista | `ProviderPreferenceStore` / `ProviderCapabilityRegistry` | **READY (Contrato & Guard preparados)** | `ProviderCatalogAdaptersTest` | Ligar `ArtworkProbePolicyGuard` diretamente em `AppleArtistArtRepository` após sync com `#216` |
| `itunes` | `CATALOG_SEARCH`, `EDITORIAL_DISCOGRAPHY`, `RELEASE_ARTWORK` | `RateLimitedProviderTransport` (3,1s) + `ArtworkProbePolicyGuard` | Vitrine editorial Apple-first (PR `#216`) | `ProviderPreferenceStore` | **NOT_IMPLEMENTED nesta branch** (contrato pronto) | `ProviderCatalogAdaptersTest` | Reconciliar com os adaptadores iTunes da PR `#216` |
| `discogs` | `EDITION_METADATA` | `RateLimitedProviderTransport` (3,0s) + `BuiltInProviderDescriptors.discogs` | Metadados de edições físicas (PR `#216`) | `ProviderPreferenceStore` + `DiscogsSearchTokenVault` | **NOT_IMPLEMENTED nesta branch** (`NEEDS_CONFIGURATION` sem token) | `ProviderCapabilityPolicyTest` | Reconciliar com o módulo Discogs da PR `#216` |
| `lrc_red`, `bini_lyrics`, `better_lyrics`, `lyrics_plus`, `lrclib`, `musixmatch`, `kugou`, `unison`, `paxsenix_*`, `genius` | `LYRICS_LOOKUP` (`LYRICS_SYNCED` / `LYRICS_PLAIN`) | `CanonicalLyricsSourcePolicy` (`allowedCanonicalLyricsSources`), `LyricsCapabilityDispatcher`, `LegacyProviderCompatibility` | Picker de letras no Now Playing e busca em background no `PlaybackService` | `ProviderPreferenceStore` via `ProviderAuthorityCoordinator` | **READY & Conectado na política** | `LegacyProviderChoicesTest`, `ProviderCatalogAdaptersTest`, `ProviderEngineEndToEndIntegrationTest` | Cortar escritores diretos restantes em `AppSettings` na integração final |
| `addon:<id>`, `custom_module:<id>` | `EXTENSION_PLAYBACK` (e `DOWNLOAD` se `allowDownloads`) | `LegacyProviderCompatibility`, `ProviderAuthorityCoordinator` | Resolução de extensões configuradas pelo usuário | `ProviderPreferenceStore` (identidade/ordem/on-off) + `EncryptedPrefs` (URLs/segredos) | **READY (Descritores dinâmicos & projeção)** | `LegacyProviderChoicesTest`, `ProviderCapabilitySessionTest`, `ProviderRuntimeMigrationSafetyTest`, `ProviderEngineEndToEndIntegrationTest` | Cortar escritores diretos restantes em `SourceRegistry` na integração final |
| `local:mediastore`, `opensubsonic:<id>`, `webdav:<id>`, `smb:<id>` | `PERSONAL_LIBRARY`, `AUDIO_PLAYBACK` | `CanonicalLocalLibrary`, `CanonicalMultiSourceBrowseRepository`, `CatalogProvenanceFilter` | Biblioteca pessoal e playback autenticado | `ProviderCapabilityRegistry` + `AndroidOpenSubsonicAccountStore` | **READY (Protegido contra opt-out editorial)** | `CatalogProvenanceFilterTest`, `MusicBrainzCachedSearchRepositoryTest`, `ProviderEngineEndToEndIntegrationTest` | Nenhuma mutação destrutiva |
| `soulseek` | `DOWNLOAD` | `SoulseekDownloadRouting` / `A0–A4` | Seletor explícito de candidatos em Downloads | `SoulseekCredentialVault` + `ProviderCapabilityRegistry` | **NEEDS_CONFIGURATION / READY conforme credencial** | `ProviderCapabilityRegistry` | Integrar toggle de capacidade na tela Fontes pós-`#212` |
| `lastfm`, `listenbrainz` | `LISTENING_STATISTICS` | `LastFM`, `ListenBrainzManager`, `ScrobbleManager` | Scrobbling e estado "Tocando agora" | `AppSettings` + `ProviderCapabilityRegistry` | **NEEDS_CONFIGURATION / READY conforme conta** | `ProviderCapabilityRegistry` | Conectar gate `LISTENING_STATISTICS` no `ScrobbleManager` |
| `acoustid`, `charts`, `store_charts`, `listenbrainz` (`PUBLIC_PLAYLISTS`) | `ACOUSTIC_IDENTIFICATION`, `CHART_MOST_PLAYED`, `CHART_SALES`, `PUBLIC_PLAYLISTS` | Não implementado nesta branch | — | — | **NOT_IMPLEMENTED** (explicitamente negado) | `ProviderCapabilityPolicyTest`, `ProviderEngineEndToEndIntegrationTest` | Implementar apenas quando houver fonte aprovada |
| `jiosaavn`, `youtube` | (Aposentados) | Excluídos por `ProviderAuthorityCoordinator` e `LegacyProviderCompatibility` | — | Migrados como excluídos sem apagar IDs históricos | **BLOCKED / Aposentado** | `LegacyProviderChoicesTest`, `ProviderRuntimeMigrationSafetyTest`, `ProviderEngineEndToEndIntegrationTest` | Remoção física final concluída na PR `#209` |

---

## 3. Verificação

```bash
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest --tests "com.music.bitchord.Provider*Test"
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest --tests "com.music.bitchord.CatalogProvenanceFilterTest"
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest --tests "com.music.bitchord.MusicBrainz*Test"
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest
```

## 4. Fronteira de Integração e UI (PR #212 e PR #216)

- **UI congelada:** Nenhum arquivo de UI da PR `#212` (`AppleMusicBottomBar`, `LibraryScreen`, `SourcesScreen`, `MainActivity`) foi alterado nesta branch. `ProviderCapabilityRegistry.projectTab(...)` já entrega o modelo de dados por aba (`Catálogo`, `Capas`, `Letras`, `Estatísticas & Rankings`, `Áudio & Extensões`, `Downloads`) pronto para acoplar aos componentes nativos assim que a PR `#212` for mesclada.
- **PR #217 permanece em `draft`:** Nenhuma conversão para *Ready for Review*, merge ou build de APK foi executado.
