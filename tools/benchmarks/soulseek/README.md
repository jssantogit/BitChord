# Soulseek pre-download benchmark — reproducible developer harness

This folder is for **offline evaluation**, not part of BitChord's Android APK.
**No peer traffic, Soulseek passwords, remote paths or audio files are needed**
to run these examples.

## What is actually available

- `app/src/test/resources/soulseek/synthetic-cases.jsonl`: **18 invented cases**
  (3 each in ordinary/version/edition/artist/ambiguity/technical categories).
  Their `truth` fields are constructed for unit testing; these are **NOT**
  a real-world precision benchmark and not the target 120 independent intents.
- `evaluate.py`: input validation, blinding, per-case SHA-256 parity,
  conservative metrics with explicit UNKNOWN denominators, paired case counts
  and separate strata. Comparisons abort for synthetic data by default.
- `run_flux_baseline.py`: calls the **real** `CandidateScoringService` in a
  clean local checkout of Noqlen Flux at pinned
  `3e6fa935fc139ed80e4e75ae3aa7c88ebd36747d` / `default_v1`, with
  no dependency on a running slskd instance. Its `rank_first` interpretation
  of the score is **not** Flux auto-download behavior.
- `A0CanonicalBenchmarkReplayTest`: the existing Kotlin
  `preflight-v4` scorer over the **same** JSONL, emitting blinded offer-ID
  rankings in the Android module's `build/benchmark/soulseek` folder.
  The current preflight is *not* the planned MusicBrainz identity engine.

## Run on a developer machine

Requirements: Python 3.10+, Git and the BitChord Android Gradle environment.
The Flux checkout is a **developer-only comparator** — no Flux/slskd daemon
or Python runtime is bundled in the app.

```bash
python3 -m unittest discover -s tools/benchmarks/soulseek -p 'test_*.py' -v
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest

# An independent checkout is required for the pinned Flux source:
git clone https://github.com/jssantogit/noqlen-flux-core.git /tmp/bitchord-flux
git -C /tmp/bitchord-flux checkout 3e6fa935fc139ed80e4e75ae3aa7c88ebd36747d

python3 tools/benchmarks/soulseek/evaluate.py \
  --cases app/src/test/resources/soulseek/synthetic-cases.jsonl \
  --export-blind /tmp/bitchord-soulseek-blind.jsonl

python3 tools/benchmarks/soulseek/run_flux_baseline.py \
  --blind /tmp/bitchord-soulseek-blind.jsonl \
  --flux-root /tmp/bitchord-flux \
  --output /tmp/bitchord-flux-ranked.jsonl

# Gradle writes app/build/benchmark/soulseek/bitchord-preflight-v4.jsonl
python3 tools/benchmarks/soulseek/evaluate.py \
  --cases app/src/test/resources/soulseek/synthetic-cases.jsonl \
  --left app/build/benchmark/soulseek/bitchord-preflight-v4.jsonl \
  --right /tmp/bitchord-flux-ranked.jsonl --allow-synthetic
```

The comparator checks Git HEAD and clean-tree state before importing any
Flux source. Predictions carry the **same blinded input SHA-256** and must
report **all observed offer IDs**; rankings may omit ineligible offers. Files
provided to either ranker exclude `truth` and `truth_evidence`. Only the
final independent evaluator sees ground-truth labels.

## Manifest schema v1

One JSON object per line:

- `schema_version: 1`, `case_id` opaque ASCII, `dataset_kind` one of
  `synthetic` / `real`, `split` one of `development` / `holdout`,
  `stratum` one of `ordinary`, `versions`, `editions`, `artists`,
  `ambiguous`, `quality` or `empty`.
- `intent` artist/title, optional requested album/edition, version rules,
  selected technical profile and canonical ID context when independently known.
- `observations[]` opaque `offer_id`, peer-declared (untrusted) filename,
  directory, claimed artist/title/album, format and file attributes.
- `truth`: EXACT_EDITION, CORRECT_RECORDING_EDITION_UNKNOWN,
  WRONG_EDITION_VERSION, WRONG_ARTIST_OR_RECORDING,
  TECHNICALLY_INELIGIBLE, UNKNOWN_UNVERIFIABLE. Each labelled offer needs
  `truth_evidence`. `NO_OFFER` is a **search outcome**, not a file label.
- The *input hash* excludes labels and evidence; changing only the gold
  annotation must not alter the blinded claim manifest fingerprint.

Strict limits apply to the number of records and offers. Validation rejects
mixed synthetic/real comparison, duplicated IDs and predicted populations
that differ. Unresolved labels are excluded from precision denominators but
**counted separately**; an abstention with a verified exact offer is reported
rather than silently treated as correct.

## Interpreting results

1. `conditional exact_precision_at_1` is based on independently settled
   top recommendations only. ALWAYS report unknown recommendations alongside.
2. `correct_coverage` and `exact_recall_at_5` use only intents with a
   verified exact offer **among the exact input offers**.
3. `recommendation_coverage` reflects the scorer's policy: Flux `rank_first`
   versus BitChord `strong_only`; do not present this as matching automatic
   transfer behavior.
4. `paired` win/loss/tie is a **coarse categorical comparison**, not a
   statistical significance test or release gate. UNKNOWN pairs are excluded
   and counted.
5. No result on this invented sample supports the claim **BitChord beats Flux**.
   Real intents require blinded, independently adjudicated labels, permission
   to observe remote offers and a separately authorized Android live smoke.

## Critical boundaries

- **No automatic transfers**: issue #184 reserves any future opt-in. The
  benchmark does not call P/F or change the Android user-facing selection.
- **No guarantee of source edition/master** from MusicBrainz relationships or
  peer-declared metadata alone. A same-recording ID may exist on remasters.
- **Holdout is sealed**: default evaluator refuses to compare holdout rows.
  Unsealing requires an explicit, separately reviewed final-evaluation protocol.
- **Privacy**: output contains aggregate counts, model versions and opaque
  IDs only. Put real private manifests under access control, never commit them
  or use normal user-visible diagnostics as a vehicle for metadata collection.

See `docs/superpowers/specs/2026-10-25-soulseek-pre-download-benchmark.md`
for complete sampling, evidence, adjudication and confidence interval rules.
