"""Read-only comparisons of Gradle cache evidence from *two* main builds.

This is intentionally not cache provenance verification or an actionable
cache-policy recommendation. Jobs with different inputs/SHAs, despite sharing
a restore-key workflow family, cannot prove which task outputs were reused.
"""
import re

class LineageError(ValueError):
    pass

_SHA = re.compile(r"^[0-9a-f]{40}$")
_TASKS = frozenset({
    "ksp_main", "kotlin_main", "ksp_tests", "kotlin_tests",
    "android_tests", "shared_kotlin",
})
_STATES = frozenset({"executed", "from_cache", "up_to_date", "no_source", "skipped"})
_FAMILIES = frozenset({"android_workflow", "build_smoke_apk_workflow", "other_workflow"})
_WRITES = frozenset({"written", "read_only", "unobserved"})


def _require(value):
    if not value:
        raise LineageError("invalid_or_unsupported_cache_lineage_evidence")


def _integer(x, minimum=0):
    _require(type(x) is int and x >= minimum)


def _parse_record(record):
    _require(type(record) is dict and type(record.get("version")) is int and record["version"] == 1)
    sha = record.get("head_sha")
    _require(type(sha) is str and bool(_SHA.fullmatch(sha)))
    _require(type(record.get("lane")) is str and record["lane"] in ("main", "pr"))
    _require(type(record.get("cache_restore_status")) is str and record["cache_restore_status"] in ("hit", "unobserved"))
    family = record.get("cache_restore_workflow_family")
    _require(family is None or (type(family) is str and family in _FAMILIES))
    _require((family is not None) == (record["cache_restore_status"] == "hit"))
    _require(type(record.get("write_policy")) is str and record["write_policy"] in _WRITES)
    for field in ("gradle_seconds", "actionable_tasks", "executed_tasks",
                  "from_cache_tasks"):
        _integer(record.get(field), 1 if field in ("gradle_seconds", "actionable_tasks") else 0)
    _require(record["executed_tasks"] + record["from_cache_tasks"] <=
             record["actionable_tasks"])
    tasks = record.get("tasks")
    _require(type(tasks) is dict and tasks.keys() == _TASKS)
    _require(all(state is None or (type(state) is str and state in _STATES) for state in tasks.values()))
    return sha


def _response(status, family=None, from_cache=None, executed=None, wall=None):
    return {
        "version": 1, "status": status,
        "cache_workflow_family": family,
        "from_cache_task_delta": from_cache,
        "executed_task_delta": executed,
        "gradle_wall_seconds_delta": wall,
        "cross_revision_output_reuse_proven": False,
        "speedup_attributable_to_cache": False,
        "safe_to_promote_pr_cache": False,
    }


def compare_main_cache_evidence(older, newer):
    """Typed measurement of deltas only; no causal performance or trust claim."""
    first_sha = _parse_record(older)
    second_sha = _parse_record(newer)
    _require(first_sha != second_sha)

    # A cache from a PR merge ref is not a main-cache producer. Keep these
    # unrelated observations incomparable rather than extrapolating from hits.
    if (older["lane"] != "main" or newer["lane"] != "main" or
        older["write_policy"] != "written" or newer["write_policy"] != "written" or
        older["cache_restore_workflow_family"] != "android_workflow" or
        newer["cache_restore_workflow_family"] != "android_workflow"):
        return _response("incomparable")

    if any(older["tasks"][name] is None or newer["tasks"][name] is None
           for name in _TASKS):
        return _response("incomplete_task_evidence")

    # Matching workflow family does not prove creator/branch identity, matching
    # input hashes, incremental compiler state, or byte-level artifact safety.
    return _response(
        "observations_only",
        family="android_workflow",
        from_cache=newer["from_cache_tasks"] - older["from_cache_tasks"],
        executed=newer["executed_tasks"] - older["executed_tasks"],
        wall=newer["gradle_seconds"] - older["gradle_seconds"],
    )
