"""DI2: read-only deterministic dependency DAG and unapproved merge proposals.

Input is an explicit first-party snapshot; never fetches or mutates GitHub.
The proposed manifest is a *candidate*, never a reviewed/approved batch.
"""
import argparse
import heapq
import json
import re
import sys

if __package__:
    from tools.integration_queue import validate_manifest
else:
    from integration_queue import validate_manifest


class PlannerInputError(ValueError):
    pass


_SHA = re.compile(r"^[0-9a-f]{40}$")
_REF = re.compile(r"^[A-Za-z0-9][A-Za-z0-9/_.-]{0,127}$")
_PATH = re.compile(r"^[A-Za-z0-9_./@+\-]+$")
_CHECK = {"success", "failure", "pending", "missing"}
_MERGE = {"clean", "dirty", "behind", "unknown", "blocked", "unstable", "draft"}


def _need(ok, message):
    if not ok:
        raise PlannerInputError(message)


def _safe_ref(value):
    return (isinstance(value, str) and bool(_REF.fullmatch(value))
            and ".." not in value and not value.endswith("/"))


def _safe_path(value):
    return (isinstance(value, str) and bool(_PATH.fullmatch(value))
            and not value.startswith("/")
            and all(part not in ("", ".", "..") for part in value.split("/")))


def plan(snapshot):
    """Classify a reviewed-input snapshot without elevating it to authorization."""
    _need(isinstance(snapshot, dict), "snapshot must be a JSON object")
    main = snapshot.get("main_sha")
    raw_prs = snapshot.get("pull_requests")
    _need(isinstance(main, str) and bool(_SHA.fullmatch(main)),
          "main must be pinned to 40 hex")
    _need(isinstance(raw_prs, list), "missing pull_requests")
    prs, by_ref = {}, {}

    for item in raw_prs:
        _need(isinstance(item, dict), "invalid PR observation")
        n, sha = item.get("number"), item.get("head_sha")
        head, base = item.get("head_ref"), item.get("base_ref")
        files = item.get("files")
        _need(type(n) is int and n > 0 and n not in prs, "invalid/duplicate PR")
        _need(isinstance(sha, str) and bool(_SHA.fullmatch(sha)), "invalid PR HEAD")
        _need(_safe_ref(head) and head != "main" and head not in by_ref,
              "unsafe or duplicate head_ref")
        _need(_safe_ref(base), "unsafe base_ref")
        _need(isinstance(files, list) and 0 < len(files) <= 100 and
              len(set(files)) == len(files) and
              all(_safe_path(f) for f in files), "unsafe files")
        _need(type(item.get("draft")) is bool, "missing draft truth")
        _need(item.get("check") in _CHECK, "invalid required check state")
        _need(item.get("mergeable") in _MERGE, "invalid mergeable state")
        prs[n] = {
            "number": n, "head_sha": sha, "head_ref": head, "base_ref": base,
            "files": sorted(files), "draft": item["draft"],
            "check": item["check"], "mergeable": item["mergeable"],
        }
        by_ref[head] = n

    # One exact base branch may encode a parent; names/titles are never guessed.
    parents, children, indegree = {}, {n: [] for n in prs}, {n: 0 for n in prs}
    blockers = {n: [] for n in prs}
    for n, p in prs.items():
        if p["draft"]:
            blockers[n].append("draft")
        if p["check"] != "success":
            blockers[n].append("ci_red" if p["check"] == "failure" else "ci_pending")
        if p["mergeable"] != "clean":
            blockers[n].append({
                "dirty": "conflict", "behind": "stale_base",
                "unknown": "mergeability_unknown",
                "blocked": "merge_policy_blocked", "unstable": "ci_unstable",
                "draft": "draft"}[p["mergeable"]])
        if p["base_ref"] != "main":
            parent = by_ref.get(p["base_ref"])
            blockers[n].append("stacked_base" if parent is not None else "missing_parent")
            if parent is not None:
                parents[n] = parent
                children[parent].append(n)
                indegree[n] += 1

    # Overlapping edits from independently based PRs warrant a manual preflight.
    numbers = sorted(prs)
    for i, left in enumerate(numbers):
        for right in numbers[i + 1:]:
            if (prs[left]["base_ref"] == "main" and
                    prs[right]["base_ref"] == "main" and
                    set(prs[left]["files"]) & set(prs[right]["files"])):
                for n in (left, right):
                    if "overlapping_files" not in blockers[n]:
                        blockers[n].append("overlapping_files")

    ready = [n for n in numbers if indegree[n] == 0]
    heapq.heapify(ready)
    order = []
    while ready:
        n = heapq.heappop(ready)
        order.append(n)
        for child in sorted(children[n]):
            indegree[child] -= 1
            if indegree[child] == 0:
                heapq.heappush(ready, child)
    cycle = sorted(set(prs) - set(order))
    for n in cycle:
        blockers[n].append("dependency_cycle")
    order.extend(cycle)

    rows = []
    for n in order:
        p = prs[n]
        rows.append({
            "number": n, "head_sha": p["head_sha"],
            "base_ref": p["base_ref"], "dependencies": (
                [parents[n]] if n in parents else []),
            "blockers": blockers[n],
            "changed_files": p["files"],
        })

    eligible = [n for n in order if not blockers[n]]
    batch = eligible[:3]
    proposal = {
        "version": 1, "repository": "jssantogit/BitChord", "target": "main",
        "items": [
            {"number": n, "expected_head_sha": prs[n]["head_sha"],
             "expected_base_ref": "main", "depends_on": None,
             "allowed_files": prs[n]["files"]}
            for n in batch
        ],
    }
    # Do not mistake an empty proposal for an executable manifest.
    if batch:
        validate_manifest(proposal, "jssantogit/BitChord")
    return {"version": 1, "main_sha": main, "ordered": rows,
            "manifest_proposal": proposal, "not_proposed": eligible[3:],
            "approval_required": True, "executable": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Offline, read-only PR planner")
    parser.add_argument("--input", required=True, help="snapshot JSON or '-'")
    args = parser.parse_args(argv)
    try:
        if args.input == "-":
            data = json.load(sys.stdin)
        else:
            with open(args.input, encoding="utf-8") as f:
                data = json.load(f)
        result = plan(data)
    except (ValueError, OSError) as exc:
        parser.exit(2, "PLANNER HALTED: " + type(exc).__name__ + "\n")
    json.dump(result, sys.stdout, ensure_ascii=False, sort_keys=True, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
