# Playback B14–B18 — Remote quality evidence and physical acceptance

Status: **DRAFT, not physically accepted**. Baseline main: `8a5f9925ea785925ecc56a53590c3e8eefdc9bfd` (B13 PR #133 merged). This document does not authorize merging, integration queue, or an APK.

## Evidence contract

| Stage | Observable fact | Not established |
| --- | --- | --- |
| Preference selection | Current physical Wi-Fi or cellular transport chooses the corresponding persisted setting; metered status is independent | That the active stream was restarted or changed |
| Prepared | Catalog accepted construction of a private DataSource for ORIGINAL or an *unverified request* for capped MP3 | Per-MediaAsset MP3 support, HTTP open, codec, bitrate |
| Transport opened | The delegated DataSource.open returned | Any read bytes, decoding, effective bitrate, audible output |
| Catalog truth | Catalog C12 gave a typed server decision / denied / HTTP headers / bytes stage | MIME is not a decoded codec; byte count is not effective kbps |
| Media3 decoder input reported | An input sample MIME family was reported for the currently matched canonical remote item | Actual decoded frames/output, end-to-end selected transcode, audible listening |
| Media3 decoder initialized | Media3 reported decoder initialization for the currently matched canonical remote item | Audio sink advancing, externally heard sound, bit-perfect output |
| Physical owner check | Manual confirmation on a real Android device with the actual server | Cannot be inferred from CI or `play_to_audible` trace alone |

The `remote_quality_*` records use the existing `Diagnostics.event` pipeline with enum/bounded integer metadata. Stream callbacks share a random `qualityAttemptId`, never a MediaAsset/provider account ID, URL, token, MIME header, file title, or artist. The independent Media3 decoder callbacks do **not** have an attempt ID, because correlating them to a specific stream without a grounded causal key would fabricate evidence. The D11–D15 owner may add the canonical correlation contract later. Decoder input family is allowlisted, and the decoder callback intentionally does **not** claim audible or actual bitrate.

Existing C13 Catalog is responsible for account generation, per-MediaAsset capability check, negotiated server response, secret-bearing URL and HTTP transport. Playback remains responsible for physical-network quality choice and next-open-only lifecycle.

## Automated acceptance matrix

| Scenario | Expected | Automated / physical |
| --- | --- | --- |
| Wi-Fi HIGH | Request 320, only at next Media3 `open` | JVM fake-open + physical pending |
| Cellular MEDIUM | Request 160 even when cellular unmetered | JVM policy/fake-open + physical pending |
| Wi-Fi metered | Still Wi-Fi preference, not cellular | Pure policy tests + physical pending |
| Cellular unmetered | Still cellular preference | Pure policy tests + physical pending |
| ORIGINAL / LOSSLESS | Direct Catalog original; no manifest refresh | JVM fake-open + physical pending |
| LOW 64 | `UNSUPPORTED_CEILING`; reject, never 96 or unbounded raw fallback | JVM + physical pending |
| ADVERTISED only | May prepare request, never per-asset verified | JVM / Catalog C11 contract + physical pending |
| No transcode or no decoder support | Fail-closed, no silent alternate provider or original fallback | JVM / Catalog C11 tests + physical pending |
| Malformed logical URI / stale account | Typed closed/failed, no poisoning next open, no leaked secrets | JVM + device pending |
| Seek / re-open / transitions | New DataSource/open must recheck generation and network; old lease closed | JVM lifecycle checks + device pending |
| Background, close during open, logout/reconnect | Cancellation/lease revocation and terminal truth gate, no late resurrection | JVM partial + device pending |
| HTTP 401/403/429/5xx, timeout | Sanitized error; queue and no-autoplay invariants preserved | Catalog+Playback existing regression; device pending |
| Server truly transcoding | Catalog negotiation and transport + independently matched decoder evidence | **Real OpenSubsonic device test only** |
| Physical audio heard | Tester confirms sound after fresh track, seek and transitions | **Physical device only** |

## Next authorized physical smoke (not executed)

1. Verify the APK's embedded commit SHA (the 1.8.1-beta1 report did not supply it), capture diagnostic mode, account generation state without IDs, and actual physical network transport (Wi-Fi vs cellular).
2. Configure Wi-Fi ORIGINAL; select a fresh canonical remote track (not already playing); start capture; note quality attempt ID, requested mode, Catalog truth after real bytes, Media3 decoder input and initialization; listen to the result.
3. Configure Wi-Fi HIGH 320; initiate a **new** stream; record provider capability (ADVERTISED versus per-asset verified), the server decision/HTTP status, decoder input family and whether audio is actually heard. Do not infer 320 effective from request/metadata alone.
4. On cellular MEDIUM 160, repeat with a new open. Repeat with metered Wi-Fi and unmetered cellular to distinguish physical transport from billing/metering, without forcing a change to an ongoing track.
5. Configure LOW 64; confirm typed rejection without 64→96 upgrade, raw fallback or automatic playback start. Restore ORIGINAL afterward explicitly.
6. Scrub/seek repeatedly, pause/resume, switch between tracks, background/foreground, drop/reconnect network, rotate/logout account. Check callbacks after close, queue integrity, no unexpected autoplay, sanitized errors and bounded event counts.
7. Compare request, C12 negotiation, HTTP headers/bytes and Media3 decoder input **separately**. Ask tester to record actual audible outcome. If decoder/input events cannot be causally joined to the same attempt, mark actual selected-stream codec as UNKNOWN.

## Limitations / dependencies

- A successful `:app:testDevDebugUnitTest` and Android CI only prove automatic assertions, not network transcode or acoustic output.
- Media3 input `Format.bitrate` (when present) is stream metadata, not proof of effective transfer bitrate.
- No changes to Catalog authentication, URLs, DSP, shared diagnostics/trace taxonomy, Listening ratings, Downloads, or crossfade behavior were authorized in this PR.
- Perf D11–D15 should own future end-to-end `flowId` / `parentSpanId` joins. Catalog C14–C18 remote ratings work is independent.
