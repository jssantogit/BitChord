# A0 — Real Soulseek interoperability acceptance gate

**Status: PREPARED, BLOCKED FOR REAL-NETWORK EXECUTION.** This document is an operational acceptance contract only, not permission to use credentials or public Soulseek endpoints.

**Scope:** BitChord one-APK Android (minSdk 26, targetSdk 36), Kotlin feasibility branch in [draft PR #63](https://github.com/jssantogit/BitChord/pull/63), with no separate Docker, Termux, server, Seeker APK, .NET runtime or background host. Preserve Playback, Catalog, Listening, Diagnostics and the legacy Song.videoId downloader; no merge and no A1–A5.

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
| Owner authorization | Explicit separate approval to use the actual Soulseek network for one bounded interoperability experiment, with the approved device/runner and scope | NOT GIVEN |
| Dedicated account | Test-only Soulseek account, never written into a chat, PR, CI logs or Notion page | NOT PROVIDED |
| Controlled peer | Explicitly owner-operated Soulseek-compatible peer with a stable test username, expected session time and agreed content rights | NOT PROVIDED |
| Test content | Self-generated or properly licensed PCM WAV intentionally shared by that peer; expected exact size and SHA-256 recorded privately, independently of untrusted search metadata | NOT PROVIDED |
| Client identity | Review and reserve a demonstrably distinct minor-version identifier for the live BitChord experiment; do not transmit a fixture identifier | NOT APPROVED |
| Licensing | Review GPL-3.0-only Soulseek.NET and Seeker additional terms if incorporating their code/binaries; keep original notices and obligations as applicable, and inspect Kotlin protocol implementation provenance | REVIEW REQUIRED |
| Android connectivity | Reviewed server response handling, real discovery, direct/indirect peer paths, dual P/F TCP sockets, cancellation/timeouts and redaction before adding any external endpoint | NOT IMPLEMENTED |
| CI security | Separately reviewed secret-bearing workflow and trusted revision with protected environment approval; no use of the existing untrusted/label-triggered PR job for credentials | NOT CONFIGURED |

### Critical version-identifier finding

The [Soulseek.NET README](https://github.com/jpdillingham/Soulseek.NET#reserved-minor-version-ranges) reports minor versions **760–7,699,999 reserved to slskd**. The A0 test fixture uses **1201**, which falls in that reserved interval. It must remain an offline localhost fixture and **must not be reused for an actual BitChord Soulseek login**.

The current [Soulseek.NET LICENSE](https://github.com/jpdillingham/Soulseek.NET/blob/master/LICENSE) is GPL-3.0-only with additional terms under GPL section 7; clause 5 discusses a unique client-version identifier for Covered Software. This requirement should be assessed by a license reviewer for the chosen integration design. Do not assume the license for a third-party library automatically covers independently written Kotlin wire-format implementations, or the opposite. A version number appearing unclaimed is not proof it is globally unique. Check upstream reservations and existing clients again before any live traffic, document the decision, and use a dedicated identifier approved for this client.

Its README also describes excluded search phrases sent by the Soulseek server. Any future functionality that returns search results must honor the current exclusion policy, avoid default file sharing and never expose user library paths.

References: [Soulseek.NET README](https://github.com/jpdillingham/Soulseek.NET), [Soulseek.NET LICENSE](https://github.com/jpdillingham/Soulseek.NET/blob/master/LICENSE), [Seeker README](https://github.com/jackBonadies/SeekerAndroid), [GitHub environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).

## How to execute only after separate authorization

**Preferred environment:** run a reviewed test-only build of the actual BitChord APK on an authorized Android device or emulator. A trusted non-public peer should be controlled by the project owner and should share only the synthetic test WAV. Keep server addresses and credentials out of repository artifacts.

**GitHub Actions is not automatically sufficient for P2P:** hosted runners can often make outbound connections but may not accept arbitrary incoming TCP peers, and their IP addresses/port mappings change. Peer direct reachability, indirect connect-back and IPv6/mobile network behavior are separate observations. If using GitHub Actions, add an entirely distinct, protected workflow on a trusted revision with explicit reviewer approval, restricted branches and read-only permissions. Never add secrets to the existing PR label-triggered Emulator Smoke, never use pull_request_target to execute PR-head code and never print passwords, MD5 login digests, peer addresses or raw packets.

Do not create that secret-bearing workflow or a real-network client in this A0 preparation-only step.

## Finite acceptance sequence — each step requires genuine evidence

1. **Identity + controlled start.** Confirm account/fixture ownership, selected unique client ID, vetted target environment and operational consent. Start the APK explicitly and connect only on command. Refuse any implicit background network login.
2. **Server login.** Prove the installed BitChord APK can authenticate to the actual Soulseek service under a bounded deadline, parse an actual server response and deliberately disconnect, without logging secrets or creating infinite retries. A localhost LoginResponse does not count.
3. **One search.** Query an unambiguous identifier for the single owned WAV, receive real search results, and verify that the selected candidate belongs to the controlled peer. Reject unrelated or malformed candidates; do not create RecordingIds from paths or names.
4. **Peer negotiation.** Prove direct or indirect peer reachability; record the selected P connection path, explicit Download request, actual permission, exact byte count, transfer token and the separate F channel. Peer token is correlation data, never proof of peer authenticity.
5. **Owned data transfer.** Transfer exactly the approved synthetic WAV. Stage only to app-private storage, bound transfer size, validate the independent reference hash, ensure a complete output and no partial artifact. Do not publish into MediaStore/Catalog/Playback.
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

**Next gate:** get explicit owner/orchestration authorization for the narrow live-network proof and provide an approved dedicated account, controlled peer and authorized test WAV through appropriate secure channels; then implement and review only the minimum A0 live transport necessary, with TDD and separate network acceptance. Until then report **REAL_NETWORK_NOT_EXERCISED** and **A0 LIVE INTEROPERABILITY BLOCKED**.
