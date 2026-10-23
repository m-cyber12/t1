#!/usr/bin/env python3
"""Assert the sealed gate actually ran, and passed.

A skipped test is not a pass, and a hard-coded "Tests 2/2" is a gate that breaks
the day someone adds a test (it did). This reads the JUnit XML that
`connectedSealedAndroidTest` writes and requires every expected class to have
run, unskipped and green.

Usage: assert-gate-results.py <results-dir>
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

# Class -> minimum number of test methods. Counts are floors, not equals, so
# adding a test does not require touching the gate.
EXPECTED = {
    "io.mcyber12.codingharness.SealedShellTest": 2,
    "io.mcyber12.codingharness.ExecModelTest": 1,
}


def collect(results_dir):
    found = {}
    for path in glob.glob(os.path.join(results_dir, "*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            name = case.get("classname", "")
            for expected in EXPECTED:
                if name == expected or name.startswith(expected + "."):
                    stats = found.setdefault(expected,
                                             {"tests": 0, "failed": 0, "skipped": 0})
                    stats["tests"] += 1
                    if case.find("failure") is not None or case.find("error") is not None:
                        stats["failed"] += 1
                    if case.find("skipped") is not None:
                        stats["skipped"] += 1
    return found


def main():
    results_dir = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/androidTest-results/connected"
    found = collect(results_dir)
    print("results:", found or "none")
    problems = []
    for expected, minimum in EXPECTED.items():
        stats = found.get(expected)
        if not stats:
            problems.append("%s did not run (no JUnit XML in %s)" % (expected, results_dir))
            continue
        if stats["tests"] < minimum:
            problems.append("%s ran %d test(s), expected at least %d"
                            % (expected, stats["tests"], minimum))
        if stats["failed"]:
            problems.append("%s has %d failure(s)" % (expected, stats["failed"]))
        if stats["skipped"]:
            problems.append("%s skipped %d test(s) - a skip is not a pass"
                            % (expected, stats["skipped"]))
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    print("both gates ran and passed: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
