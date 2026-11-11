# MusicBrainz #209 — bounded positive video-only source proof

This is a focused **source-contract** check, not a UI/smoke test. It must not
alter canonical identity, expose providers' media assets, or infer that an
unavailable MusicBrainz result is a release missing from the catalogue.

Official MusicBrainz pages identify a one-track NewJeans release:
- Release `c3d3777d-2d77-4d6f-9473-ec480b66c27a`, **New Jeans (music video)**,
  Official / Digital Media, 2023-07-07:
  https://musicbrainz.org/release/c3d3777d-2d77-4d6f-9473-ec480b66c27a
- ReleaseGroup `cd544c25-e9c4-4e52-a146-0761ef6e006f`, type **Other**:
  https://musicbrainz.org/release-group/cd544c25-e9c4-4e52-a146-0761ef6e006f
- Recording `2c124b3c-e76b-4046-9980-e88c5ffa4ab2`,
  classified as **Video** on the official entity page:
  https://musicbrainz.org/recording/2c124b3c-e76b-4046-9980-e88c5ffa4ab2
- Edition-specific Track `38f39d28-3f1d-40ce-b0e2-f77ea6d5eba7`.

The targeted workflow runs **8 offline cases** then at most two logical WS/2
lookup operations (Release with `inc=recordings+release-groups`; Recording
by MBID). It has a **hard ceiling of four actual HTTP attempts**, including
429/503 retries, spaced at least 1.1 seconds. It checks exact IDs, Official
status, complete tracks, and *positive* nested `recording.video` evidence;
a separate Recording lookup corroborates the flag.

If Digital Media is present but video flags are missing, the source result
is **INCONCLUSIVE**, never AUDIO. A 503/timeout becomes **ERROR**, never
NOT_FOUND. A contradiction becomes **FAIL**. An observed PASS validates the
API shape for this one release, not every video edition or the Android UI.

Use the retained JSON artifact to report the exact outcome before claiming
live video-positive verification. Never silently retry broad catalog probes
just to turn an upstream-throttled gate green.
