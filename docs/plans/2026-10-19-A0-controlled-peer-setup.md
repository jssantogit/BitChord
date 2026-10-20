# A0 — OPTIONAL FALLBACK: Owner-controlled Soulseek peer setup (NO LIVE TEST EXECUTED)

**Status:** OPTIONAL FALLBACK ONLY, not required by the public-peer-first strategy approved 2026-10-20. Primary plan: [A0 Public Peers First](2026-10-20-A0-public-peer-first.md). Fixture READY; account, test peer and real network permission/implementation gates NOT VERIFIED. This is a preparation guide for [draft PR #63](https://github.com/jssantogit/BitChord/pull/63), not a network-enabled workflow or an external-download command.

## Recommended peer: Nicotine+ on a dedicated desktop test account

Use the current open-source [Nicotine+](https://nicotine-plus.org/doc/DOWNLOADS.html) on a PC *only as the owner-controlled uploader*. The **BitChord APK remains a single Android app**; a desktop peer is a test fixture, not a requirement of the end-user product.

1. Set up a **dedicated Soulseek peer test account** within Nicotine+ and confirm it can log in. Keep its password and username PRIVATE; do not place credentials in chat, GitHub, CI, issues, screenshots or Notion. Use a *different dedicated account* for future BitChord Android test login; don't run both sessions with the same username.
2. Create a new, **empty folder solely for the A0 fixture** (never point Shares at Music, Downloads, Home or existing personal libraries). Place ONLY `bitchord-a0-owned-synthetic-8000hz.wav` inside it. Keep the fixture SHA manifest OUTSIDE this shared folder.
3. Obtain the test-only WAV supplied in this chat, **or** recreate it offline from the repo with:
   ```bash
   python3 tools/a0_fixture.py --generate --directory /path/to/PRIVATE-a0-fixture
   python3 tools/a0_fixture.py --verify --directory /path/to/PRIVATE-a0-fixture
   python3 -m unittest discover -s tests -p test_a0_fixture.py
   ```
   Then COPY only the WAV to the isolated shared folder. The generator is deterministic and refuses to overwrite an existing source fixture.
4. In Nicotine+ **Settings → Shares**, add only that isolated folder, rescan if necessary and verify no unrelated shared directories. Where supported, restrict visibility to a designated trusted buddy/test account. If buddy-only visibility hides the WAV from global search, test by browsing the peer shares through the approved test username instead; never make a personal library public to satisfy the test.
5. Confirm the peer's network/listening status in Nicotine+ and leave it online for the narrow test window. Inbound reachability is not guaranteed on carrier CGNAT or GitHub-hosted runners; an approved indirect peer connection may be necessary. Do not open arbitrary ports or disable the OS firewall without reviewing the intended test topology.
6. Report back **only** these non-sensitive statuses: "Peer test account ONLINE", "Test WAV visible only in intended share", "Second test account AVAILABLE", and the operating system you use. Do not send passwords or actual test usernames here.

### Independent reference fixture

Filename: `bitchord-a0-owned-synthetic-8000hz.wav`  
Size: **32,044 bytes**  
SHA-256: `baa73555f873990bccd20e52808eefd2584567b1c43cd6d0d9cc1b78132f9c24`  
Format: PCM WAV, 2 seconds, mono, 8 kHz, 16-bit. The audio was synthesized locally using integer samples and is authorized solely as an owner-generated test fixture. No third-party music is included.

The manifest is an **out-of-band trusted reference** and MUST NOT be used as part of the peer's file listing. Soulseek search metadata does not provide a trustworthy content digest.

## Compatibility issue discovered before attempting the public network

The [Nicotine+ Soulseek protocol documentation](https://nicotine-plus.org/doc/SLSKPROTOCOL.html) describes the **modern QueueUpload** download flow:

- Request the test peer's file through `P` **QueueUpload code 43** with its remote filename.
- The **peer** decides when to initiate an upload, sends `TransferRequest code 40 direction=1` including the file size, and awaits the receiver's `TransferResponse code 41`.
- The uploading peer establishes the **incoming F connection** with the receiver. The receiver then sends an **eight-byte raw little-endian offset 0** and reads the WAV bytes until complete, closing F at completion.
- The modern peer connection setup requires actual server `GetPeerAddress` / `ConnectToPeer` and direct-or-indirect handling, and current documentation describes a `PeerInit` token of zero. Client versions and actual field behavior must be checked against the controlled peer rather than inferred from local fixtures.

The A0 localhost experiment had primarily tested the **older direction=0 TransferRequest** and an **outbound F socket** with synthetic positive connection tokens. That remains legitimate fixture coverage but **must not be mistaken for modern Nicotine+ interoperability**. The new `A0SoulseekPeerWireProbe.queueUploadRequest()` and `initialDownloadOffset()` encode isolated modern wire primitives, with unit tests; they are NOT a complete listener/transport. Do not try to run live until the Android client's modern incoming-P/F path, valid client identity, secret handling and official server login are implemented and reviewed.

## Remaining operational gates

**BLOCKED** until: explicit external-network execution approval, two test accounts, one owner-controlled peer online with only the WAV share, approved client version/major-minor identity (the previous fixture minor `1201` is slskd-reserved), reviewed secure execution environment and actual real-transport code. The PR stays **DRAFT / NOT MERGED**. No external Soulseek traffic or account setup was performed by ChatGPT in this step.

References: [Nicotine+ downloads](https://nicotine-plus.org/doc/DOWNLOADS.html), [Nicotine+ protocol](https://nicotine-plus.org/doc/SLSKPROTOCOL.html), [Soulseek.NET library and license](https://github.com/jpdillingham/Soulseek.NET).
