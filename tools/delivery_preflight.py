"""DI3: bounded, GET-only parallel validation of an unapproved DI2 plan.

No write endpoints, no side effects, no synchronization and no CI bypass.
Read errors are typed and redacted; main is checked before AND after reads.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


class PreflightInputError(ValueError):
    pass


_SHA = re.compile(r"^[0-9a-f]{40}$")
_REPO = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
_REQUIRED = "Android · test and build"


def _need(ok, msg):
    if not ok:
        raise PreflightInputError(msg)


class GitHubReadOnly:
    """GitHub adapter restricted to GET and the explicitly selected repository."""

    def __init__(self, repository, token=None):
        _need(isinstance(repository, str) and bool(_REPO.fullmatch(repository)),
              "unsafe repository identifier")
        self.repository = repository
        self.token = token
        self.root = "https://api.github.com/repos/" + repository

    def _get(self, path):
        req = Request(self.root + path, method="GET", headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            **({"Authorization": "Bearer " + self.token} if self.token else {}),
        })
        try:
            with urlopen(req, timeout=15) as response:
                return json.load(response)
        except (HTTPError, URLError, OSError, ValueError) as exc:
            # No token, URL, server error body or raw exception printed.
            raise PreflightInputError("read_error") from None

    def main_sha(self):
        return self._get("/branches/main")["commit"]["sha"]

    def pr(self, n):
        _need(type(n) is int and n > 0, "unsafe PR number")
        data = self._get("/pulls/" + str(n))
        return {
            "head_sha": data["head"]["sha"],
            "base_ref": data["base"]["ref"],
            "base_sha": data["base"]["sha"],
            "draft": data["draft"],
            "mergeable": data["mergeable_state"],
            "state": data["state"],
            "merged": data["merged"],
            "head_repo": (data["head"].get("repo") or {}).get("full_name"),
        }

    def files(self, n):
        _need(type(n) is int and n > 0, "unsafe PR number")
        data = self._get("/pulls/" + str(n) + "/files?per_page=100")
        _need(isinstance(data, list) and len(data) < 100,
              "file list incomplete or too large")
        return [x["filename"] for x in data]

    def check(self, sha):
        _need(isinstance(sha, str) and bool(_SHA.fullmatch(sha)),
              "unsafe check SHA")
        runs = self._get("/commits/" + sha + "/check-runs?per_page=100")
        matched = [r for r in runs["check_runs"] if r.get("name") == _REQUIRED]
        if len(matched) != 1:
            return "missing"
        row = matched[0]
        return (row.get("conclusion") if row.get("status") == "completed"
                else "pending")


def _inspect(row, reader, main_sha):
    number = row["number"]
    blockers = list(row["blockers"])
    try:
        p = reader.pr(number)
        files = reader.files(number)
        check = reader.check(row["head_sha"])
        if p.get("state") != "open" or p.get("merged") is not False:
            blockers.append("pr_closed")
        if p.get("head_repo") != getattr(reader, "repository", None):
            blockers.append("untrusted_head_repository")
        if p.get("head_sha") != row["head_sha"]:
            blockers.append("head_drift")
        if p.get("base_ref") != row["base_ref"]:
            blockers.append("base_drift")
        if p.get("base_ref") == "main" and p.get("base_sha") != main_sha:
            blockers.append("stale_base")
        if sorted(files) != sorted(row["changed_files"]):
            blockers.append("file_drift")
        if p.get("draft") is not False:
            blockers.append("draft")
        if p.get("mergeable") != "clean":
            blockers.append("not_clean")
        if check != "success":
            blockers.append("ci_red" if check == "failure" else "ci_unverified")
    except Exception:
        # Fail closed and do not echo untrusted exceptions.
        blockers.append("read_error")
    return {"number": number, "head_sha": row["head_sha"],
            "blockers": list(dict.fromkeys(blockers)),
            "verified_candidate": not blockers}


def parallel_preflight(planner_report, reader, max_workers=3):
    """Run bounded concurrent reads and invalidate everything on main drift."""
    _need(type(max_workers) is int and 1 <= max_workers <= 4,
          "workers must be between one and four")
    _need(isinstance(planner_report, dict) and
          planner_report.get("version") == 1 and
          planner_report.get("approval_required") is True and
          planner_report.get("executable") is False, "untrusted planner report")
    sha, rows = planner_report.get("main_sha"), planner_report.get("ordered")
    _need(isinstance(sha, str) and bool(_SHA.fullmatch(sha)),
          "untrusted main SHA")
    _need(isinstance(rows, list) and len(rows) <= 100, "invalid plan rows")
    seen = set()
    for row in rows:
        _need(isinstance(row, dict), "invalid row")
        n, head = row.get("number"), row.get("head_sha")
        _need(type(n) is int and n > 0 and n not in seen, "duplicate PR")
        seen.add(n)
        _need(isinstance(head, str) and bool(_SHA.fullmatch(head)), "invalid HEAD")
        _need(isinstance(row.get("blockers"), list) and
              all(isinstance(b, str) for b in row["blockers"]), "invalid blockers")
        _need(isinstance(row.get("changed_files"), list) and
              isinstance(row.get("base_ref"), str), "missing file/base evidence")

    try:
        initial_main = reader.main_sha()
    except Exception:
        initial_main = None
    if initial_main != sha:
        return {"version": 1, "main_sha": sha, "main_drift": True,
                "approval_required": True, "executable": False,
                "results": [{"number": row["number"], "head_sha": row["head_sha"],
                             "blockers": ["main_drift"],
                             "verified_candidate": False} for row in sorted(rows, key=lambda x: x["number"])]}

    results = []
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        pending = [pool.submit(_inspect, row, reader, sha) for row in rows]
        for future in as_completed(pending):
            results.append(future.result())
    try:
        drifted = reader.main_sha() != sha
    except Exception:
        drifted = True
    if drifted:
        for item in results:
            if "main_drift" not in item["blockers"]:
                item["blockers"].append("main_drift")
            item["verified_candidate"] = False
    results.sort(key=lambda x: x["number"])
    return {"version": 1, "main_sha": sha, "main_drift": drifted,
            "approval_required": True, "executable": False,
            "results": results}


def main(argv=None):
    p = argparse.ArgumentParser(description="Read-only, bounded live GH preflight")
    p.add_argument("--plan", required=True, help="DI2 JSON plan")
    p.add_argument("--repo", required=True, help="owner/repository")
    p.add_argument("--workers", type=int, default=3)
    a = p.parse_args(argv)
    try:
        with open(a.plan, encoding="utf-8") as source:
            report = json.load(source)
        result = parallel_preflight(
            report, GitHubReadOnly(a.repo, os.environ.get("GITHUB_TOKEN")),
            max_workers=a.workers)
    except (OSError, ValueError) as exc:
        p.exit(2, "PREFLIGHT HALTED: " + type(exc).__name__ + "\n")
    json.dump(result, sys.stdout, ensure_ascii=False, sort_keys=True, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
