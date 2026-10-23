#!/usr/bin/env python3
"""Tests for scripts/check-bundle-size.py.

The tripwire is the only thing standing between a release and a Play rejection,
so its arithmetic is tested against a bundle built here rather than trusted.
"""

import io
import json
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "check_bundle_size",
    os.path.join(os.path.dirname(__file__), "..", "check-bundle-size.py"),
)
check = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check)


def make_aab(path, base_entries=1, pack_entries=0, base_bytes=1024, pack_bytes=1024):
    """A bundle shaped like AGP's: base/ plus one feature module."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr("BundleConfig.pb", b"config" * 10)
        for index in range(base_entries):
            bundle.writestr(f"base/dex/classes{index}.dex", b"a" * base_bytes)
        for index in range(pack_entries):
            bundle.writestr(f"pack_buildtools/assets/payload{index}.zip", b"b" * pack_bytes)
    return path


class MeasureTest(unittest.TestCase):

    def test_entries_are_grouped_by_module(self):
        with tempfile.TemporaryDirectory() as directory:
            aab = make_aab(os.path.join(directory, "app.aab"),
                           base_entries=2, pack_entries=3)
            modules = check.measure(aab)
        self.assertIn("base", modules)
        self.assertIn("pack_buildtools", modules)
        self.assertEqual(modules["base"]["entries"], 3)      # BundleConfig.pb + 2
        self.assertEqual(modules["pack_buildtools"]["entries"], 3)
        self.assertGreater(modules["base"]["bytes"], 0)
        # The largest entries are kept for the report, and only a handful.
        self.assertLessEqual(len(modules["base"]["largest"]), 5)

    def test_the_bundle_config_counts_against_the_base(self):
        self.assertEqual(check.module_of("BundleConfig.pb"), "base")
        self.assertEqual(check.module_of("base/manifest/AndroidManifest.xml"), "base")
        self.assertEqual(check.module_of("pack_buildtools/manifest/AndroidManifest.xml"),
                         "pack_buildtools")

    def test_an_unknown_module_is_still_reported(self):
        # A module added later must not vanish from the report by being unknown.
        self.assertEqual(check.module_of("pack_future/assets/x"), "pack_future")


class LimitTest(unittest.TestCase):

    def test_a_small_base_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            aab = make_aab(os.path.join(directory, "app.aab"), base_entries=2,
                           base_bytes=1024)
            self.assertEqual(check.main([aab]), 0)

    def test_a_base_over_the_limit_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            aab = os.path.join(directory, "app.aab")
            with zipfile.ZipFile(aab, "w", zipfile.ZIP_STORED) as bundle:
                bundle.writestr("base/manifest/AndroidManifest.xml", b"m" * 10)
                bundle.writestr("base/assets/huge.bin", b"x" * (2 * 1_000_000))
            # 2 MB against a 1 MB limit: the limit is an argument so this test
            # does not have to write a 200 MB file to prove the arithmetic.
            self.assertEqual(check.main([aab, "--limit-mb", "1"]), 1)

    def test_the_report_names_every_module_and_the_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            aab = make_aab(os.path.join(directory, "app.aab"), base_entries=1,
                           pack_entries=2)
            apk = os.path.join(directory, "app.apk")
            with open(apk, "wb") as handle:
                handle.write(b"z" * 5000)
            report = os.path.join(directory, "report.json")
            code = check.main([aab, "--json", report, "--apk", apk])
            self.assertEqual(code, 0)
            with open(report, encoding="utf-8") as handle:
                written = json.load(handle)
        self.assertEqual(written["limit_mb"], 200)
        self.assertIn("base", written["modules"])
        self.assertIn("pack_buildtools", written["modules"])
        self.assertEqual(written["universal_apk_bytes"], 5000)

    def test_a_bundle_without_a_base_module_is_an_error(self):
        with tempfile.TemporaryDirectory() as directory:
            aab = os.path.join(directory, "app.aab")
            with zipfile.ZipFile(aab, "w", zipfile.ZIP_DEFLATED) as bundle:
                bundle.writestr("pack_buildtools/assets/x", b"y")
            self.assertEqual(check.main([aab]), 2)

    def test_the_warning_threshold_is_below_the_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            aab = os.path.join(directory, "app.aab")
            with zipfile.ZipFile(aab, "w", zipfile.ZIP_STORED) as bundle:
                bundle.writestr("base/assets/mid.bin", b"x" * 1_500_000)
            # Under the 2 MB limit, over the 1 MB warning: passes, warns.
            self.assertEqual(check.main([aab, "--limit-mb", "2", "--warn-mb", "1"]), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
