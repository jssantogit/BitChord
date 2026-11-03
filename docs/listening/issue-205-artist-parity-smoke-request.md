# Physical smoke request — PR #205 artist detail parity (2026-11-02)

**Explicit user authorization:** "Ok, seguimos para o smoke."

## Immutable application code and safety gates

- Source PR: https://github.com/jssantogit/BitChord/pull/205 (OPEN/DRAFT; dependent on #193).
- Exact Android application HEAD: `626aef9fda9949dc48aab50a8604f0415c475552`.
- Android CI completed **SUCCESS** on the exact code HEAD: https://github.com/jssantogit/BitChord/actions/runs/41552615055.
- Dedicated smoke branch `smoke/205-artist-parity-20261102` was created **from that exact HEAD**. The only changes on this branch are its one-time physical-smoke push trigger and this request document; no Android code was modified.
- Do **not** merge PR #205, #193, or main. Use the existing workflow guard for #202 native playlist UI; run Android tests, build `assembleDevDebug`, upload a three-day artifact.

## Primary physical comparison — Galaxy A54

Visual baseline: user-supplied photograph of the original **AtHeart** artist page, plus previous approved APK #41474086844. Check **ablume** and **aespa** against the same original composition.

1. Biblioteca → Artistas → open **aespa**, **ablume**, and **AtHeart** when available. The tap must open the native artist page, not a legacy/general folder/album page.
2. Verify native artist photo and artwork wash merge smoothly with the full page background; artist name/logo centered. A missing verified artist photo must not use a record sleeve as a fake giant portrait or reserve an enormous empty canvas.
3. Verify three native **circular** controls: shuffle, large centered play, star (disabled if unsupported). No album-style wide play pills.
4. Search appears only when the toolbar search button is activated or query is present, not permanently.
5. “Faixas”: exactly the native compact row style; horizontally scrolling **four-row columns**, up to 20 tracks, correct song artwork and title, overflow and native ratings, no album-style duration stamp; check ability to play and shuffle.
6. Section layout matches reference order: conditional **Álbum recente** when trusted dated album exists, **Álbuns**, **Singles e EPs**, and **Lançamentos** only for unclassified releases. Avoid fabricating albums or dates; check existing cover art and card dimensions.
7. Enter an album via the artist page; verify all available release members including eligible collaborations, then back both via top toolbar and system Back to the artist (not a parallel legacy stack), and back to artist list.
8. Recheck no regressions in the previously passed 594-song count / 29h20m, Logs integrated in settings, three-dot menu alignment, and Adições recentes; do not reopen without new evidence.

## If the physical smoke fails

Capture screenshots of the exact differing section and export BitChord diagnostics; document which artist and whether an official portrait / release classification existed. Keep source account identifiers, authenticated URLs and media paths out of public logs.

**Not in scope:** rating artists, selecting artists/albums for playlists, new filters in Adições recentes, or other new feature work. These remain separate backlog items.
