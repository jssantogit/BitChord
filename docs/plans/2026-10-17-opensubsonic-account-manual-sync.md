# OpenSubsonic account + manual catalog sync

## Goal

Expose the OpenSubsonic/Navidrome canonical-catalog adapter merged in PR #3 as a usable Android remote-library connection without coupling it to playback.

Target flow:

`Sources → OpenSubsonic account → Test connection / Sync now → canonical Room catalog`

## Scope

- one logical OpenSubsonic account;
- stable local `accountId` generated on first save;
- server URL and auth metadata persisted separately from credentials;
- API-key or username/password auth;
- change-only secret field: stored credentials are never displayed again;
- Test connection uses current editor draft and does not mutate the catalog;
- Sync now uses the saved connection and PR #3's transactional `OpenSubsonicCatalogSync`;
- last manual-sync result persisted as non-secret metadata;
- disconnect forgets connection metadata/credential without deleting canonical Recording/Release identity;
- Android Keystore AES/GCM credential storage with no plaintext fallback;
- credential ciphertext excluded from Android backup/device transfer;
- remote-library UI lives beside WebDAV/SMB, outside the ordered playback resolver source list.

## Non-goals

- PlaybackResolver / queue integration;
- direct-play or transcoding selection;
- background/periodic sync;
- multi-account UI;
- generic provider-account framework;
- canonical Artist/credit schema;
- provider ratings/playlists/lyrics/writes;
- migration of existing `AuthStore` / `EncryptedSharedPreferences` users.

## Persistence

Plain provider metadata is stored in provider-specific preferences and contains no credential fields. The credential is stored independently behind `OpenSubsonicCredentialStore`.

The Android implementation encrypts the credential with a non-exportable AES key from `AndroidKeyStore` using AES/GCM/NoPadding. No plaintext fallback is allowed. If the key cannot decrypt the stored value, the credential is treated as absent and the UI asks for re-entry.

A new account gets a random stable local `accountId`. Editing the same logical connection preserves it; forgetting the connection clears it, so a later setup receives a fresh ID.

## TDD gates

1. First save generates a local ID and later edits preserve it.
2. Plain account records never contain/echo credential material.
3. Changing auth mode requires a replacement credential.
4. Saved password/API-key state reconstructs `OpenSubsonicAccountConfig` correctly.
5. Test connection never invokes catalog sync and sanitizes thrown transport errors.
6. Manual sync delegates to PR #3's sync boundary and records a safe summary only after an attempt.
7. Failure metadata never persists provider/request exception text.
8. Forgetting the connection clears the credential and a later setup gets a fresh ID.
9. Final Android CI runs unit tests and `assembleDevDebug`; physical-device acceptance covers Keystore save/reopen plus a real server Test connection/Sync now.

## Security invariants

- Never persist password/API key in ordinary SharedPreferences, Room, logical `opensubsonic://` URIs, logs, docs, PR descriptions or Notion.
- Never display a stored secret back to the user.
- Never surface raw request URLs/query parameters in connection or sync errors.
- No plaintext fallback if Android Keystore is unavailable.
