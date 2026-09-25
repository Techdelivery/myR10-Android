#!/usr/bin/env python3
"""Render JUnit XML reports into a GitHub Actions job summary.

Inputs (env):
  REPORT_DIR  required  directory searched recursively for JUnit XML
  JOB_NAME    optional  heading label (default: "Tests")
Output:
  $GITHUB_STEP_SUMMARY  (stdout when unset, so it can be inspected locally)

Exit code is always 0: this is a reporter, not a gate. Gradle already failed the
build step if the tests failed.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def to_int(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def to_float(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def first_line(text, limit=200):
    text = (text or "").strip()
    return text.splitlines()[0][:limit] if text else ""


def collect(report_dir):
    files = sorted(glob.glob(os.path.join(report_dir, "**", "*.xml"), recursive=True))
    suites, failures = [], []

    for path in files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as exc:
            print(f"::warning::could not parse {path}: {exc}", file=sys.stderr)
            continue

        nodes = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in nodes:
            suites.append(
                {
                    "name": suite.get("name") or os.path.relpath(path, report_dir),
                    "tests": to_int(suite.get("tests")),
                    "failures": to_int(suite.get("failures")),
                    "errors": to_int(suite.get("errors")),
                    "skipped": to_int(suite.get("skipped")),
                    "time": to_float(suite.get("time")),
                }
            )
            for case in suite.iter("testcase"):
                for tag, kind in (("failure", "failure"), ("error", "error")):
                    node = case.find(tag)
                    if node is None:
                        continue
                    failures.append(
                        {
                            "suite": suite.get("name") or os.path.relpath(path, report_dir),
                            "case": f"{case.get('classname', '?')}.{case.get('name', '?')}",
                            "kind": kind,
                            "message": first_line(node.get("message")),
                        }
                    )
    return suites, failures


def render(job_name, suites, failures):
    lines = [f"### {job_name}", ""]
    if not suites:
        lines.append(f"> [!WARNING]\n> No JUnit XML found under `{os.environ.get('REPORT_DIR', '')}`.")
        return "\n".join(lines) + "\n"

    total = {k: sum(s[k] for s in suites) for k in ("tests", "failures", "errors", "skipped")}
    total["time"] = sum(s["time"] for s in suites)
    passed = total["tests"] - total["failures"] - total["errors"] - total["skipped"]
    bad = total["failures"] + total["errors"]

    badge = ":white_check_mark: passed" if bad == 0 else ":x: failed"
    lines.append(
        f"**{badge}** — {passed} passed · {total['failures']} failed · "
        f"{total['errors']} errors · {total['skipped']} skipped "
        f"({total['tests']} total, {total['time']:.1f}s)"
    )
    lines += ["", "| Suite | Tests | Fail | Err | Skip | Time |", "|---|---:|---:|---:|---:|---:|"]
    for suite in sorted(suites, key=lambda s: (-s["failures"] - s["errors"], s["name"])):
        marker = ":x: " if suite["failures"] + suite["errors"] else (
            ":fast_forward: " if suite["skipped"] and suite["tests"] == suite["skipped"] else ""
        )
        lines.append(
            f"| `{marker}{suite['name']}` | {suite['tests']} | {suite['failures']} | "
            f"{suite['errors']} | {suite['skipped']} | {suite['time']:.2f}s |"
        )

    if failures:
        lines += ["", "<details>", "<summary>Failing tests</summary>", ""]
        for item in failures:
            lines.append(f"- **{item['kind']}** `{item['case']}`")
            if item["message"]:
                lines.append(f"  ```\n  {item['message']}\n  ```")
        lines += ["</details>"]

    return "\n".join(lines) + "\n"


def main():
    report_dir = os.environ.get("REPORT_DIR", "")
    job_name = os.environ.get("JOB_NAME", "Tests")
    suites, failures = collect(report_dir)
    summary = render(job_name, suites, failures)

    out = os.environ.get("GITHUB_STEP_SUMMARY")
    if out:
        with open(out, "a", encoding="utf-8") as fh:
            fh.write(summary + "\n")
    else:
        sys.stdout.write(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
