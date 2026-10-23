#!/usr/bin/env python3
"""Fail the build when the store artefact grows past what Play accepts.

Play caps a **base module** at 200 MB of download size. For this app that cap
is the whole reason optional packs exist: clang and libllvm are larger than
everything else combined, and shipping them in the base module would put the
app over the limit — or close enough that no ordinary release would fit.

The number this script reports is deliberately pessimistic. Play measures the
compressed APK a device downloads; an AAB stores every entry individually
compressed, so summing the compressed entry sizes and adding the manifest and
resource tables is a close upper bound, and the margin is left in place rather
than estimated away. A bundle that trips the tripwire at this bound would very
likely trip Play's own check.

Usage:
    check-bundle-size.py app.aab [--limit-mb 200] [--warn-mb 150]
                                [--json report.json] [--apk app.apk]
"""

import argparse
import json
import sys
import zipfile

#: Play's limit for a base module, in MB (decimal, as the console states it).
PLAY_BASE_MODULE_LIMIT_MB = 200
#: Where the build starts complaining, so a release does not creep up on the cap.
DEFAULT_WARN_MB = 150

_MANIFEST_NAMES = ("BundleConfig.pb", "base/manifest/AndroidManifest.xml")


def module_of(entry_name):
    """The module an AAB entry belongs to ('base', 'pack_buildtools', ...)."""
    if entry_name.startswith("BundleConfig.pb"):
        return "base"
    head = entry_name.split("/", 1)[0]
    return head or "base"


def measure(aab_path):
    """Per-module compressed byte counts, and the entries that make them up."""
    modules = {}
    with zipfile.ZipFile(aab_path) as bundle:
        for info in bundle.infolist():
            if info.is_dir():
                continue
            name = module_of(info.filename)
            slot = modules.setdefault(name, {"bytes": 0, "entries": 0, "largest": []})
            slot["bytes"] += info.compress_size
            slot["entries"] += 1
            slot["largest"].append((info.compress_size, info.filename))
    for slot in modules.values():
        slot["largest"] = sorted(slot["largest"], reverse=True)[:5]
    return modules


def abi_of(entry_name):
    """The ABI an AAB entry is delivered for, or None for ABI-independent ones.

    Play measures the base module against its limit **per device**: a bundle
    that carries arm64-v8a and x86_64 libraries is not 330 MB of download, it is
    165 MB on either device. Summing both ABIs would fail every real bundle.
    """
    parts = entry_name.split("/")
    if len(parts) >= 4 and parts[0] == "base" and parts[1] == "lib":
        return parts[2]
    return None


def base_size_by_abi(aab_path):
    """Base-module download size as Play would see it, per ABI.

    Returns {"common": bytes, "abis": {abi: bytes_of_that_abi}, "perAbi": {abi: total}}.
    """
    common = 0
    per_abi = {}
    with zipfile.ZipFile(aab_path) as bundle:
        for info in bundle.infolist():
            if info.is_dir() or module_of(info.filename) != "base":
                continue
            abi = abi_of(info.filename)
            if abi is None:
                common += info.compress_size
            else:
                per_abi[abi] = per_abi.get(abi, 0) + info.compress_size
    totals = {abi: common + size for abi, size in per_abi.items()}
    return {"common": common, "abis": per_abi, "perAbi": totals}


def universal_apk_size(apk_path):
    """The APK a device would install from a universal build, if one exists."""
    try:
        import os

        return os.path.getsize(apk_path)
    except OSError:
        return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("aab")
    parser.add_argument("--limit-mb", type=int, default=PLAY_BASE_MODULE_LIMIT_MB)
    parser.add_argument("--warn-mb", type=int, default=DEFAULT_WARN_MB)
    parser.add_argument("--json", dest="json_path")
    parser.add_argument("--apk", help="the sealed APK, reported as ground truth")
    args = parser.parse_args(argv)

    limit = args.limit_mb * 1_000_000
    warn = args.warn_mb * 1_000_000

    modules = measure(args.aab)
    if "base" not in modules:
        print(f"no base module in {args.aab}", file=sys.stderr)
        return 2

    base_total = modules["base"]["bytes"]
    sizing = base_size_by_abi(args.aab)
    # The number that matters: the largest per-device base module.
    if sizing["perAbi"]:
        base_bytes = max(sizing["perAbi"].values())
    else:
        base_bytes = sizing["common"]
    report = {
        "aab": args.aab,
        "limit_mb": args.limit_mb,
        "base_module_total_bytes": base_total,
        "base_module_total_mb": round(base_total / 1_000_000, 1),
        "base_module_bytes": base_bytes,
        "base_module_mb": round(base_bytes / 1_000_000, 1),
        "base_common_mb": round(sizing["common"] / 1_000_000, 1),
        "base_per_abi_mb": {
            abi: round(size / 1_000_000, 1) for abi, size in sizing["perAbi"].items()
        },
        "modules": {
            name: {
                "bytes": slot["bytes"],
                "mb": round(slot["bytes"] / 1_000_000, 1),
                "entries": slot["entries"],
                "largest": [
                    {"bytes": size, "name": entry} for size, entry in slot["largest"]
                ],
            }
            for name, slot in sorted(modules.items())
        },
    }
    apk_bytes = universal_apk_size(args.apk) if args.apk else None
    if apk_bytes is not None:
        report["universal_apk_bytes"] = apk_bytes
        report["universal_apk_mb"] = round(apk_bytes / 1_000_000, 1)

    print(f"bundle: {args.aab}")
    for name in sorted(modules):
        slot = modules[name]
        kind = "base" if name == "base" else "feature"
        print(f"  {kind:<8} {name:<20} {slot['bytes'] / 1_000_000:8.1f} MB "
              f"({slot['entries']} entries)")
    if apk_bytes is not None:
        print(f"  universal APK         {apk_bytes / 1_000_000:8.1f} MB")
    if sizing["perAbi"]:
        for abi, size in sorted(sizing["perAbi"].items()):
            print(f"  base module on {abi:<10} {size / 1_000_000:8.1f} MB "
                  f"({sizing['common'] / 1_000_000:.1f} MB shared + "
                  f"{sizing['abis'][abi] / 1_000_000:.1f} MB of libraries)")
    share = f" ({100.0 * base_bytes / limit:.1f}% of it)" if limit else ""
    print(f"  base module against Play's {args.limit_mb} MB limit: "
          f"{base_bytes / 1_000_000:.1f} MB per device{share}; "
          f"{base_total / 1_000_000:.1f} MB for every ABI at once")

    if args.json_path:
        with open(args.json_path, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2)
            handle.write("\n")

    if base_bytes > limit:
        print(f"the base module a device would download is over Play's "
              f"{args.limit_mb} MB limit by {(base_bytes - limit) / 1_000_000:.1f} MB "
              f"— move weight into a dynamic feature module (see pack_buildtools/)",
              file=sys.stderr)
        return 1
    if base_bytes > warn:
        print(f"warning: base module is within {(limit - base_bytes) / 1_000_000:.1f} MB "
              f"of Play's limit")
    return 0


if __name__ == "__main__":
    sys.exit(main())
