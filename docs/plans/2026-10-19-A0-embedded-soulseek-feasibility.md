# A0 — Embedded Soulseek Feasibility v0

**Date:** 2026-10-19  
**Scope:** feasibility report, loopback-only Android/Kotlin protocol frames and read-only parser, plus owned-WAV local-byte staging; no external network/device execution authorized  
**Decision state:** MOBILE_CLIENT_PREFERRED / ENGINE_INTEGRATION_NOT_PROVEN  
**Recovery:** NO_EXISTING_IMPLEMENTATION (scope-limited verified search)  
**Operational gate:** REAL_NETWORK_NOT_EXERCISED

## 1. Recovery and provenance

- Fork: `jssantogit/BitChord`; protected `main` at `fedba3467fefad1f375b9895dab199f178f6b5f9`, independently checked against the live branch API and Git history.
- [Android CI #592](https://github.com/jssantogit/BitChord/actions/runs/39272521918): `completed/success`, **same** main SHA, from before this research branch.
- Queried remote branch inventory (100/page plus subsequent page), plus focused branches named `acquisition`, `downloads`, `soulseek`, and `slskd`: no pre-existing Acquisition branch found. Queried PRs #1–#62, including open/draft/closed; four open PRs #59–#62 relate to other workstreams. Commit searches for `soulseek` and `acquisition` and default-branch code search for `soulseek` returned no matches. This is not evidence that every legacy file lacks download logic.
- Legacy `DownloadService` / `DownloadSession` / `DownloadManagerSheet` are **not** Soulseek acquisition; `Song.videoId` is not canonical Recording identity. Do not reuse/replace that downloader here.
- Existing Android app: `app/build.gradle.kts`, Kotlin, `minSdk=26`, `targetSdk=36`, `compileSdk=37`, ABI splits arm32/arm64/x86_64. Existing required PR CI in `.github/workflows/check.yml` executes `./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest` (no APK).
- Precedence: owner's ON-DEVICE requirement in [Acquisition](https://app.notion.com/p/3fcfe7e229fd81a49858ebba21f3e199) and [handoff](https://app.notion.com/p/3fefe7e229fd81c9b0d5cea505ca8a74); earlier external-server/Delivery Bridge plan is superseded.

## 2. Evidence matrix

| Dimension | Embed official slskd | Embedded mobile Soulseek client (Seeker/Soulseek.NET reference) |
| --- | --- | --- |
| Existing executable/runtime | Official [slskd csproj](https://github.com/slskd/slskd/blob/master/src/slskd/slskd.csproj): `Microsoft.NET.Sdk.Web`, `net10.0`, web daemon. Linux arm64/Docker builds are **not Android APKs**. | [Seeker Android project](https://github.com/jackBonadies/SeekerAndroid/blob/master/Seeker/Seeker.csproj): `net10.0-android36.0`, `SupportedOSPlatformVersion=23`, Android architecture builds; it references a modified Soulseek.NET. Demonstrates protocol-client viability on Android, **not** embeddability inside Kotlin BitChord. |
| BitChord interop | Would need Android-compatible .NET hosting, daemon process lifecycle, web server or replacement API, file boundary and probably duplicate platform/runtime dependencies; no verified packaging path. | C#/.NET for Android runtime and Kotlin interoperability still require a proven bridge and packaging build; alternative is audited minimal Kotlin/JVM protocol port. No supported drop-in JVM artifact established. |
| Licensing | [AGPL-3.0-only with additional terms](https://github.com/slskd/slskd/blob/master/LICENSE). Different code license than the existing GPLv3 BitChord; assess combined distribution, source offer, UI/source notices, change identification and network provisions with legal review. | Seeker [GPL-3.0-only and §7 terms](https://github.com/jackBonadies/SeekerAndroid/blob/master/LICENSE); [Soulseek.NET current GPL-3.0-only and §7 terms](https://github.com/jpdillingham/Soulseek.NET/blob/master/LICENSE). Preserve notices/attribution and avoid brand confusion; client version identifier must be independently assigned and not impersonate another client. Version 10.x requires minor identifier >100; inspect reserved ranges before deployment. |
| Minimum device/runtime | Android min26/target36: no demonstrated `net10.0` webdaemon-in-Kotlin-APK feasibility. Android 10+ forbids executing code extracted into app-writable directory (W^X); do not ship an unpacked Linux ELF and call `exec`. | Seeker's `SupportedOSPlatformVersion=23` covers API26 as an independent app; embedding into the BitChord target36 and surviving R8/trimming/AOT must be proven before acceptance. |
| Core use case | slskd supports Soulseek login/search/download, but contains irrelevant web UI, HTTP API, admin/config, sharing and daemon facilities. | Seeker already implements network login/search/peer transfer on Android; minimize embedded feature set to user-initiated search, candidate selection, receive, cancel, clean stop. Never auto-share storage. |
| Auth, sockets, P2P | Requires Soulseek credentials and outbound TCP/peer sockets; inbound reachability may be blocked by mobile CGNAT/NAT. | Same protocol/network constraints; mobile client can own socket lifecycle in its foreground-bound session directly. Peer IP exposure is intrinsic to P2P; direct connections cannot be promised for every peer. |
| Startup/memory/APK overhead | Expected higher integration and runtime complexity from .NET Web SDK, web services and more dependencies; **no measured values**. | Less conceptual surface than slskd, but .NET runtime embedded in Kotlin may add large binary/memory cost; a Kotlin engine might reduce this at increased port/maintenance cost. **No measured values**. |
| Maintenance/security | Full upstream slskd revisions, AGPL compliance and Android packaging to maintain. | Track Seeker/Soulseek.NET protocol changes, licensing notices/client ID and potential fork divergence; building reliable Kotlin parity is nontrivial. |
| Recommendation | **Do not select as embedded engine** without a concrete Android build and lifecycle proof. | **Preferred architecture family**, conditional on proof that a protocol client can be integrated into the existing one-APK Kotlin Android app. |

Additional repository-level evidence:
- Official slskd references `Soulseek` NuGet v10.0.2 in its `net10.0` Web SDK project; shipping it would not remove the protocol-library relationship.
- Upstream [Soulseek.NET project](https://github.com/jpdillingham/Soulseek.NET/blob/master/src/Soulseek.csproj) targets `netstandard2.0;netstandard2.1`; that does **not** make it a JVM/Kotlin library.
- The Seeker app's project references a **modified bundled** `Soulseek.NET-master`; do not assume it is byte-for-byte equivalent to upstream 10.0.2.
- [Android 10 W^X platform behavior](https://developer.android.com/about/versions/10/behavior-changes-10) disallows execution from writable app directories for target 29+.
- Licensing review is preliminary engineering risk assessment, not legal certification.

## 3. Android service and storage constraints

- Foreground-only interactive search can hold a session while the UI is visible. User-initiated longer transfer may require an explicit user-visible notification and a correctly declared foreground service; background-start restrictions and permissions apply. Do not tie a transfer socket to an Activity lifecycle.
- Android 15+ for target35+ imposes an aggregated **6h/24h** background timeout on `dataSync` foreground services; implement `Service.onTimeout(int,int)` and stop safely. [Android developer guidance](https://developer.android.com/develop/background-work/services/fgs/timeout).
- [User-initiated data transfer jobs (UIDT)](https://developer.android.com/develop/background-work/background-tasks/uidt) are available from Android 14/API34, require scheduling under user-visible conditions plus notification, and remain system-constrained. Verify suitability for long-lived P2P socket sessions before relying on them.
- API26–28 can write **app-private** staging bytes without broad media permissions; API29+ MediaStore publication with `IS_PENDING` is future A4 only. For API26–28 final public storage, design SAF or permission-compatible scoped import; do not pretend `IS_PENDING` exists there.
- Termination model: `STOPPED → CONNECTING → AUTHENTICATING → READY/SEARCHING → TRANSFERRING → IDLE_GRACE → STOPPED`; `FAILED/RECOVERING/CANCELLED` explicit. Session cannot disappear during transfer. User cancel and process death need structured cleanup/resume planning. No auto-start triggered by recommendations or background app launch.
- No implicit file sharing, local-storage enumeration or listening server by default. Clearly disclose P2P peer-IP visibility and NAT/CGNAT limits; bound file size, storage quota, sockets, timeouts and retries. Keystore-backed secret storage is for production only. Never put passwords, IPs, peer paths or user queries into logs.
- `CanonicalLocalLibrary.sync` can create a fresh random `RecordingId` for a new, unbound URI. A4 must agree on an explicit, atomic/idempotent `RecordingId ↔ published MediaStore URI` seam **with Catalog ownership**; filename, metadata and peer are not trusted identity.

## 4. Implemented Kotlin A0 loopback spike, gates and evidence

The branch adds a deliberately isolated `internal` probe in `app/src/main/java/com/music/bitchord/acquisition/a0/A0SoulseekLoopbackProbe.kt` and its JUnit test in `app/src/test/java/com/music/bitchord/acquisition/a0/A0SoulseekLoopbackProbeTest.kt`. It has **no app UI entry point, no manifest/service changes, no added runtime dependency** and refuses all non-loopback endpoints *before* attempting name resolution or any TCP connection.

The probe exercises upstream-documented Soulseek **server framing** over actual `127.0.0.1` sockets: little-endian message length + message code; login (code 1, user/password, protocol major 170, MD5 wire digest, test-only minor version) and login response (without propagating server text); FileSearch request (code 26, explicit token and search query). It uses a 4-second socket timeout, 1 MiB inbound frame limit, 512-byte query limit, typed login rejection and fail-closed close. **A separate, read-only search-results decoder now exists for offline fixtures; no peer handshake, live search-results socket listener, queue or file-transfer receiver exists.** Historical Soulseek MD5 hashing is required by the **wire protocol** and is *not* secure credential storage; never reuse test credentials or minor ID for real login.

- Protocol reference, not imported third-party code: [Soulseek.NET login request](https://github.com/jpdillingham/Soulseek.NET/blob/master/src/Messaging/Messages/Server/LoginRequest.cs), [FileSearch request](https://github.com/jpdillingham/Soulseek.NET/blob/master/src/Messaging/Messages/Server/SearchRequest.cs), [message framing](https://github.com/jpdillingham/Soulseek.NET/blob/master/src/Messaging/MessageBuilder.cs), [login response](https://github.com/jpdillingham/Soulseek.NET/blob/master/src/Messaging/Messages/Server/LoginResponse.cs). The GPL-3.0-only + §7 NOTICE remains a downstream import/derivative assessment requirement.
- Real localhost socket JUnit tests cover field encoding and MD5 test vector, search request, closure after explicit session end, denial redaction, early non-loopback refusal, search-before-auth guard and oversized payload fail-closed.
- **First TDD batch:** tests first at `5e58482584cfdeecde36ee19720c498c9e17e96f`; [RED CI #672](https://github.com/jssantogit/BitChord/actions/runs/39288074237) cancelled by PR concurrency (not a proven RED failure); implementation at `53f0bd879964238500f747cce7a60921b6e20400`, documentation at `b68459c121a99405e63773662e5970f6f82d84ac`; [CI #680](https://github.com/jssantogit/BitChord/actions/runs/39288890479) **completed/success** on exactly `b68459c...`, verifying the initial Kotlin localhost probe.
- Environment includes JDK/Kotlin but no Android SDK, .NET CLI, Android device or emulator; no real credentials or external peer network were authorized.

| Gate | Observed result |
| --- | --- |
| Recovery | `NO_EXISTING_IMPLEMENTATION` after scoped live audit |
| Source-targeted client | `SEEKER_ANDROID_PROJECT_VERIFIED` (`net10.0-android36.0`) |
| Android Kotlin app source & localhost framing | `IMPLEMENTED, ANDROID_CI_680_GREEN` (exact earlier HEAD); TCP *only on localhost* |
| Upstream slskd daemon embedded into APK | `NOT_BUILT / NOT_SELECTED` |
| Full Soulseek login / real peer search responses / selected peer | `REAL_NETWORK_NOT_EXERCISED`; fixture-only decoder exists, no live peer socket |
| Authenticated P2P download, authorized audio, SHA-256/codec | `NOT_IMPLEMENTED / NOT_MEASURED` |
| Android physical installation and process-death / background behavior | `DEVICE_NOT_EXERCISED` |
| Startup latency, RSS/heap, CPU, APK delta, radio/battery | `NOT_MEASURED` |
| Source-code license import | `NONE`; protocol fields independently encoded, legal review still pending |
| Merge and productive slices | `NOT_AUTHORIZED` |

**Important:** localhost server fixture is not the Soulseek service, a search candidate, a peer, or an audio transfer. This experiment only narrows the feasibility risk of building a small Kotlin packet framing layer inside BitChord, while full protocol implementation is still unproven and may be too costly to maintain.

The Seeker [fork changes record](https://github.com/jackBonadies/SeekerAndroid/blob/master/Seeker/Soulseek.NET-master/SEEKER-CHANGES.md) specifically documents dual-stack/IPv6-only carrier networking, Latin-1 filename fallbacks, transfer and concurrent search fixes. An eventual native implementation must reproduce **verified behaviors** through interoperability tests; do not extrapolate this localhost framing success to real mobile-peer reachability.

**Real acceptance still requires** a single APK on representative Android versions, user-requested session, licensed test credentials, actual search response and explicit user candidate choice, P2P receipt of legally authorized audio into app-private storage, SHA-256/codec and idle shutdown, battery/CPU/memory measures. Physical device/network requires a separate explicit owner authorization.

### A0 read-only peer search-response decoder — additional isolated spike

- **TDD commits:** search-response test fixture first at `084e1683b505ecfceef6c0fc25a9428d813c6040`; [RED CI #703](https://github.com/jssantogit/BitChord/actions/runs/39294205454) was cancelled when the follow-on push superseded it (no RED CI failure to claim). Standalone JVM red compilation without the parser exited 1 (unresolved parser); local Kotlin JVM smoke after the implementation passed decoding/token/decompression, Latin-1, truncated frames and rejected negative sizes. Kotlin parser commit `ec673ef68a1b391979c22b0406b301f771ab8935`. [CI #709](https://github.com/jssantogit/BitChord/actions/runs/39294731856) was dispatched on that code HEAD; check actual conclusion, then revalidate any later final documentation-only HEAD.
- `app/src/main/java/com/music/bitchord/acquisition/a0/A0SoulseekSearchResponseParser.kt` independently reads **zlib-compressed peer message code 9** into explicitly untrusted, read-only result objects (peer username, search token, public/locked files, byte sizes, extensions, attributes, upload slot, queue length). It checks the expected search token and exact wire frame length before decompression; bounds wire bytes to 1 MiB, decompressed bytes to 512 KiB, string length to 2 KiB, total files to 128 and attributes per file to 32; rejects negative counts/sizes, malformed data and unexpected message codes. UTF-8 decode has a reversible Latin-1 fallback for legacy peer names. No generated RecordingId, no trust promotion and no persistence. Source format cross-check: [Soulseek.NET SearchResponseFactory](https://github.com/jpdillingham/Soulseek.NET/blob/master/src/Messaging/Messages/Peer/SearchResponseFactory.cs) and [file decoding extensions](https://github.com/jpdillingham/Soulseek.NET/blob/master/src/Messaging/MessageReaderExtensions.cs).
- `app/src/test/java/com/music/bitchord/acquisition/a0/A0SoulseekSearchResponseParserTest.kt`: deterministic zlib fixtures cover public/locked candidates, field/attribute preservation, token/code checks, truncated or inconsistent frame, compressed bomb, absurd file counts, malformed string lengths, negative byte count and legacy Latin-1. **Fixtures are not real Soulseek search results**. No real peer socket, file selection/download, filesystem write or Android device is exercised by this parser.

The new decoder reduces protocol compatibility uncertainty only for message framing and parsing. It does not establish actual peer connectivity, interoperability, audio acquisition, CPU/memory behavior or valid license status. `REAL_NETWORK_NOT_EXERCISED` remains unchanged.

### A0 additional local-byte staging evidence (test-owned WAV; not a Soulseek transfer)

- **TDD/provenance:** test-first JUnit `A0VerifiedByteStagerTest.kt` at commit `59b623ef967c51ac2a3ccb02964a51cfb9f99d71` verifies streamed bytes from a **real localhost TCP socket** carrying a self-generated PCM WAV, expected size and SHA-256, malformed/truncated/oversized inputs, cancellation, deletion of `.partial` on failure, unknown container and duplicate-file non-overwrite. [Android CI #819](https://github.com/jssantogit/BitChord/actions/runs/39345218953) was **cancelled** by the branch's following push; a RED CI failure was **not** obtained. Local `kotlinc` compilation of the focused harness without the implementation exited 1 (unresolved stager), followed by a local Kotlin/JVM harness **PASS** after implementation for bytes/hash/truncation/digest mismatch/cancellation/container/bounds/cleanup.
- **Code:** `A0VerifiedByteStager.kt` at `42b24e336f5bbd469b51675440c808cbd41c0872`. Explicit caller-supplied **app-private** directory (privacy of directory is caller's obligation), bounded up-to-512-MiB streamed size with 8 KiB buffer, external *trusted* expected SHA-256, `MessageDigest.isEqual` after exactly requested bytes, unique temporary `.partial` name unrelated to peer-controlled filenames, same-directory `ATOMIC_MOVE` to `.verified` on success, cleanup if closed/truncated/cancelled/corrupted. Fail closed if atomic move is not available; no silent non-atomic fallback. Header-only hints `WAV_HEADER_ONLY`, `FLAC_HEADER_ONLY`, `ID3_HEADER_ONLY` are not codecs proven playable or safe.
- **Verified GitHub build:** [Android CI #820](https://github.com/jssantogit/BitChord/actions/runs/39345379884) **completed/success** on the exact code HEAD `42b24e336f5bbd469b51675440c808cbd41c0872`. This verifies Android JVM unit tests, not Android-device behavior. Any later documentation commit must obtain a new exact-HEAD CI before declaring full-branch GREEN.
- **Crucial trust limit:** search results provide a claimed size/filename, but not a trustworthy SHA-256. The SHA-256 in this local test comes from its **independent owner-created fixture**. This spike proves a byte-integrity mechanism given trusted hash input; it does **not** prove authenticity of arbitrary Soulseek files, music codec decoding, peer negotiation, actual remote bytes or full Android lifecycle. No Catalog recording/media binding, MediaStore, UI, service, real credentials or network was implemented.
- **Main drift:** initial PR base `fedba346...`; protected `main` had advanced to `213a5fc1012523e778765ce8019cfa97ac8abfc3` on 2026-10-19. Keep branch draft/behind and coordinate any later non-destructive synchronization with central integration orchestration.

## 5. Conditional architecture recommendation

Select a **small embedded mobile protocol client**, not slskd-as-daemon, for the intended Android product; **defer choice of the implementation/runtime** until the integration gate proves either:

1. An audited .NET-for-Android network core can be linked and invoked within the existing Kotlin-based BitChord APK (without second installed app, separate process service host, web UI, or external server), with measured startup and memory; **or**
2. A narrowly scoped, licensing-reviewed Kotlin/JVM protocol implementation/port preserves the necessary Soulseek login/search/transfer semantics against authoritative fixtures and is validated on the real network with a permitted test file.

Require a feasibility decision record covering package size, runtime, IPC/JNI/interop, license notices, API26 compatibility, CPU and binary provenance. Do not import the entire Seeker/slskd tree or start a speculative protocol rewrite. Both routes should be judged using the exact same mobile device/network proof.

Proposed boundaries after approval: `AcquisitionIntent(RecordingId?)` → `SoulseekEngine` adapter → `AcquisitionSessionController` → `P2PTransfer` → app-private verified file → future MediaStore publish → Catalog-owned canonical binding → existing Media3 playback. A manually searched file may legitimately have **no** canonical `RecordingId` pending explicit user resolution; never fabricate one from filename or `Song.videoId`.

## 6. Proposed **unapproved** productive slices A1–A5

- **A1 — One-APK Android Soulseek vertical proof:** first prove chosen runtime/build/interop with min26 target36 in BitChord's APK, on-demand start/auth/search/explicit selection and **real licensed P2P audio into app-private file**, hash and clean idle stop in foreground. RED→GREEN local logic tests + actual authorized device/network proof. No catalog publication yet.
- **A2 — Canonical-aware Search & Candidate Review:** RecordingId-aware manual intent, provider-independent query and response shaping, explicit user file/peer choice, quality/evidence display, no untrusted metadata-as-identity, no automatic downloads.
- **A3 — Durable On-device P2P Queue & Session Recovery:** Room transfer intent/checkpoints, bounded concurrency, reconnection after process death/network change, cancel/retry and validated resume by peer capabilities, FGS/UIDT/API26–36 foreground policy.
- **A4 — Verified Android Storage & Canonical Binding:** app-private validation to MediaStore/SAF with API29+ pending semantics, disk quota, hash/decoder gate, rollback/idempotence, explicit Catalog-owned RecordingId↔MediaAsset seam, no duplicate identity, offline existing Playback.
- **A5 — Integrated Acquisition UX, Privacy & Reliability:** cohesive search/selection/progress/retry/cancel notifications, permissions, battery/port/privacy disclosures, diagnostics with redaction and failure reasons, structured physical smoke and resilience matrix.

All five are only **proposed**; implementation and merge require separate owner/orchestration authorization. This branch is documentation only and does not modify Playback, Catalog, Listening, Diagnostics, legacy Downloads, manifest, dependencies or runtime.

## 7. Next acceptance gate

1. Confirm owner approval for a dedicated **physical/network** proof with test credentials and an audio file for which sharing/download permission exists. Do **not** write credentials in GitHub, Notion, CI, or logs.
2. Arrange a reproducible Android build environment capable of testing one-APK .NET/Kotlin interop **before** porting any source, alongside a native-JVM feasibility check. If neither can be compiled or isolated with credible app integration, retain blocker and revise technology choice.
3. Retain the committed loopback protocol tests; verify Android CI result on the exact final HEAD, and record the RED run's cancellation accurately. Add further RED→GREEN acceptance tests only for a genuinely selected and approved integration engine; do not mistake localhost for the production network.
4. Return measurements and evidence to orchestration; no A1–A5 or merge until explicitly approved.
