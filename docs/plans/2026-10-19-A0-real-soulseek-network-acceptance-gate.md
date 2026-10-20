# A0 — Real Soulseek interoperability acceptance gate

**Status: PUBLIC-PEER-FIRST APPROVED; LIVE NETWORK NOT EXECUTED.** This document is an operational acceptance contract only, not permission to use credentials or public Soulseek endpoints.

**Scope:** BitChord one-APK Android (minSdk 26, targetSdk 36), Kotlin feasibility branch in [draft PR #63](https://github.com/jssantogit/BitChord/pull/63), with no separate Docker, Termux, server, Seeker APK, .NET runtime or background host. Preserve Playback, Catalog, Listening, Diagnostics and the legacy Song.videoId downloader; no merge and no A1–A5.

## 2026-10-20 strategy decision — PUBLIC PEER FIRST (supersedes mandatory controlled peer)

**Owner approval:** choose existing Soulseek public peer shares for a narrow **open-licensed one-file** A0 test. Installing/configuring Nicotine+ on the owner's PC is **NOT REQUIRED**; the previously mandatory owner-controlled uploader/second account becomes an **OPTIONAL FALLBACK ONLY**. See [A0 Public Peers First acceptance guide](2026-10-20-A0-public-peer-first.md). Requirements below referring to a controlled peer are now superseded by this decision.

A filename, size and the fact that a peer publicly shares it do NOT establish copyright rights or provenance. A0 requires license evidence on a source outside Soulseek, an independently obtained SHA-256/size, one candidate matching the original, and a strict digest comparison AFTER bytes arrive. Public search results must not be auto-trusted. Deny on a different hash, queue/lock, missing approved rights or size mismatch; do not download arbitrary copyrighted tracks to test the engine.

New `A0OpenAudioCandidateGate` + JUnit tests explicitly enforce the one-candidate bounds and reject unreviewed licenses or untrusted paths WITHOUT performing network I/O. Tests-first [Android CI #869](https://github.com/jssantogit/BitChord/actions/runs/39362847321) had real compile RED because the class did not yet exist; implementation `db549ba1a0013e8686787863cae81f1925e32013`. [Android CI #872](https://github.com/jssantogit/BitChord/actions/runs/39363232760) initially failed because the runner's NDK 27 archive was corrupt, **BEFORE source compile**, so its failed job was re-run. Verify the outcome of that re-run/final document HEAD before declaring GREEN.

No login, search or download on the actual Soulseek network has been performed; required credentials, approved live identity and modern inbound P/F runtime remain pending. The old no-Secret emulator workflow remains unchanged.

## Recovered proof: what is and is not already established

- Required Android CI [#854](https://github.com/jssantogit/BitChord/actions/runs/39356503607) completed successfully at code/report HEAD d84999c7b0973e04058a744284219e2093e39bf7.
- Android Emulator Smoke [#4](https://github.com/jssantogit/BitChord/actions/runs/39355652413) completed successfully at implementation HEAD f996e2869cf2ea565510d035bc1b108bae24e8ba: API 26 had **9/9 PASS**, API 36 had **9/9 PASS** in the installed devDebug APK. The only subsequent change was documentation.
- The tests implement locally simulated server login/search, bounded read-only search response decoding, P authorization before F, raw F transfer token, owned PCM-WAV byte staging into app-private storage, integrity from a separately trusted test fixture and deletion after rejection, EOF or timeout.
- All successful TCP connections in the probe are limited to literal **127.0.0.1**. No real Soulseek login, peer discovery, publicly reachable P/F socket, real remote audio, network-origin authentication, playback codec test, mobile carrier/CGNAT/IPv6 test or physical phone test has happened.
- The unit-test login minor-version example **1201** is not an approved public BitChord client identity.

**Local Android feasibility = GREEN. Live Soulseek interoperability = NOT TESTED.** Repeating localhost simulations cannot replace the missing live evidence.

## Required approvals and fixtures before any live connection

| Mandatory gate | Required evidence | Current result |
| --- | --- | --- |
| Owner authorization | Public-peer-first one-file strategy approved by owner; credentials, client identity and secure live transport remain missing | STRATEGY APPROVED; LIVE NOT EXECUTED |
| Dedicated account | Test-only Soulseek account, never written into a chat, PR, CI logs or Notion page | NOT PROVIDED |
| Public peer | Existing peer sharing an independently licensed exact file, with matching filename/size, normal permissions and reachable slot; uploader need not be owner-controlled | NOT DISCOVERED / NOT TESTED |
| Test content | Open-licensed/PD source with verified independent rights, exact bytes, source filename, SHA-256 and size OUTSIDE Soulseek; content on peer must match these | LICENSE EXAMPLE IDENTIFIED; ORIGINAL SHA-256 NOT VERIFIED |
| Client identity | Review and reserve a demonstrably distinct minor-version identifier for the live BitChord experiment; do not transmit a fixture identifier | NOT APPROVED |
| Licensing | Review GPL-3.0-only Soulseek.NET and Seeker additional terms if incorporating their code/binaries; keep original notices and obligations as applicable, and inspect Kotlin protocol implementation provenance | REVIEW REQUIRED |
| Android connectivity | Reviewed server response handling, real discovery, direct/indirect peer paths, dual P/F TCP sockets, cancellation/timeouts and redaction before adding any external endpoint | NOT IMPLEMENTED |
| CI security | Separately reviewed secret-bearing workflow and trusted revision with protected environment approval; no use of the existing untrusted/label-triggered PR job for credentials | NOT CONFIGURED |

### Critical version-identifier finding

The [Soulseek.NET README](https://github.com/jpdillingham/Soulseek.NET#reserved-minor-version-ranges) reports minor versions **760–7,699,999 reserved to slskd**. The A0 test fixture uses **1201**, which falls in that reserved interval. It must remain an offline localhost fixture and **must not be reused for an actual BitChord Soulseek login**.

The current [Soulseek.NET LICENSE](https://github.com/jpdillingham/Soulseek.NET/blob/master/LICENSE) is GPL-3.0-only with additional terms under GPL section 7; clause 5 discusses a unique client-version identifier for Covered Software. This requirement should be assessed by a license reviewer for the chosen integration design. Do not assume the license for a third-party library automatically covers independently written Kotlin wire-format implementations, or the opposite. A version number appearing unclaimed is not proof it is globally unique. Check upstream reservations and existing clients again before any live traffic, document the decision, and use a dedicated identifier approved for this client.

Its README also describes excluded search phrases sent by the Soulseek server. Any future functionality that returns search results must honor the current exclusion policy, avoid default file sharing and never expose user library paths.

References: [Soulseek.NET README](https://github.com/jpdillingham/Soulseek.NET), [Soulseek.NET LICENSE](https://github.com/jpdillingham/Soulseek.NET/blob/master/LICENSE), [Seeker README](https://github.com/jackBonadies/SeekerAndroid), [GitHub environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).

## 2026-10-19 controlled fixture and modern protocol correction

- Generated a fresh **32,044-byte owner-authored PCM WAV**, SHA-256 `baa73555f873990bccd20e52808eefd2584567b1c43cd6d0d9cc1b78132f9c24` with offline deterministic `tools/a0_fixture.py` and four passing local Python tests. Peer setup: [controlled Nicotine+ uploader guide](2026-10-19-A0-controlled-peer-setup.md). Only the WAV may be shared; the reference manifest stays private. Account/peer setup is still not confirmed.
- The current A0 `direction=0` request plus outbound F is **legacy-local-fixture semantics**. [Nicotine+ protocol documentation](https://nicotine-plus.org/doc/SLSKPROTOCOL.html) requires modern `QueueUpload` P code 43 → peer-sent `TransferRequest(direction=1, size)` → local permission response → peer-initiated F and receiver's **8-byte initial FileOffset(0)**. The code now serializes `QueueUpload` and offset correctly in isolation (CI #865 GREEN), but lacks a real incoming F listener and server-driven peer negotiation. **Cannot claim end-to-end public-network readiness**.
- Modern `PeerInit` typically uses token zero, unlike the positive tokens in earlier fixtures. The protocol documentation permits experimental major 177, but selecting a globally compatible minor/version and checking the applicable third-party license constraints is pending. Never transmit fixture version 1201 as a BitChord identity.
- The PR currently cannot be assumed conflict-free against latest protected `main`. Integration remains separately coordinated.

## How to execute only after separate authorization

**Preferred environment:** run a reviewed test-only build of the actual BitChord APK on an authorized Android device or emulator; target an existing public peer only for the independently verified open-licensed original. An owner-controlled Nicotine+ peer is optional fallback, not mandatory. Keep server addresses and credentials out of repository artifacts.

**GitHub Actions is not automatically sufficient for P2P:** hosted runners can often make outbound connections but may not accept arbitrary incoming TCP peers, and their IP addresses/port mappings change. Peer direct reachability, indirect connect-back and IPv6/mobile network behavior are separate observations. If using GitHub Actions, add an entirely distinct, protected workflow on a trusted revision with explicit reviewer approval, restricted branches and read-only permissions. Never add secrets to the existing PR label-triggered Emulator Smoke, never use pull_request_target to execute PR-head code and never print passwords, MD5 login digests, peer addresses or raw packets.

Do not create that secret-bearing workflow or a real-network client in this A0 preparation-only step.

## Finite acceptance sequence — each step requires genuine evidence

1. **Identity + controlled start.** Confirm account/fixture ownership, selected unique client ID, vetted target environment and operational consent. Start the APK explicitly and connect only on command. Refuse any implicit background network login.
2. **Server login.** Prove the installed BitChord APK can authenticate to the actual Soulseek service under a bounded deadline, parse an actual server response and deliberately disconnect, without logging secrets or creating infinite retries. A localhost LoginResponse does not count.
3. **One search.** Query the exact filename of a source independently confirmed as freely licensed, with original SHA-256 and byte size. Review untrusted search results with the bounded A0 candidate gate; require a publicly accessible match by path basename and size. A public peer is not an authority for the file's rights or contents. If none match, report NO_MATCH; do not create RecordingIds from paths or names.
4. **Peer negotiation.** Prove direct or indirect reachability of the chosen public peer; follow modern QueueUpload (43) → uploader TransferRequest direction=1 → approved TransferResponse → incoming F and FileOffset(0). The old direction=0/outbound F fixture is NOT a modern interoperability proof. Peer tokens are correlation, never proof of identity.
5. **Open-licensed exact-file transfer.** Receive at most ONE candidate matching an independently reviewed open-licensed source. Stage only to app-private storage, bound transfer to 64 MiB, verify original size and independent SHA-256; discard any mismatch/partial artifact. Never publish into MediaStore/Catalog/Playback.
6. **Failure recovery.** Under explicit preapproval, test one peer denial, one deliberate interrupted/timeout transfer, cancel and reattempt if safe. Require no stale P/F sockets, retry loops, residual partial files, secrets or automatic sharing.
7. **Shutdown and reporting.** Stop voluntarily and release the listener/network resources. Record only build SHA, Android API, pseudonymous peer reference, direct/indirect result, stage statuses, bounded timings, bytes, known-fixture digest match and redacted errors. Never upload media or unredacted logcat/network captures to GitHub.

**PASS:** steps 1–5 genuinely complete within one authorized session on an installed BitChord APK, including exact checksum equality to the independently owned source file, with clean shutdown; failures in step 6 are handled safely.

**BLOCKED:** any missing consent, account, controlled peer, licensed fixture, unique identifier, reachable path, license review or infrastructure. No inferred or fabricated success.

## Reporting contract

- Explicit statuses: NOT_STARTED, NOT_AUTHORIZED, MISSING_CREDENTIALS, IDENTITY_CONFLICT, LOGIN_REJECTED, PEER_UNREACHABLE, SIZE_MISMATCH, HASH_MISMATCH, TIMEOUT, CANCELLED, SUCCESS.
- Collect: timestamp, build SHA, API level, environment, non-identifying stage name, elapsed time, byte count, fixture digest comparison and typed failure.
- Exclude: usernames, passwords, login MD5 hashes, peer/public IPs, filenames beyond synthetic test labels, raw search payloads, media bytes, device identifiers and full network trace.
- Default retention: no uploaded binary test audio, no unredacted device diagnostic bundle. Delete test artifacts when no longer needed.

## Decision handoff

Keep [PR #63](https://github.com/jssantogit/BitChord/pull/63) **OPEN/DRAFT/UNMERGED**, preserve its green local and emulator evidence, and leave the existing opt-in emulator workflow secretless and inactive. Do not start A1–A5, import third-party runtime/source code or synchronize the behind-main branch while other workstreams are integrating.

**Next gate:** public peer strategy is owner-approved; acquire a dedicated login through secure channels, approve a unique client identity and independently verifiable open-licensed original SHA-256, then implement/review minimum A0 modern P/F live transport with TDD. Find a qualifying public peer at execution time; if absent, return NO_MATCH or optionally use a controlled peer, never substitute copyrighted music. Until then report **REAL_NETWORK_NOT_EXERCISED** and **A0 LIVE INTEROPERABILITY BLOCKED**.
