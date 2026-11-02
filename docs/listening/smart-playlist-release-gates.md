# Playlists #192 — release gates and Galaxy A54 smoke protocol

**Status:** pending real device and version-locked Symfonium field/operator audit; this protocol is **not** a report of a completed smoke or evidence of parity. Target: stable Symfonium Android 15.1.0 (the official current stable release at the 01/11/2026 audit), draft BitChord PR #193.

## Gate A — Android CI exact HEAD (must pass before smoke)

1. `:app:testDevDebugUnitTest` and `:shared:jvmTest` completed with SUCCESS on the exact branch HEAD, including Room migration to v14 and V2 evidence tests.
2. Integration Queue Tests completed SUCCESS for the same HEAD.
3. CI artifact `smart-playlist-benchmark-<sha>/metrics.json` contains the 50,123-recording Room evidence + 50,123 MediaAsset/tag fixture and the second composer/folder result; the deterministic matched IDs must be exact. **CI metrics are host-only**.
4. PR #193 remains draft, issue #192 remains open. No merge just because CI is green.

## Gate B — Galaxy A54, real local/remote assets

1. Install smoke APK **only after Gate A**. Enable diagnostic capture and note the starting build/commit and Android API. No special permissions beyond the application's intended MediaStore access.
2. Test local tracks with verified tags in **ID3v2.3, ID3v2.4, FLAC/Vorbis, Ogg Vorbis, Opus and M4A/MP4**, including samples where `moov` follows `mdat`. Choose an example with one composer and one with multiple composers; include a blank-tagged and an unreadable/unsupported file.
3. In smart playlist editor, use *Compositor = [known value]*, *Gênero contém [known value]*, *Mood é presente*, *BPM >= [known tempo]*, *Estilo = [known value]*. Each positive result must originate from a real tag. `É ausente` must **not** match a file the parser could not inspect.
4. Use *Pasta do arquivo = [verified directory]*, then *Caminho do arquivo termina com [filename]*; verify no sibling `Music` vs `Music 2` or album-title match and no `content://` path synthesis. Check Windows-style backslashes for remote paths.
5. Test `NOT(Compositor contém X)` and E/OU combinations with three cases: known X, known other and unknown. Unknown must not slip in through negation. Ensure multi-valued local tags behave as ANY for positive and ALL for negative conditions.
6. Add one shared canonical recording with separate local and OpenSubsonic sources, different folder/composer and one unknown field. Source-scoped filter must show only its account's tags. Switch remote account and confirm old source data does not leak.
7. Edit tags on a local file and rescan: versioned fingerprint invalidates stale tags; unmodified rescan uses cached extraction. Pull to refresh and reopen the playlist; metadata updates appear without recreating the playlist. Confirm unsupported/corrupt inputs do not make the local library disappear.
8. Reopen an existing saved V2 playlist; modify a nested E/OU/NÃO group and a saved reusable filter; verify exact preservation of rule/operator values, ordering, sorting and limit.
9. Preview, `PLAY_NOW` and `ADD_TO_QUEUE` under the 500-track handoff policy; no implicit autoplay for queue actions, no partially queued playlist on error or account change. Test 501-track rejection.
10. Export the BitChord diagnostic report **after** the session. Look for `local_tag_enrichment_v1` (`attempted/updated/unsupported/accessErrors/superseded`) and `smart_playlist_preview_v2` (`candidateCount/matchedCount/readMs/evaluateMs/fieldsCount/referenceCount/limit`). These events intentionally omit raw filenames, paths, credentials and tag strings. Pair with UI screenshots and the APK commit.
11. Explicitly note any provider that cannot expose a seekable file descriptor, unsupported metadata variant, false-positive negative predicate, cancellation, UI pause, missing cover or empty-list flash.

## Gate C — realistic 50k on-device scale

- Use a **real catalog of comparable cardinality**, not a 50k synthetic JVM test. Take cold/warm and post-rescan samples for a title/duration playlist, a composer+folder playlist and an account-scoped saved Songs filter; each should return exact expected IDs.
- Record preview duration and candidate count from `smart_playlist_preview_v2`, RSS/heap and any Android frame freezes or ANRs. Capture three comparable runs, with hardware/storage/thermal conditions, before setting an acceptance budget.
- Check memory stability after changing filter condition or provider repeatedly. The host CI benchmark reports only its own measured time and memory deltas; it does **not** certify Android UI, Media3, I/O or energy.

## Gate D — Symfonium 15.1.0 parity proof

- Capture **every actual Song, Album and Artist smart-filter field menu** and the operator/options menu for each field, plus sorting, max item count, source visibility/Inherit and edit/copy-to-static behaviors.
- Annotate each row in `smart-playlist-symfonium-parity.md` with reference UI evidence, BitChord behavior, source provenance, automated test and Android smoke outcome. No guesses from similarly named tags or high-level manual screenshots.
- **Remaining known gaps** include Favorite Date (distinct from rating date), original release date (distinct from edition date), label/release type, thumbnail presence/absence semantics, copy-to-static and exact album/artist scope parity. Keep separate dependency issues for metadata unsupported by the catalog instead of fabricating evidence.

## Decision rule

**Do not declare Symfonium parity or merge/close #192 unless Gates A–D have verifiable evidence and no unresolved P1/P2 regression.** A green Android CI alone authorizes only the next smoke phase, not closure.
