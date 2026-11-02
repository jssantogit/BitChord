# Physical smoke request — PR #205 regression repairs (2026-11-02)

**Authorized by user:** "Dispare apk" after Android CI success on [#41538905297](https://github.com/jssantogit/BitChord/actions/runs/41538905297).

## Immutable application baseline

- PR [#205](https://github.com/jssantogit/BitChord/pull/205), **code HEAD** `c2e8897121d6d28b3f1a46ec7af6d04d92ca7cd4`, Android CI SUCCESS.
- Smoke branch `smoke/205-regressions-20261102` created at the exact code HEAD. Changes on smoke branch are exclusively this request and the scoped GitHub Actions push-trigger.
- **Do not merge #205, #193 or main** because this is physical validation only.

## Physical checks (Galaxy A54)

1. Library → Músicas: 594 local tracks (as in baseline), duration shows calculated hours/minutes rather than literal Kotlin template. Confirm overflow ⋮ alignment, search, filter stars and origin.
2. Library → Artistas → aespa: opens native artist page (not generic folder), photo/header, "Faixas" horizontal four-row carousel and grounded Singles e EPs / Álbuns / Álbum recente if catalog evidence exists. Tap release; toolbar and Android Back return to artist, then artist list.
3. Library → Álbuns: render in native library layout and show correct cover/list without visual regression.
4. Biblioteca → Adições recentes returns after initial MediaStore indexing, "Ver todos" works, repeated navigation is cached; don't infer a real empty library from pre-index cache.
5. Configurações → Diagnósticos → Logs opens as embedded native page without separate-Activity transition; run capture, stop, share report to confirm dev FileProvider export still works.
6. Connect Navidrome and validate unified source filtering without losing local songs, metadata, artwork, native stars, and actions; collect relevant diagnostics for any failures.

**Not part of this build:** adding tracks/artists/albums to playlists, new contextual ⋮ actions, multiple selection, rating artists, new filters in Adições recentes.

CI workflow must pass guard requiring #202 UI, Android unit tests, `assembleDevDebug`, and artifact upload. Keep branch isolated; no merge.
