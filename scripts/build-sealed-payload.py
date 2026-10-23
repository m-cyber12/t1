#!/usr/bin/env python3
"""Splits the Termux userland into a *sealed* payload.

A sealed payload is the userland of a modern-targetSdk build: the bytes the
kernel executes ship inside the APK as JNI libraries (the only exec-allowed
place for an app targeting API 29+), everything else is data the app extracts
at first run, and the prefix keeps its absolute paths because the executables
are symlinks into the native library directory.

Two inputs, both pinned:

* one ABI's Termux bootstrap zip (shell, coreutils, apt/dpkg database);
* the `.deb` files fetched by `fetch-core-debs.py` (git, python, node, perl,
  ruby, ...) — unpacked here, never installed on a device.

Output:

    assets/userland-<abi>.zip          data tree (prefix-relative)
    assets/sealed-exec-<abi>.txt       "<prefix path>\\t<library>"
    assets/sealed-symlinks-<abi>.txt   data links, prefix-relative both sides
    assets/sealed-payload-<abi>.json   counts, sizes, per-library hashes
    jniLibs/<abi>/lib<name>.so         the executables themselves

Design rules worth keeping:

* one library per unique binary *content*. Termux hard-links git-core's ~180
  command names to one binary; duplicating it would add hundreds of megabytes
  to the APK for nothing, so equal content maps to the same `lib*.so` and the
  manifest simply lists every path that points at it.
* scripts stay in the payload on purpose: they are interpreted (the preload
  translates their interpreter and starts it), never `execve`-d.
* the output is verified before it is reported: an executable ELF left in the
  payload is a build failure, not a warning.

Usage:
    build-sealed-payload.py --bootstrap <zip> --abi <arm64-v8a|x86_64> --out <dir> \
        [--deb-dir <dir with .deb files>]
"""

import argparse
import gzip
import hashlib
import io
import json
import lzma
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import zipfile

OFFICIAL_PREFIX = "/data/data/com.termux/files/usr"
OFFICIAL_DATA = "/data/data/com.termux"
SYMLINK_SEPARATOR = "\u2190"  # Termux's own SYMLINKS.txt separator

# Directories whose ELF files are shared libraries, not executables: they are
# dlopen()ed (mmap, always allowed), so they stay in the payload.
LIBRARY_DIRS = ("lib/", "lib64/")

# Where an ELF is an executable even when the archive lost its mode bits.
EXECUTABLE_DIRS = ("bin/", "libexec/")


# --------------------------------------------------------------------------- #
# names and paths
# --------------------------------------------------------------------------- #

def normalize(path):
    """Strip the official Termux prefix and any relative noise."""
    path = path.replace("\\", "/")
    while path.startswith("./"):
        path = path[2:]
    path = path.lstrip("/")
    for prefix in (OFFICIAL_PREFIX[1:] + "/", OFFICIAL_DATA[1:] + "/"):
        if path.startswith(prefix):
            path = path[len(prefix):]
            if prefix.startswith(OFFICIAL_DATA[1:]) and path.startswith("files/usr/"):
                path = path[len("files/usr/"):]
            break
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


def sanitize_library_name(path, used, digest):
    """lib<name>.so, unique per payload; AGP only packages lib*.so."""
    base = os.path.basename(path)
    base = re.sub(r"[^A-Za-z0-9_]", "_", base)
    if base.endswith(".so"):
        base = base[:-3]
    candidate = "lib" + base + ".so"
    if candidate in used and used[candidate] != digest:
        candidate = "lib" + base + "_" + digest[:8] + ".so"
    return candidate


def entry_mode(mode, path):
    if mode:
        return mode
    return 0o755 if path.startswith(EXECUTABLE_DIRS) else 0o644


def is_elf(head):
    return head[:4] == b"\x7fELF"


def is_script(head):
    return head[:2] == b"#!"


# --------------------------------------------------------------------------- #
# inputs
# --------------------------------------------------------------------------- #

class Tree:
    """The merged userland: files by prefix-relative path, plus symlinks."""

    def __init__(self):
        self.files = {}       # path -> bytes
        self.modes = {}       # path -> mode
        self.links = {}       # path -> target (absolute or prefix-relative)
        self.origins = {}     # path -> "bootstrap" | package name

    def add_file(self, path, data, mode, origin):
        self.files[path] = data
        self.modes[path] = mode
        self.links.pop(path, None)
        self.origins[path] = origin

    def add_link(self, path, target, origin):
        self.files.pop(path, None)
        self.modes.pop(path, None)
        self.links[path] = target
        self.origins[path] = origin


def read_bootstrap(bootstrap, tree):
    with zipfile.ZipFile(bootstrap) as archive:
        symlinks = {}
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = normalize(info.filename)
            if not name:
                continue
            if name == "SYMLINKS.txt":
                for raw in archive.read(info).decode("utf-8", "replace").splitlines():
                    if not raw.strip():
                        continue
                    if SYMLINK_SEPARATOR not in raw:
                        raise SystemExit("malformed SYMLINKS.txt line: %r" % raw)
                    target, link = raw.split(SYMLINK_SEPARATOR, 1)
                    symlinks[normalize(link.strip())] = target.strip()
                continue
            tree.add_file(name, archive.read(info),
                          entry_mode((info.external_attr >> 16) & 0o777, name),
                          "bootstrap")
        for link, target in symlinks.items():
            tree.add_link(link, target, "bootstrap")


def ar_members(path):
    """Yield (name, bytes) from a Debian `ar` archive."""
    with open(path, "rb") as handle:
        magic = handle.read(8)
        if magic != b"!<arch>\n":
            raise SystemExit("not an ar archive: %s" % path)
        while True:
            header = handle.read(60)
            if len(header) < 60:
                return
            name = header[0:16].decode("ascii", "replace").strip().rstrip("/")
            size = int(header[48:58].decode("ascii", "replace").strip() or 0)
            data = handle.read(size)
            if size % 2:
                handle.read(1)
            yield name, data


def deb_data_tar(path):
    """The uncompressed-or-streamed `data.tar.*` member of a .deb, as bytes."""
    for name, data in ar_members(path):
        if not name.startswith("data.tar"):
            continue
        if name.endswith(".xz"):
            return lzma.decompress(data)
        if name.endswith(".gz"):
            return gzip.decompress(data)
        if name.endswith(".zst"):
            try:
                import zstandard  # optional
                return zstandard.ZstdDecompressor().decompress(data)
            except ImportError:
                pass
            try:
                with tempfile.NamedTemporaryFile(suffix=".zst", delete=False) as handle:
                    handle.write(data)
                    temporary = handle.name
                result = subprocess.run(["zstd", "-d", "-c", temporary],
                                        capture_output=True, check=False)
                if result.returncode != 0:
                    raise SystemExit("zstd is not available to unpack %s" % path)
                return result.stdout
            finally:
                try:
                    os.unlink(temporary)
                except Exception:
                    pass
        return data  # plain data.tar
    raise SystemExit("no data.tar member in %s" % path)


def read_deb(path, tree, name):
    """Merge one package's payload into the tree, dependency order last-wins."""
    archive = deb_data_tar(path)
    count = 0
    pending_links = []
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
        for member in tar:
            relative = normalize(member.name)
            if not relative or relative.startswith("DEBIAN/") or relative == "DEBIAN":
                continue
            if member.isdir():
                continue
            if member.issym():
                pending_links.append((relative, member.linkname))
                continue
            if member.islnk():
                # Hard link: the content of an earlier member. Recorded as a
                # link to that same content; the splitter dedupes by hash, so
                # git-core's 180 names stay one library.
                pending_links.append((relative, normalize(member.linkname)))
                count += 1
                continue
            if not member.isfile():
                continue
            handle = tar.extractfile(member)
            if handle is None:
                continue
            tree.add_file(relative, handle.read(),
                          entry_mode(member.mode, relative), name)
            count += 1
    for link, target in pending_links:
        tree.add_link(link, target, name)
    return count


# --------------------------------------------------------------------------- #
# split
# --------------------------------------------------------------------------- #

def resolve(path, links, files, depth=0):
    """Follow a chain of links inside the tree; None when it is not shipped."""
    if depth > 16:
        return None
    if path in links:
        target = links[path]
        if not target.startswith("/") and "/" not in target:
            directory = os.path.dirname(path)
            target = (directory + "/" + target) if directory else target
        resolved = normalize(target)
        if resolved not in links and resolved not in files:
            resolved = normalize(links[path])
        return resolve(resolved, links, files, depth + 1)
    return path if path in files else None


def split(tree):
    sealed = {}      # prefix path -> library name
    libraries = {}   # library name -> bytes
    payload = {}
    kept_links = {}
    by_digest = {}   # digest -> library name
    used = {}        # library name -> digest

    unique = 0
    for path in sorted(tree.files):
        content = tree.files[path]
        digest = hashlib.sha256(content).hexdigest()
        head = content[:4]
        executable_bit = bool(tree.modes[path] & 0o111)
        in_library_dir = path.startswith(LIBRARY_DIRS)

        if (not in_library_dir and is_elf(head)
                and (executable_bit or path.startswith(EXECUTABLE_DIRS))):
            library = by_digest.get(digest)
            if library is None:
                library = sanitize_library_name(path, used, digest)
                used[library] = digest
                by_digest[digest] = library
                libraries[library] = content
                unique += 1
            sealed[path] = library
            continue
        payload[path] = content

    scripts = 0
    for path in sorted(tree.links):
        if path in sealed:
            continue
        resolved = resolve(path, tree.links, tree.files)
        if resolved is not None and resolved in sealed:
            sealed[path] = sealed[resolved]
        elif resolved is not None and resolved in payload:
            target = resolved
            if is_script(payload[resolved][:2]):
                scripts += 1
            kept_links[path] = target
    for path in sealed:
        payload.pop(path, None)
    for path in kept_links:
        payload.pop(path, None)

    return sealed, libraries, payload, kept_links, unique, scripts


# --------------------------------------------------------------------------- #
# output
# --------------------------------------------------------------------------- #

def write_payload(out_dir, abi, tree, sealed, libraries, payload, kept_links,
                  unique, scripts, sources):
    assets_dir = os.path.join(out_dir, "assets")
    jni_dir = os.path.join(out_dir, "jniLibs", abi)
    os.makedirs(assets_dir, exist_ok=True)
    os.makedirs(jni_dir, exist_ok=True)
    for stale in os.listdir(jni_dir):
        os.unlink(os.path.join(jni_dir, stale))

    userland = os.path.join(assets_dir, "userland-%s.zip" % abi)
    with zipfile.ZipFile(userland, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, content in sorted(payload.items()):
            archive.writestr(path, content)

    for library, content in sorted(libraries.items()):
        target = os.path.join(jni_dir, library)
        with open(target, "wb") as handle:
            handle.write(content)
        os.chmod(target, 0o755)

    with open(os.path.join(assets_dir, "sealed-exec-%s.txt" % abi), "w", encoding="utf-8") as handle:
        handle.write("# prefix-relative executable path\tJNI library (shipped by the APK)\n")
        for path, library in sorted(sealed.items()):
            handle.write("%s\t%s\n" % (path, library))

    with open(os.path.join(assets_dir, "sealed-symlinks-%s.txt" % abi), "w", encoding="utf-8") as handle:
        handle.write("# prefix-relative link\tprefix-relative target (data, created at install)\n")
        for path, target in sorted(kept_links.items()):
            handle.write("%s\t%s\n" % (path, target))

    problems = []
    with zipfile.ZipFile(userland) as archive:
        for info in archive.infolist():
            head = archive.read(info)[:4]
            if is_elf(head) and not info.filename.startswith(LIBRARY_DIRS):
                problems.append("executable ELF left in payload: %s" % info.filename)
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
        "executables": len(sealed),
        "libraries": len(libraries),
        "uniqueBinaries": unique,
        "dataFiles": len(payload),
        "symlinks": len(kept_links),
        "scripts": scripts,
        "userlandBytes": os.path.getsize(userland),
        "libraryBytes": sum(os.path.getsize(os.path.join(jni_dir, name))
                            for name in os.listdir(jni_dir)),
        "sources": sources,
        "librariesSha256": {
            name: hashlib.sha256(libraries[name]).hexdigest()
            for name in sorted(libraries)
        },
    }
    with open(os.path.join(assets_dir, "sealed-payload-%s.json" % abi), "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)

    print("sealed payload %s: %d executables -> %d unique binaries shipped as JNI "
          "libraries (%.1f MiB), %d data files (%.1f MiB), %d data links, %d scripts "
          "in the payload"
          % (abi, len(sealed), unique, report["libraryBytes"] / (1 << 20),
             len(payload), report["userlandBytes"] / (1 << 20),
             len(kept_links), scripts))
    return report


def build(bootstrap, abi, out_dir, deb_dir=None):
    if not os.path.isfile(bootstrap):
        raise SystemExit("bootstrap not found: %s" % bootstrap)

    tree = Tree()
    read_bootstrap(bootstrap, tree)
    sources = [{"kind": "bootstrap", "artifact": os.path.basename(bootstrap)}]

    if deb_dir and os.path.isdir(deb_dir):
        order = []
        manifest = os.path.join(deb_dir, "packages.json")
        if os.path.isfile(manifest):
            with open(manifest, encoding="utf-8") as handle:
                order = [entry["file"] for entry in
                         json.load(handle).get("packages", [])]
        available = sorted(name for name in os.listdir(deb_dir) if name.endswith(".deb"))
        for name in [n for n in order if n in available] + \
                    [n for n in available if n not in order]:
            files = read_deb(os.path.join(deb_dir, name), tree, name)
            sources.append({"kind": "deb", "artifact": name, "files": files})
            print("unpacked %-46s %5d files" % (name, files))

    sealed, libraries, payload, kept_links, unique, scripts = split(tree)
    if not sealed:
        raise SystemExit("refusing to build a sealed payload with no executables")
    return write_payload(out_dir, abi, tree, sealed, libraries, payload,
                         kept_links, unique, scripts, sources)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bootstrap", required=True, help="ABI-specific bootstrap zip")
    parser.add_argument("--abi", required=True, choices=["arm64-v8a", "x86_64"])
    parser.add_argument("--out", required=True, help="output directory")
    parser.add_argument("--deb-dir", help="directory of .deb files (and packages.json)")
    arguments = parser.parse_args()
    build(arguments.bootstrap, arguments.abi, arguments.out, arguments.deb_dir)


if __name__ == "__main__":
    main()
