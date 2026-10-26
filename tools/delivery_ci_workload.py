"""Bounded, read-only, revision-attested CI workload categorization.

No filenames, branch refs, diff fragments, free-form API messages or tokens
are exported. This aids apples-to-apples Gradle timing comparisons; it does
not claim that a PR's current diff represents an old workflow run.
"""
import argparse
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class WorkloadError(ValueError):
    pass


_REPO = "jssantogit/BitChord"
_SHA = re.compile(r"^[0-9a-f]{40}$")
_STATUSES = frozenset(("added", "modified", "removed", "renamed", "changed"))
_GRADLE_FILES = frozenset((
    "gradle.properties", "gradle-wrapper.properties", "settings.gradle",
    "settings.gradle.kts", "build.gradle", "build.gradle.kts",
    "gradle/libs.versions.toml", "gradlew", "gradlew.bat",
))
_TEST_SEGMENTS = frozenset(("test", "androidTest", "commonTest", "jvmTest",
                            "testFixtures", "androidUnitTest", "jvmSharedTest"))


def _need(ok):
    if not ok:
        raise WorkloadError("unsafe_or_incomplete_pr_workload")


def _kind(path):
    _need(type(path) is str and 0 < len(path) <= 512)
    _need(not path.startswith("/") and "\\" not in path)
    parts = path.split("/")
    _need(all(x not in ("", ".", "..") for x in parts))
    lower = path.lower()
    basename = parts[-1]
    if path.startswith(".github/workflows/"):
        return "workflow"
    if basename in _GRADLE_FILES or path.startswith("gradle/") or (
        basename.endswith(".gradle.kts") or basename.endswith(".gradle")
    ):
        return "gradle"
    if lower.endswith((".kt", ".java")):
        return "kotlin_test" if any(p in _TEST_SEGMENTS for p in parts) else "kotlin_source"
    if (path.startswith(("tools/", "tests/", ".github/scripts/"))
            and lower.endswith((".py", ".sh", ".js", ".ts", ".yml", ".yaml"))):
        return "ci_script"
    if path.startswith("docs/") or lower.endswith((".md", ".rst", ".txt")):
        return "docs"
    return "other"


def classify_files(files):
    """Return only fixed-category counts for a complete (<100) PR files page."""
    _need(type(files) is list and 0 < len(files) < 100)
    kinds = {"kotlin_source": 0, "kotlin_test": 0, "gradle": 0,
             "workflow": 0, "ci_script": 0, "docs": 0, "other": 0}
    seen = set()
    for item in files:
        _need(type(item) is dict and item.get("status") in _STATUSES)
        path = item.get("filename")
        category = _kind(path)
        _need(path not in seen)
        seen.add(path)
        kinds[category] += 1
        if item["status"] == "renamed":
            previous = item.get("previous_filename")
            previous_kind = _kind(previous)
            # For a cross-class rename, describe both changed workloads.
            if previous_kind != category:
                kinds[previous_kind] += 1

    active = {k for k, count in kinds.items() if count}
    if "kotlin_source" in active:
        workload = ("kotlin_production" if active <= {
            "kotlin_source", "kotlin_test", "docs"} else "mixed")
    elif "gradle" in active:
        workload = ("gradle_configuration" if active <= {"gradle", "docs"}
                    else "mixed")
    elif "workflow" in active:
        workload = ("ci_workflows" if active <= {"workflow", "ci_script", "docs"}
                    else "mixed")
    elif "kotlin_test" in active:
        workload = ("kotlin_tests" if active <= {"kotlin_test", "docs"}
                    else "mixed")
    elif "ci_script" in active:
        workload = ("ci_scripts" if active <= {"ci_script", "docs"} else "mixed")
    elif active == {"docs"}:
        workload = "documentation"
    elif active == {"other"}:
        workload = "other"
    else:
        workload = "mixed"

    return {"version": 1, "workload_class": workload,
            "changed_files": len(files), "kind_counts": kinds}


def read_pr_workload(reader, pr_number, head_sha):
    """Refuse stale PR metadata, partial pages or any ambiguous provenance."""
    _need(type(pr_number) is int and 1 <= pr_number <= 10_000_000)
    _need(type(head_sha) is str and bool(_SHA.fullmatch(head_sha)))
    _need(getattr(reader, "repository", None) == _REPO)
    try:
        original = reader.pr(pr_number)
        _need(type(original) is dict and original.get("number") == pr_number)
        _need((original.get("head") or {}).get("sha") == head_sha)
        classes = classify_files(reader.files(pr_number))
        confirmation = reader.pr(pr_number)
        _need(type(confirmation) is dict and confirmation.get("number") == pr_number)
        _need((confirmation.get("head") or {}).get("sha") == head_sha)
        return {"pr_number": pr_number, "head_sha": head_sha, **classes}
    except WorkloadError:
        raise
    except Exception:
        raise WorkloadError("unsafe_or_incomplete_pr_workload") from None


class GithubPRFilesReader:
    repository = _REPO

    def __init__(self, token=None):
        self._token = token

    def _get(self, route):
        _need(type(route) is str and route.startswith("/") and not route.startswith("//"))
        headers = {"Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28",
                   **({"Authorization": "Bearer " + self._token} if self._token else {})}
        try:
            request = Request("https://api.github.com/repos/" + _REPO + route,
                              headers=headers, method="GET")
            with urlopen(request, timeout=15) as response:
                raw = response.read(3_000_001)
            _need(len(raw) <= 3_000_000)
            return json.loads(raw)
        except (HTTPError, URLError, OSError, ValueError):
            raise WorkloadError("unsafe_or_incomplete_pr_workload") from None

    def pr(self, number):
        return self._get("/pulls/" + str(number))

    def files(self, number):
        return self._get("/pulls/" + str(number) + "/files?per_page=100")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Read-only exact-HEAD PR workload class")
    parser.add_argument("--repo", required=True)
    parser.add_argument("--pr", required=True, type=int)
    parser.add_argument("--head-sha", required=True)
    args = parser.parse_args(argv)
    try:
        _need(args.repo == _REPO)
        report = read_pr_workload(
            GithubPRFilesReader(os.environ.get("GITHUB_TOKEN")),
            args.pr, args.head_sha)
    except WorkloadError:
        parser.exit(2, "PR WORKLOAD HALTED: unsafe_or_incomplete_pr_workload\n")
    json.dump(report, sys.stdout, sort_keys=True, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
