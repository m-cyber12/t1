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
    assets/sealed-payload-id-<abi>.txt short identity, so the app can skip a
                                       re-extract when nothing changed
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

# The bootstrap carries the Termux packages' own test suites. Their binaries are
# built against the sanitizer runtimes, which are not part of a usable payload,
# and nothing in a harness ever runs them - so they are dropped by prefix rather
# than by chasing libraries that exist only to serve them.
EXCLUDED_PREFIXES = (
    "libexec/installed-tests/",
)

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
            if not name or name.startswith(EXCLUDED_PREFIXES):
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
# dpkg state
# --------------------------------------------------------------------------- #

# Fields that describe a .deb file rather than an installed package; dpkg keeps
# them in the repository index, not in /var/lib/dpkg/status.
DEB_ONLY_FIELDS = {
    "filename", "size", "md5sum", "sha1", "sha256", "sha512", "tag",
    "description-md5", "installed-size",
}


def deb_control(path):
    """The `control` file of a .deb, as text (empty when it cannot be read)."""
    for name, data in ar_members(path):
        if not name.startswith("control.tar"):
            continue
        if name.endswith(".xz"):
            data = lzma.decompress(data)
        elif name.endswith(".gz"):
            data = gzip.decompress(data)
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as tar:
            for member in tar:
                if normalize(member.name) in ("control", "./control"):
                    handle = tar.extractfile(member)
                    if handle is not None:
                        return handle.read().decode("utf-8", "replace")
    return ""


def status_stanza(control_text):
    """Turn a control paragraph into a status paragraph, or None."""
    fields = []
    name = None
    for line in control_text.splitlines():
        if not line.strip():
            continue
        if line[0] in " \t" and fields:      # continuation line
            fields[-1][1] += "\n" + line
            continue
        key, _, value = line.partition(":")
        key = key.strip()
        if not key:
            continue
        fields.append([key, value.strip()])
        if key.lower() == "package":
            name = value.strip()
    if not name:
        return None
    lines = ["Package: " + name, "Status: install ok installed"]
    for key, value in fields:
        if key.lower() in DEB_ONLY_FIELDS or key.lower() == "package":
            continue
        if key.lower() == "status":
            continue
        lines.append("%s: %s" % (key, value))
    return name, "\n".join(lines) + "\n"


def merge_status(existing, stanzas):
    """Merge status paragraphs into the bootstrap's /var/lib/dpkg/status.

    A sealed payload installs packages by unpacking them, so dpkg's own database
    still describes the bootstrap it came from: `apt` would offer to install
    packages whose files are already in the prefix, and would report versions
    that are not the ones shipped. Every paragraph for a shipped package is
    therefore replaced with the shipped control data and marked installed; a
    package the payload did not ship keeps whatever the bootstrap said.

    No `.list` files are written on purpose: a wrong path base in them would be
    worse than their absence (removal would act on paths that may not exist),
    and `Status` alone is what makes `apt` agree with reality.
    """
    blocks = []
    index = {}
    for block in existing.split("\n\n"):
        if not block.strip():
            continue
        name = None
        for line in block.splitlines():
            if line.lower().startswith("package:"):
                name = line.split(":", 1)[1].strip()
                break
        if name:
            index[name] = len(blocks)
        blocks.append(block.rstrip("\n"))
    replaced = 0
    added = 0
    for name, stanza in stanzas:
        if name in index:
            blocks[index[name]] = stanza.rstrip("\n")
            replaced += 1
        else:
            index[name] = len(blocks)
            blocks.append(stanza.rstrip("\n"))
            added += 1
    return "\n\n".join(blocks) + "\n", replaced, added



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
# ELF dependency check
# --------------------------------------------------------------------------- #

# Libraries the platform always provides. Anything else a sealed executable
# links against has to be in the payload (or the APK), or the binary dies at
# startup with "cannot locate symbol" / "library not found" on the device.
SYSTEM_LIBRARIES = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
    "libstdc++.so", "libc++_shared.so", "libc.so.6", "ld-android.so",
}

PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10


def elf_needed(content):
    """DT_NEEDED names of a 64-bit little-endian ELF, or [] if unparsable."""
    try:
        if content[:6] != b"\x7fELF\x02\x01":
            return []
        phoff = int.from_bytes(content[0x20:0x28], "little")
        phentsize = int.from_bytes(content[0x36:0x38], "little")
        phnum = int.from_bytes(content[0x38:0x3a], "little")
        if not 0 < phentsize <= 128 or not 0 < phnum <= 256:
            return []
        loads = []
        dynamic = None
        for index in range(phnum):
            base = phoff + index * phentsize
            kind = int.from_bytes(content[base:base + 4], "little")
            offset = int.from_bytes(content[base + 8:base + 16], "little")
            vaddr = int.from_bytes(content[base + 16:base + 24], "little")
            filesz = int.from_bytes(content[base + 32:base + 40], "little")
            if kind == PT_LOAD:
                loads.append((vaddr, offset, filesz))
            elif kind == PT_DYNAMIC:
                dynamic = (offset, filesz)
        if dynamic is None:
            return []
        entries = []
        offset, size = dynamic
        for at in range(offset, min(offset + size, len(content)) - 16, 16):
            tag = int.from_bytes(content[at:at + 8], "little", signed=True)
            value = int.from_bytes(content[at + 8:at + 16], "little")
            if tag == DT_NULL:
                break
            entries.append((tag, value))
        strtab = next((value for tag, value in entries if tag == DT_STRTAB), None)
        if strtab is None:
            return []
        strtab = strtab
        # The dynamic string table is addressed by virtual address; map it back
        # to a file offset through the PT_LOAD segment that contains it.
        file_offset = None
        for vaddr, seg_offset, seg_size in loads:
            if vaddr <= strtab < vaddr + seg_size:
                file_offset = seg_offset + (strtab - vaddr)
                break
        if file_offset is None:
            return []
        needed = []
        for tag, value in entries:
            if tag != DT_NEEDED:
                continue
            end = content.find(b"\x00", file_offset + value)
            if end < 0:
                continue
            needed.append(content[file_offset + value:end].decode("utf-8", "replace"))
        return needed
    except Exception:
        return []


def ensure_dependencies(sealed, libraries, payload, kept_links):
    """Make sure every sealed binary's libraries exist; link or fail loudly.

    Android resolves DT_NEEDED by file name. A package may ship the same library
    under a versioned name only (perl ships libperl.so.5.40.0 while the binary
    needs libperl.so), which works under dpkg because the postinst creates the
    link - and fails in a payload, where nothing runs postinsts. Rather than
    guess which packages do that, this reads the binaries.
    """
    by_basename = {}
    for path in payload:
        by_basename.setdefault(os.path.basename(path), []).append(path)
    added = []
    problems = []
    for path, library in sorted(sealed.items()):
        content = libraries.get(library)
        if content is None:
            continue
        for needed in elf_needed(content):
            candidates = by_basename.get(needed, [])
            if candidates:
                existing = new_link = "lib/" + needed
                if new_link not in payload and new_link not in kept_links:
                    kept_links[new_link] = candidates[0]
                    added.append((new_link, candidates[0]))
                continue
            if needed in SYSTEM_LIBRARIES or needed.startswith("ld-android"):
                continue
            # Not shipped under that name: a versioned sibling will do.
            stem = needed.split(".so")[0] + ".so"
            siblings = [path for name in by_basename
                        if name.startswith(stem) for path in by_basename[name]]
            if siblings:
                link = "lib/" + needed
                if link not in payload and link not in kept_links:
                    kept_links[link] = sorted(siblings)[0]
                    added.append((link, sorted(siblings)[0]))
                continue
            problems.append("%s needs %s (not in the payload)" % (path, needed))
    return added, problems


# --------------------------------------------------------------------------- #
# output
# --------------------------------------------------------------------------- #

def write_payload(out_dir, abi, tree, sealed, libraries, payload, kept_links,
                  unique, scripts, sources, prefix=""):
    assets_dir = os.path.join(out_dir, "assets")
    jni_dir = os.path.join(out_dir, "jniLibs", abi)
    os.makedirs(assets_dir, exist_ok=True)
    os.makedirs(jni_dir, exist_ok=True)
    for stale in os.listdir(jni_dir):
        os.unlink(os.path.join(jni_dir, stale))

    userland = os.path.join(assets_dir, "%suserland-%s.zip" % (prefix, abi))
    with zipfile.ZipFile(userland, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, content in sorted(payload.items()):
            archive.writestr(path, content)

    for library, content in sorted(libraries.items()):
        target = os.path.join(jni_dir, library)
        with open(target, "wb") as handle:
            handle.write(content)
        os.chmod(target, 0o755)

    with open(os.path.join(assets_dir, "%ssealed-exec-%s.txt" % (prefix, abi)), "w", encoding="utf-8") as handle:
        handle.write("# prefix-relative executable path\tJNI library (shipped by the APK)\n")
        for path, library in sorted(sealed.items()):
            handle.write("%s\t%s\n" % (path, library))

    # Per-file integrity data, in the shape the reference harness ships
    # (runtime-manifest.json) but line-oriented so a Python build, a Java kernel
    # and a TypeScript harness all read it with no parser at all. The installer
    # hashes while it writes, so verification costs no extra I/O on device.
    with open(os.path.join(assets_dir, "%ssealed-manifest-%s.txt" % (prefix, abi)), "w", encoding="utf-8") as handle:
        handle.write("# sha256\tsize\tprefix-relative path\n")
        for path, content in sorted(payload.items()):
            handle.write("%s\t%d\t%s\n" % (hashlib.sha256(content).hexdigest(), len(content), path))

    with open(os.path.join(assets_dir, "%ssealed-symlinks-%s.txt" % (prefix, abi)), "w", encoding="utf-8") as handle:
        handle.write("# prefix-relative link\tprefix-relative target (data, created at install)\n")
        for path, target in sorted(kept_links.items()):
            handle.write("%s\t%s\n" % (path, target))

    # A short, stable identity for this payload. The app compares it with what it
    # installed last time, so an unchanged payload is never extracted twice (a
    # first install of 13k files takes minutes on a phone) and a changed one is
    # always replaced - including across an app upgrade.
    identity = hashlib.sha256()
    for name in (("%ssealed-manifest" % prefix, "%ssealed-exec" % prefix, "%ssealed-symlinks" % prefix)):
        with open(os.path.join(assets_dir, "%s-%s.txt" % (name, abi)), "rb") as handle:
            identity.update(handle.read())
    identity.update(str(os.path.getsize(userland)).encode("ascii"))
    payload_id = identity.hexdigest()[:16]
    with open(os.path.join(assets_dir, "%ssealed-payload-id-%s.txt" % (prefix, abi)), "w", encoding="utf-8") as handle:
        handle.write(payload_id + "\n")

    problems = []
    with zipfile.ZipFile(userland) as archive:
        for info in archive.infolist():
            head = archive.read(info)[:4]
            if is_elf(head) and not info.filename.startswith(LIBRARY_DIRS):
                problems.append("executable ELF left in payload: %s" % info.filename)
    for name in sorted(os.listdir(jni_dir)):
        if not name.startswith("lib") or not name.endswith(".so"):
            problems.append("bad library name: %s" % name)
    manifest_path = os.path.join(assets_dir, "%ssealed-manifest-%s.txt" % (prefix, abi))
    manifest_lines = [line for line in open(manifest_path, encoding="utf-8").read().splitlines()
                      if line and not line.startswith("#")]
    if len(manifest_lines) != len(payload):
        problems.append("manifest lists %d files, payload has %d"
                        % (len(manifest_lines), len(payload)))
    for line in manifest_lines:
        digest, size, path = line.split("\t")
        content = payload.get(path)
        if content is None:
            problems.append("manifest names a file the payload lacks: %s" % path)
        elif hashlib.sha256(content).hexdigest() != digest or len(content) != int(size):
            problems.append("manifest entry does not match the payload: %s" % path)
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
    "manifestEntries": len(payload),
        "userlandBytes": os.path.getsize(userland),
        "libraryBytes": sum(os.path.getsize(os.path.join(jni_dir, name))
                            for name in os.listdir(jni_dir)),
        "sources": sources,
        "librariesSha256": {
            name: hashlib.sha256(libraries[name]).hexdigest()
            for name in sorted(libraries)
        },
    }
    with open(os.path.join(assets_dir, "%ssealed-payload-%s.json" % (prefix, abi)), "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)

    print("sealed payload %s: %d executables -> %d unique binaries shipped as JNI "
          "libraries (%.1f MiB), %d data files (%.1f MiB), %d data links, %d scripts "
          "in the payload"
          % (abi, len(sealed), unique, report["libraryBytes"] / (1 << 20),
             len(payload), report["userlandBytes"] / (1 << 20),
             len(kept_links), scripts))
    return report


def build(bootstrap, abi, out_dir, deb_dir=None, prefix=""):
    tree = Tree()
    sources = []
    if bootstrap:
        if not os.path.isfile(bootstrap):
            raise SystemExit("bootstrap not found: %s" % bootstrap)
        read_bootstrap(bootstrap, tree)
        sources.append({"kind": "bootstrap", "artifact": os.path.basename(bootstrap)})

    stanzas = []
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
            location = os.path.join(deb_dir, name)
            files = read_deb(location, tree, name)
            sources.append({"kind": "deb", "artifact": name, "files": files})
            print("unpacked %-46s %5d files" % (name, files))
            stanza = status_stanza(deb_control(location))
            if stanza is not None:
                stanzas.append(stanza)

    if stanzas:
        status_path = "var/lib/dpkg/status"
        existing = tree.files.get(status_path)
        text = existing.decode("utf-8", "replace") if existing else ""
        merged, replaced, added = merge_status(text, stanzas)
        tree.add_file(status_path, merged.encode("utf-8"),
                      tree.modes.get(status_path, 0o644), "dpkg-state")
        print("dpkg status: %d packages marked installed (%d replaced, %d added)"
              % (len(stanzas), replaced, added))

    sealed, libraries, payload, kept_links, unique, scripts = split(tree)
    if not sealed:
        raise SystemExit("refusing to build a sealed payload with no executables")

    added, problems = ensure_dependencies(sealed, libraries, payload, kept_links)
    for link, target in added:
        print("dependency link %-34s -> %s" % (link, target))
    if problems:
        for problem in problems:
            print("sealed-payload: %s" % problem, file=sys.stderr)
        raise SystemExit("sealed payload is missing %d shared library(ies)" % len(problems))
    return write_payload(out_dir, abi, tree, sealed, libraries, payload,
                         kept_links, unique, scripts, sources, prefix)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bootstrap",
                        help="ABI-specific bootstrap zip; omit to build a pack payload that "
                             "contains only the packages given in --deb-dir")
    parser.add_argument("--abi", required=True, choices=["arm64-v8a", "x86_64"])
    parser.add_argument("--out", required=True, help="output directory")
    parser.add_argument("--deb-dir", help="directory of .deb files (and packages.json)")
    parser.add_argument("--asset-prefix", default="",
                        help="prefix for the emitted asset names, e.g. 'pack-buildtools-'")
    parser.add_argument("--log", help="write a transcript here (the build reads it on failure)")
    arguments = parser.parse_args()
    if arguments.log:
        # Gradle cannot capture a child's output when the child fails, and a
        # missing library is only useful if the message survives. Tee here.
        handle = open(arguments.log, "w", buffering=1)

        class Tee:
            def __init__(self, *streams):
                self.streams = streams

            def write(self, text):
                for stream in self.streams:
                    stream.write(text)

            def flush(self):
                for stream in self.streams:
                    stream.flush()

        sys.stdout = Tee(sys.stdout, handle)
        sys.stderr = Tee(sys.stderr, handle)
    try:
        build(arguments.bootstrap, arguments.abi, arguments.out, arguments.deb_dir,
              arguments.asset_prefix)
    except BaseException as error:
        print("sealed-payload: %s" % error, file=sys.stderr)
        raise


if __name__ == "__main__":
    main()
