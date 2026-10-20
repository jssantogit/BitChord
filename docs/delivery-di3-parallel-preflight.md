# Delivery Infrastructure v2 — DI3: Parallel Preflight v1

The preflight consumes a **DI2 plan snapshot** and checks its exact SHA/file/check evidence again against live first-party GitHub REST GET endpoints. It never submits workflow runs, writes refs, synchronizes branches, approves or merges PRs, and never makes requests to any host other than `api.github.com`.

```bash
python3 -m unittest tests.test_delivery_preflight -v
# Optional public repository reads; token only for read rate limit and visibility
python3 tools/delivery_preflight.py --plan plan.json --repo jssantogit/BitChord --workers 3
```

### Behavior

- Up to four concurrent workers; uses independent GETs to read exact PR HEAD/base, changed-file list and required `Android · test and build` check. This is concurrency in **preparation**, not merge concurrency.
- `main` SHA is checked at the start and end. Any change invalidates all candidates, even if the individual PRs looked healthy.
- A stale HEAD/base, stale main base SHA, PR draft, mergeability uncertainty, file drift, or RED/missing check is explicitly blocked; remote GET failure becomes only `read_error` — no token/error body/URLs echoed.
- Paged or potentially truncated changed-file lists (100+) are refused. Actual token must be least-privileged read access. A preflight result always contains `approval_required: true` and `executable: false`.
- Results are sorted by PR number regardless of worker completion order; tests include a barrier demonstrating actual overlapping read executions, injected drift and secret-bearing exception redaction.
- No speculative merge succeeds based on file non-overlap alone. Passing preflight is **not** approval or protected GitHub `mergeable_state` guarantee after another PR merges.

### Integration boundary

Do not send preflight output directly to the existing `mode=execute` controller. Operator must review a newly pinned manifest, current main SHA and full PR diffs and then explicitly use `integration-apply`. DI4 may later formalize durable checkpoints. No GitHub repo settings, secrets, CI required context, APK policy or protected `main` rules changed in DI3.

### Known limitations

- GitHub REST `mergeable_state` can temporarily be `unknown`; preflight blocks rather than inventing certainty.
- Comparing a PR's files to its own base is only a preliminary compatibility assessment; stacked children need parent normalization before execution.
- Cost and latency improvements must be measured with live samples; parallel GETs are not assumed to make Android tests faster.
