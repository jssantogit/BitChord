# A0 — Public peers first: open-license one-file acceptance (2026-10-20)

**Owner-approved strategy:** use files already offered by peers on Soulseek for the initial A0 live proof. **NO Nicotine+ installation or owner-controlled uploader is required.** The older [controlled-peer setup](2026-10-19-A0-controlled-peer-setup.md) is OPTIONAL FALLBACK only, not the default.

**State:** decision/documentation approved, initial selector implementation test-first; live Soulseek network NOT EXERCISED. Keep [PR #63](https://github.com/jssantogit/BitChord/pull/63) DRAFT and unmerged. A1–A5 excluded.

## Safety invariant: availability is not license, identity or integrity

A search result or offered file does **not** prove that the uploader owns the rights or that the bytes match a freely licensed source. A0 may use a public peer ONLY after an independently verified original establishes (1) license/right-to-download, (2) exact original filename, (3) exact byte count, (4) independently computed SHA-256. After receiving bytes the SHA-256 **must match** the independent original or the file is discarded. Mere filename/size equality admits a *candidate* for a single bounded proof, never marks the audio as licensed/verified.

New `A0OpenAudioCandidateGate` is a **read-only admission filter**. It requires explicit `rightsReviewedOutsideSoulseek` and a HTTPS source-page reference (human/source-review assertion, **not automated license verification**), whitelisted common open-license identifiers, a known SHA-256, reported size matching the original, bounded files no larger than **64 MiB**, an unlocked share/free slot and a nontraversing path. Its result does not contain peer usernames/IPs or untrusted filenames and does not touch sockets, disk, Playback or Catalog. **The missing modern transfer client is not solved by this gate.**

## Public-source discovery protocol

1. Choose a short WAV/FLAC/MP3 with verifiable public-domain/CC0/CC-BY/CC-BY-SA rights on an **independent official source page**. Respect attribution and other license terms. Do not assume a random song listed by Soulseek is freely licensed.
2. Retrieve the ORIGINAL from its authoritative source through a reviewed, authorized environment; calculate and retain SHA-256 and exact size OUTSIDE Soulseek. Confirm the source's actual license statement and avoid transcoded or renamed variants. This original may be held only temporarily for the test.
3. Using one dedicated Soulseek TEST account in an approved installed Android APK, issue **one narrow search** for the exact filename. No scraping, no mass discovery. Search results remain untrusted; evaluate candidates using `A0OpenAudioCandidateGate`, rejecting locked, wrong-size, unavailable or suspicious paths. If none qualify, record **NO_MATCH**; do not substitute copyrighted content.
4. Follow current [Nicotine+ protocol](https://nicotine-plus.org/doc/SLSKPROTOCOL.html): `QueueUpload` P (code 43), peer-initiated `TransferRequest` direction 1, explicit local permission, **incoming** F data channel and downloader raw uint64 `FileOffset(0)`. The A0 Kotlin branch currently has only the modern serializers and a **legacy localhost transport fixture**; the actual server discovery and modern incoming P/F runtime must be implemented/reviewed before this step.
5. Receive at most **one** candidate with deadlines, cancellation, output to private test staging only, exact original byte count and independent SHA-256. No MediaStore, Canonical Library, playback, sharing, or automatic retries. On timeout/size/hash mismatch, clean partial bytes/sockets and mark failure.
6. Disconnect and report only typed results and sanitized timings, build SHA and size. Avoid public peer names/IP addresses, account hashes, credential strings and raw network captures. If no usable public peer is available, the acceptance state is **NO_MATCH/BLOCKED**, not a successful download.

### Example independently licensed source (NOT yet a Soulseek candidate)

[Wikimedia Commons File:Ringingsound.wav](https://commons.wikimedia.org/wiki/File:Ringingsound.wav) lists a short (~27 KB) WAV credited to Kaga Sound, self-released under **CC0 1.0**. This is a promising *licensing reference only*. The original WAV's **SHA-256 has NOT been independently established in the test environment**, and **NO Soulseek peer has been shown to share the identical file**. Accordingly, eligibility currently remains **BLOCKED**. Do NOT insert placeholder SHA values or infer network availability from the Commons page. The independent source review should be repeated immediately before testing.

## Hard prerequisites and operational safeguards

- **User approved the public-peer-first direction.** It does not imply credentials or a completed live P2P test.
- Dedicated test login provided privately to a reviewed runtime; no secrets in GitHub PRs/issues/Notion/chat logs. Do not modify the existing label-triggered emulator workflow to accept Secrets, and never check out untrusted PR code in a secret-bearing `pull_request_target` job.
- Unique live client version identity checked against slskd-reserved **minor 760–7,699,999** (fixture `1201` is reserved, so never use it publicly); legal/provenance review where third-party GPL code or conditions apply.
- Securely reviewed implementation of server login/search, modern P request/response and inbound F with mobile socket semantics. A github-hosted emulator's inbound TCP reachability is NOT guaranteed; use a trusted environment suited to the chosen direct/indirect peer flow.
- A0 must not touch existing BitChord downloading, Song.videoId, MediaStore, Playback, Catalog, recommendations or user files. No merge; no A1–A5.
- Optional fallback: only if no independently verifiable open-licensed public peer is found, an **owner-controlled** peer may be configured following [the previous runbook](2026-10-19-A0-controlled-peer-setup.md). This is NOT a prerequisite for the approved primary experiment.

**Decision states:** `LOCAL_ANDROID_FEASIBILITY_PROVEN`; `PUBLIC_PEER_STRATEGY_APPROVED`; `LICENSED_ORIGINAL_SHA256_PENDING`; `SOULSEEK_PUBLIC_MATCH_NOT_CHECKED`; `REAL_NETWORK_NOT_EXERCISED`; `MODERN_P2P_RUNTIME_NOT_IMPLEMENTED`; `A1–A5_BLOCKED`.
