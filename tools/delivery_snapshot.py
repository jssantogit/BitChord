"""DI2: collect an exact, read-only first-party GitHub PR snapshot.

This module cannot write to GitHub, update a branch or dispatch actions.
Unexpected input, pagination overflow or ref drift fails closed.
"""
import argparse
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from tools.delivery_planner import PlannerInputError, plan


class SnapshotError(ValueError):
    pass


_REPO = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
_SHA = re.compile(r"^[0-9a-f]{40}$")
_REQUIRED = "Android · test and build"


def _need(condition, message):
    if not condition:
        raise SnapshotError(message)


class GitHubSnapshotReader:
    """Fixed-host, fixed-repository GET adapter without mutation methods."""

    def __init__(self, repository, token=None):
        _need(isinstance(repository, str) and bool(_REPO.fullmatch(repository)),
              "invalid repository")
        self.repository = repository
        self.token = token
        self.root = "https://api.github.com/repos/" + repository

    def _get(self, path):
        _need(isinstance(path, str) and path.startswith("/") and
              not path.startswith("//"), "unsafe path")
        req = Request(self.root + path, method="GET", headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            **({"Authorization": "Bearer " + self.token} if self.token else {}),
        })
        try:
            with urlopen(req, timeout=15) as response:
                return json.load(response)
        except (HTTPError, URLError, OSError, ValueError):
            raise SnapshotError("read_error") from None

    def main_sha(self):
        return self._get("/branches/main")["commit"]["sha"]

    def open_prs(self):
        rows = []
        for page in range(1, 12):
            chunk = self._get("/pulls?state=open&per_page=100&page=" + str(page))
            _need(isinstance(chunk, list) and len(chunk) <= 100,
                  "invalid PR page")
            rows.extend({
                "number": x["number"],
                "head_sha": x["head"]["sha"],
                "head_ref": x["head"]["ref"],
                "base_ref": x["base"]["ref"],
                "draft": x["draft"],
            } for x in chunk)
            if len(chunk) < 100:
                _need(len(rows) <= 1000, "PR count exceeds safe limit")
                return rows
        raise SnapshotError("PR pagination exceeds safe limit")

    def pr(self, number):
        _need(type(number) is int and number > 0, "invalid PR number")
        p = self._get("/pulls/" + str(number))
        return {"number": p["number"], "state": p["state"],
                "merged": p["merged"],
                "head_sha": p["head"]["sha"],
                "head_ref": p["head"]["ref"],
                "head_repo": (p["head"].get("repo") or {}).get("full_name"),
                "base_ref": p["base"]["ref"], "draft": p["draft"],
                "mergeable": p["mergeable_state"] or "unknown"}

    def files(self, number):
        _need(type(number) is int and number > 0, "invalid PR number")
        result = []
        for page in (1, 2):
            chunk = self._get("/pulls/" + str(number) +
                              "/files?per_page=100&page=" + str(page))
            _need(isinstance(chunk, list) and len(chunk) <= 100,
                  "invalid file page")
            result.extend(x["filename"] for x in chunk)
            _need(len(result) <= 100, "changed-file scope too large")
            if len(chunk) < 100:
                return result
        raise SnapshotError("changed-file page incomplete")

    def check(self, sha):
        _need(isinstance(sha, str) and bool(_SHA.fullmatch(sha)),
              "invalid check sha")
        data = self._get("/commits/" + sha + "/check-runs?per_page=100")
        rows = data.get("check_runs")
        _need(isinstance(rows, list), "missing check evidence")
        matches = [c for c in rows if c.get("name") == _REQUIRED]
        if len(matches) != 1:
            return "missing"
        found = matches[0]
        if found.get("status") != "completed":
            return "pending"
        conclusion = found.get("conclusion")
        if conclusion == "success":
            return "success"
        if conclusion in ("failure", "cancelled", "timed_out", "action_required"):
            return "failure"
        return "pending"


def collect_snapshot(reader, repository):
    """Pin main and every PR HEAD; never trust partial/damaged API responses."""
    _need(repository == "jssantogit/BitChord", "unapproved repository for planner")
    try:
        main_sha = reader.main_sha()
        _need(isinstance(main_sha, str) and bool(_SHA.fullmatch(main_sha)),
              "invalid main SHA")
        listing = reader.open_prs()
        _need(isinstance(listing, list) and len(listing) <= 1000,
              "invalid open PR list")
        seen, observed = set(), []
        for listed in listing:
            n = listed.get("number")
            _need(type(n) is int and n > 0 and n not in seen,
                  "duplicate/invalid open PR")
            seen.add(n)
            p = reader.pr(n)
            _need(p.get("number") == n and p.get("state") == "open" and
                  p.get("merged") is False, "PR closed during observation")
            _need(p.get("head_repo") == repository, "external PR head not trusted")
            _need(p.get("head_sha") == listed.get("head_sha") and
                  p.get("head_ref") == listed.get("head_ref") and
                  p.get("base_ref") == listed.get("base_ref") and
                  p.get("draft") == listed.get("draft"),
                  "PR changed during observation")
            files = reader.files(n)
            check = reader.check(p["head_sha"])
            again = reader.pr(n)
            _need(again.get("head_sha") == p["head_sha"] and
                  again.get("state") == "open" and
                  again.get("base_ref") == p["base_ref"],
                  "PR changed during observation")
            _need(isinstance(files, list) and 0 < len(files) <= 100,
                  "invalid or incomplete changed-file list")
            observed.append({"number": n, "head_sha": p["head_sha"],
                             "head_ref": p["head_ref"], "base_ref": p["base_ref"],
                             "files": files, "draft": p["draft"],
                             "mergeable": p["mergeable"], "check": check})
        _need(reader.main_sha() == main_sha, "main changed during observation")
        snapshot = {"main_sha": main_sha,
                    "pull_requests": sorted(observed, key=lambda r: r["number"])}
        # Consume the exact planner contract before returning a source snapshot.
        plan(snapshot)
        return snapshot
    except SnapshotError:
        raise
    except (Exception,):
        # Never render exception repr/trace from an untrusted API or reader.
        raise SnapshotError("read_error_or_invalid_snapshot") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description="Read-only GitHub PR snapshot")
    parser.add_argument("--repo", required=True, help="jssantogit/BitChord")
    args = parser.parse_args(argv)
    try:
        report = collect_snapshot(
            GitHubSnapshotReader(args.repo, os.environ.get("GITHUB_TOKEN")),
            args.repo)
    except SnapshotError as exc:
        parser.exit(2, "SNAPSHOT HALTED: " + str(exc) + "\n")
    json.dump(report, sys.stdout, sort_keys=True, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
