#!/usr/bin/env python3
"""Report 16 KB page alignment for every shared object in an APK.

Google Play requires 16 KB page support for apps targeting API 35+ (the update
block starts 2027-02-01). The property that matters is the alignment of the
ELF's loadable segments - `p_align >= 16384` - not the alignment of the zip
entry, which `zipalign -P 16` checks and which is meaningless for a build that
packages its libraries compressed and extracts them at install time (which is
what this project does, because the executables have to be real files in the
native library directory).

Two classes of object are in the APK:

  * the modules this repository compiles (libtermux, libharness-exec, the exec
    probe) - these are gated, because we control their link flags;
  * the Termux provider binaries shipped as JNI libraries (libbash.so, ...) -
    these are reported, because their alignment is decided by how the upstream
    packages were built, and the number is a fact the plan needs.

Usage: check-page-alignment.py <apk> [--report <path>] [--require-compliant]
"""

import argparse
import sys
import zipfile

OURS = ("libtermux", "libharness-exec", "libharnessprobe")
PAGE_16K = 16384
PT_LOAD = 1


def load_alignments(content):
    """p_align of every PT_LOAD segment in a 64-bit little-endian ELF."""
    if content[:6] != b"\x7fELF\x02\x01":
        return []
    phoff = int.from_bytes(content[0x20:0x28], "little")
    phentsize = int.from_bytes(content[0x36:0x38], "little")
    phnum = int.from_bytes(content[0x38:0x3A], "little")
    if not 0 < phentsize <= 128 or not 0 < phnum <= 256:
        return []
    aligns = []
    for index in range(phnum):
        base = phoff + index * phentsize
        if int.from_bytes(content[base:base + 4], "little") != PT_LOAD:
            continue
        aligns.append(int.from_bytes(content[base + 48:base + 56], "little"))
    return aligns


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk")
    parser.add_argument("--report", help="write a per-object report here")
    parser.add_argument("--require-compliant", action="store_true",
                        help="fail when this repository's own objects are not 16 KB aligned")
    arguments = parser.parse_args()

    lines = []
    ours_bad = []
    shared_bad = []
    shared_total = 0
    with zipfile.ZipFile(arguments.apk) as apk:
        for name in sorted(apk.namelist()):
            if not (name.startswith("lib/") and name.endswith(".so")):
                continue
            aligns = load_alignments(apk.read(name))
            if not aligns:
                continue
            smallest = min(aligns)
            stem = name.rsplit("/", 1)[-1][:-3]
            compliant = smallest >= PAGE_16K
            if stem in OURS:
                kind = "built-here"
                if not compliant:
                    ours_bad.append(name)
            else:
                kind = "shipped-userland"
                shared_total += 1
                if not compliant:
                    shared_bad.append(name)
            lines.append("%-8s %-52s p_align=%d %s"
                         % (kind, name, smallest, "16kb" if compliant else "4kb"))

    compliant_shared = shared_total - len(shared_bad)
    summary = ("page alignment: %d/%d shipped userland objects are 16 KB aligned; "
               "%d object(s) built here, %d non-compliant"
               % (compliant_shared, shared_total, len(OURS), len(ours_bad)))
    print(summary)
    if lines:
        print("\n".join(line for line in lines if "4kb" in line) or "all objects 16 KB aligned")
    if arguments.report:
        with open(arguments.report, "w") as handle:
            handle.write(summary + "\n" + "\n".join(lines) + "\n")
    if ours_bad:
        print("not 16 KB aligned and built by this repository: %s"
              % ", ".join(ours_bad), file=sys.stderr)
        if arguments.require_compliant:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
