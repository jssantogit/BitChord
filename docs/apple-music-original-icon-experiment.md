# Apple Music 7.0.0 — vectors in the isolated bottom chrome experiment

For PR #212 only; no changes to main or PR #209. The eight Android vector XML assets below are copied **verbatim** from the AM-DECOMP catalog on branch `fase-3-investigacao-visual`. The source project records them as resources recovered from Apple Music Android 7.0.0 (build 1614), not icons invented from the screenshot. SHA values below identify Git blobs, not a license.

| BitChord drawable | Catalog origin | Source Git blob |
|---|---|---|
| `@drawable/apple_music_tab_home` | `ui-assets/catalog/android-vector/navigation/base/drawable/tabbar_home.xml` | `fa33e4dff1e1627f9249c9fab9e8d5fd9a9cbc25` |
| `@drawable/apple_music_tab_explore` | `ui-assets/catalog/android-vector/navigation/base/drawable/ic_uitabbar_browse.xml` | `a348f6d38c5863c4c38a49cd37fb260723c2a5a4` |
| `@drawable/apple_music_tab_radio` | `ui-assets/catalog/android-vector/navigation/base/drawable/ic_uitabbar_radio.xml` | `68cc7822527d695fdc5f3f486b96b67f8b7d8b88` |
| `@drawable/apple_music_tab_library` | `ui-assets/catalog/android-vector/navigation/base/drawable/ic_uitabbar_library.xml` | `094fd07153c0934dcedbd43a3eb2fe81b071b5f0` |
| `@drawable/apple_music_tab_search` | `ui-assets/catalog/android-vector/navigation/base/drawable/ic_uitabbar_search.xml` | `f22a21e75fbaa5234b70043eb8adcbcdc4034706` |
| `@drawable/apple_music_mp_play` | `ui-assets/catalog/android-vector/mini-player/base/drawable/ic_nowplaying_mp_play.xml` | `14a9c8d9a5475e7c64840f8da09cbe05e82a1dec` |
| `@drawable/apple_music_mp_pause` | `ui-assets/catalog/android-vector/mini-player/base/drawable/ic_nowplaying_mp_pause.xml` | `07402879132af756748289354fc59933f4b16a1b` |
| `@drawable/apple_music_mp_next` | `ui-assets/catalog/android-vector/mini-player/base/drawable/ic_nowplaying_mp_fforward.xml` | `6d4ac50f08e11c76d608c0454ab9eb76da9c59d7` |

The five tab vectors correspond to the static Android menu in `docs/design/iconography.json`; a static menu reference **does not prove** the exact runtime variant shown in the user's screenshot. The three miniplayer vectors come from the extracted miniplayer drawable catalog. Icon sizes remain 30 dp (tabs) and 32 dp (transport), according to the current experimental metrics. Android Compose `Icon` applies the active, inactive and disabled tints at runtime; the Radio tab remains disabled without a route. Existing click, gestures, playback state and the 48 dp/62 dp capsules are unchanged.

**Rights:** AM-DECOMP documents that individual reuse/redistribution permissions for these Apple-derived graphic assets are not established. The private research repository is not itself an authorization to publish them. This is an isolated visual experiment; review permissions before redistribution or merging into a public release. This file only documents provenance, not a licensing claim.
