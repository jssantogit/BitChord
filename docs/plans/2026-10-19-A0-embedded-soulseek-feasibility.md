# A0 — Embedded Soulseek Feasibility v0

**Date:** 2026-10-19  
**Scope:** research and architecture only; no network/device execution authorized  
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

## 4. Prototype gate and falsifiable results

A0 investigation demonstrates **source-level** feasibility of an Android-targeted C# Soulseek client (Seeker). It does **not** demonstrate an engine integrated into BitChord.

No executable networking prototype or third-party protocol code has been committed here: there is no verified approach yet for bundling .NET Android components into the existing Kotlin/Gradle one-APK application, and importing GPL/AGPL code without completing provenance/notice obligations would be premature. This is a real blocker rather than a simulated success.

Execution environment for this research: no Android device/emulator or Android SDK and no `dotnet` CLI available; shell GitHub networking was unavailable, so repository inspection used authenticated read-only GitHub APIs. Owner has **not authorized** real Soulseek credentials, peer network or device smoke.

| Gate | Observed result |
| --- | --- |
| Existing A0 implementation | `NO_EXISTING_IMPLEMENTATION` from live scoped search |
| Runtime reference on Android | `SOURCE_TARGET_VERIFIED`: Seeker's `net10.0-android36.0` project |
| slskd Android-hosted build | `NOT_BUILT / NOT_PROVEN` |
| Seeker/Soulseek.NET embedded in Kotlin BitChord APK | `NOT_BUILT / NOT_PROVEN` |
| Android install & launch / foreground lifecycle | `DEVICE_NOT_AVAILABLE` |
| Soulseek login/search/selection/socket transfer | `REAL_NETWORK_NOT_EXERCISED` |
| Authorized audio bytes/hash/codec | `NOT_MEASURED` |
| Startup latency, RSS/Java/native heap, CPU, APK delta, radio/battery | `NOT_MEASURED` |
| RED→GREEN A0 behavioral tests | `NOT_EXECUTED` (documentation-only checkpoint) |
| GitHub PR check | See this PR's live CI on exact head; unrelated main #592 is not a branch result |

Do **not** promote `SOURCE_TARGET_VERIFIED` to `REAL_SOULSEEK_TRANSFER_PROVEN`. The minimum actual acceptance test requires: one APK on a real API26/34/36-representative device; user-requested start; login with dedicated authorized account; search, explicit candidate, P2P transfer of owner-created/licensed audio into app-private temporary storage; byte count, SHA-256, file format and session logout/idle close; then latency/memory/CPU and recoverable failure evidence with redacted logs. Separate explicit operational authorization required before the test.

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
3. Write failing A0 runtime/lifecycle acceptance tests, prove RED, implement only scoped spike, prove GREEN on the same HEAD and capture Android CI; then run approved real network smoke separately.
4. Return measurements and evidence to orchestration; no A1–A5 or merge until explicitly approved.
