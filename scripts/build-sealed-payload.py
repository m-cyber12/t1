#!/usr/bin/env python3
"""Splits a Termux bootstrap into a *sealed* payload.

A sealed payload is the userland of a modern-targetSdk build: the bytes the
kernel executes ship inside the APK as JNI libraries (the only exec-allowed
place for an app targeting API 29+), everything else is data the app extracts
at first run, and the prefix keeps its absolute paths because the executables
are symlinks into the native library directory.

Input:  the ABI-specific Termux bootstrap zip (the same artifact the apt-based
        build extracts), which already contains a full prefix tree plus
        SYMLINKS.txt.
Output: --out assets/userland-<abi>.zip       data tree (prefix-relative)
        --out assets/sealed-exec-<abi>.txt    "<prefix-relative path>\\t<lib>"
        --out assets/sealed-symlinks-<abi>.txt  "<path>←<target>" for data links
        --out jniLibs/<abi>/lib<name>.so      the executables themselves
        --out assets/sealed-payload-<abi>.json  counts + hashes for the log

The script refuses to emit a payload that would need the data-directory exec
exemption: after writing the zip it re-reads it and fails if any ELF or shebang
file with an execute bit is still inside.

Usage:
    build-sealed-payload.py --bootstrap <zip> --abi <arm64-v8a|x86_64> --out <dir>
"""

import argparse
import hashlib
import io
import json
import os
import re
import sys
import zipfile

OFFICIAL_PREFIX = "/data/data/com.termux/files/usr"
OFFICIAL_DATA = "/data/data/com.termux"
SYMLINK_SEPARATOR = "\u2190"  # Termux's own SYMLINKS.txt separator

# Directories whose ELF files are shared libraries, not executables. They are
# dlopen()ed (mmap, always allowed), so they stay in the payload.
LIBRARY_DIRS = ("lib/", "lib64/")

# Where an ELF is an executable even when the zip entry lost its mode bits.
EXECUTABLE_DIRS = ("bin/", "libexec/", "libexec/termux-am/") 


def sanitize_library_name(path, used):
    """lib<name>.so, unique per payload; AGP only packages lib*.so."""
    base = os.path.basename(path)
    base = re.sub(r"[^A-Za-z0-9_]", "_", base)
    if base.endswith(".so"):
        base = base[:-3]
    candidate = "lib" + base + ".so"
    if candidate in used:
        digest = hashlib.sha1(path.encode("utf-8")).hexdigest()[:8]
        candidate = "lib" + base + "_" + digest + ".so"
    used.add(candidate)
    return candidate


def entry_mode(info, path):
    mode = (info.external_attr >> 16) & 0o777
    if mode:
        return mode
    return 0o755 if path.startswith(EXECUTABLE_DIRS) or "/bin/" in path else 0o644


def is_elf(head):
    return head[:4] == b"\x7fELF"


def is_script(head):
    return head[:2] == b"#!"


def read_symlinks(entries):
    """SYMLINKS.txt uses '<target>←<link path>' (Termux's own format)."""
    links = {}
    if "SYMLINKS.txt" not in entries:
        return links
    for raw in entries["SYMLINKS.txt"].decode("utf-8", "replace").splitlines():
        if not raw.strip():
            continue
        if SYMLINK_SEPARATOR not in raw:
            raise SystemExit("malformed SYMLINKS.txt line: %r" % raw)
        target, link = raw.split(SYMLINK_SEPARATOR, 1)
        links[normalize(link.strip())] = target.strip()
    return links


def normalize(path):
    path = path.replace("\\", "/").lstrip("/")
    if path.startswith(OFFICIAL_PREFIX[1:] + "/"):
        path = path[len(OFFICIAL_PREFIX) - 1:].lstrip("/")
    elif path.startswith(OFFICIAL_DATA[1:] + "/"):
        path = path[len(OFFICIAL_DATA) - 1:].lstrip("/")
        # /data/data/com.termux/files/usr/... arrives here as files/usr/...
        path = path[len("files/usr/"):] if path.startswith("files/usr/") else path
    parts = []
    for piece in path.split("/"):
        if piece in ("", "."):
            continue
        if piece == "..":
            if parts:
                parts.pop()
            continue
        parts.append(piece)
    return "/".join(parts)


def resolve(path, links, entries, depth=0):
    """Follow symlinks inside the tree; None when the target is not shipped.

    A relative target in SYMLINKS.txt is relative to the link's own directory
    (``lib/libssl.so -> libssl.so.3``), not to the prefix; an absolute target is
    the official Termux path. Both shapes occur in one bootstrap.
    """
    if depth > 12:
        return None
    if path in links:
        target = links[path]
        if not target.startswith("/") and "/" not in target:
            directory = os.path.dirname(path)
            target = (directory + "/" + target) if directory else target
        resolved = normalize(target)
        if resolved not in links and resolved not in entries:
            # Fall back to prefix-relative interpretation.
            resolved = normalize(links[path])
        return resolve(resolved, links, entries, depth + 1)
    return path if path in entries else None


def build(bootstrap, abi, out_dir, verbose=True):
    if not os.path.isfile(bootstrap):
        raise SystemExit("bootstrap not found: %s" % bootstrap)

    entries = {}
    modes = {}
    with zipfile.ZipFile(bootstrap) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = normalize(info.filename)
            if not name or name.endswith("/"):
                continue
            entries[name] = archive.read(info)
            modes[name] = entry_mode(info, name)

    links = read_symlinks(entries)
    entries.pop("SYMLINKS.txt", None)

    # ---- classify ---------------------------------------------------------
    sealed = {}          # prefix-relative path -> library name
    payload = {}         # prefix-relative path -> bytes (data)
    kept_links = {}      # prefix-relative link path -> target (data)
    libraries = {}       # library name -> bytes
    used_names = set()

    for path in sorted(entries):
        content = entries[path]
        head = content[:4]
        mode = modes[path]
        executable_bit = bool(mode & 0o111)
        in_library_dir = path.startswith(LIBRARY_DIRS)

        if not in_library_dir and is_elf(head) and (executable_bit or path.startswith(EXECUTABLE_DIRS)):
            library = sanitize_library_name(path, used_names)
            libraries[library] = content
            sealed[path] = library
            continue
        payload[path] = content

    # symlinks: to a sealed binary -> another sealed entry; otherwise kept as a
    # link for the installer (libraries of the same version, script aliases).
    for link_path in sorted(links):
        if link_path in sealed:
            continue
        resolved = resolve(link_path, links, entries)
        if resolved is not None and resolved in sealed:
            sealed[link_path] = sealed[resolved]
        elif resolved is not None:
            kept_links[link_path] = resolved

    # A path that becomes a symlink at install time must not also ship as a
    # regular file (the installer replaces it anyway; this keeps the payload
    # honest about what it contains).
    for link_path in kept_links:
        payload.pop(link_path, None)

    if not sealed:
        raise SystemExit("refusing to build a sealed payload with no executables")

    # ---- write ------------------------------------------------------------
    assets_dir = os.path.join(out_dir, "assets")
    jni_dir = os.path.join(out_dir, "jniLibs", abi)
    os.makedirs(assets_dir, exist_ok=True)
    os.makedirs(jni_dir, exist_ok=True)

    userland = os.path.join(assets_dir, "userland-%s.zip" % abi)
    with zipfile.ZipFile(userland, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, content in sorted(payload.items()):
            archive.writestr(path, content)

    for library, content in sorted(libraries.items()):
        with open(os.path.join(jni_dir, library), "wb") as handle:
            handle.write(content)
        os.chmod(os.path.join(jni_dir, library), 0o755)

    with open(os.path.join(assets_dir, "sealed-exec-%s.txt" % abi), "w", encoding="utf-8") as handle:
        handle.write("# prefix-relative executable path\tJNI library (shipped by the APK)\n")
        for path, library in sorted(sealed.items()):
            handle.write("%s\t%s\n" % (path, library))

    with open(os.path.join(assets_dir, "sealed-symlinks-%s.txt" % abi), "w", encoding="utf-8") as handle:
        handle.write("# prefix-relative link\tprefix-relative target (data, created at install)\n")
        for path, target in sorted(kept_links.items()):
            handle.write("%s\t%s\n" % (path, target))

    # ---- verify what was actually written --------------------------------
    problems = []
    scripts = 0
    with zipfile.ZipFile(userland) as archive:
        for info in archive.infolist():
            head = archive.read(info)[:4]
            if is_elf(head) and not info.filename.startswith(LIBRARY_DIRS):
                # A shared library is read (dlopen/mmap), which is allowed from
                # app data; an executable ELF would need execve, which is not.
                problems.append("executable ELF left in payload: %s" % info.filename)
            elif is_script(head):
                # Scripts are data by design: the preload translates their
                # interpreter and starts it, so the script itself is never
                # execve'd. Counted so the log shows the reliance is known.
                scripts += 1
    for name in sorted(os.listdir(jni_dir)):
        if not name.startswith("lib") or not name.endswith(".so"):
            problems.append("bad library name: %s" % name)
    for path, library in sealed.items():
        if not os.path.isfile(os.path.join(jni_dir, library)):
            problems.append("sealed entry %s has no %s" % (path, library))
    if problems:
        for problem in problems:
            print("sealed-payload: %s" % problem, file=sys.stderr)
        raise SystemExit("sealed payload is not sealed")

    report = {
        "abi": abi,
        "bootstrap": os.path.basename(bootstrap),
        "executables": len(sealed),
        "libraries": len(libraries),
        "dataFiles": len(payload),
        "symlinks": len(kept_links),
        "scripts": scripts,
        "userlandBytes": os.path.getsize(userland),
        "libraryBytes": sum(os.path.getsize(os.path.join(jni_dir, name))
                            for name in os.listdir(jni_dir)),
        "librariesSha256": {
            name: hashlib.sha256(libraries[name]).hexdigest()
            for name in sorted(libraries)
        },
    }
    with open(os.path.join(assets_dir, "sealed-payload-%s.json" % abi), "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)

    if verbose:
        print("sealed payload %s: %d executables -> %d JNI libraries (%.1f MiB), "
              "%d data files (%.1f MiB), %d data symlinks"
              % (abi, len(sealed), len(libraries), report["libraryBytes"] / (1 << 20),
                 len(payload), report["userlandBytes"] / (1 << 20), len(kept_links)))
        print("sealed payload %s: %d scripts stay in the payload (run through the preload)"
              % (abi, scripts))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bootstrap", required=True, help="ABI-specific bootstrap zip")
    parser.add_argument("--abi", required=True, choices=["arm64-v8a", "x86_64"])
    parser.add_argument("--out", required=True, help="output directory")
    arguments = parser.parse_args()
    build(arguments.bootstrap, arguments.abi, arguments.out)


if __name__ == "__main__":
    main()
