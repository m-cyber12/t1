#!/usr/bin/env python3
"""Resolves and downloads the Termux packages a sealed payload needs.

A sealed build cannot run `apt` at install time: Play's Device & Network Abuse
policy forbids fetching executable code from anywhere but the store, and the
exec model wants every binary to come out of the APK. So the same packages the
apt-based build installs on first run are fetched *at build time*, pinned and
hashed, and end up inside the payload.

This script does the build-time half:

1. read the bootstrap's own `var/lib/dpkg/status` (it is inside the bootstrap
   zip) to know which packages the shell already provides, so they are not
   fetched twice;
2. read the repository's `Packages` index for the exact architecture;
3. resolve Depends/Pre-Depends transitively (alternatives, version
   constraints, `:any` qualifiers, and `Provides` for virtual names);
4. download each `.deb` and verify its SHA-256 from the index;
5. write `packages.txt` (name, version, dependency order) for the log.

Usage:
    fetch-core-debs.py --arch aarch64 --out build/debs \
        --bootstrap bootstrap-aarch64.zip \
        --packages git ripgrep python python-pip nodejs npm perl ruby jq tree file openssh

Nothing is installed here; `build-sealed-payload.py` unpacks the result.
"""

import argparse
import hashlib
import io
import json
import lzma
import os
import re
import sys
import urllib.parse
import urllib.request
import zipfile

DEFAULT_REPO = "https://packages-cf.termux.dev/apt/termux-main"
# Never fetched: provided by the bootstrap, or meaningless in a sealed payload
# (the app owns its own lifecycle).
ALWAYS_SKIP = {"termux-am", "termux-tools", "termux-exec", "command-not-found"}

DEPENDENCY_FIELDS = ("Pre-Depends", "Depends")


def log(message):
    print(message, file=sys.stderr)


def open_url(url):
    """urlopen that also understands file:// so the resolver can be tested."""
    if url.startswith("file://"):
        return urllib.request.urlopen(url)
    request = urllib.request.Request(url, headers={"User-Agent": "coding-harness-build"})
    return urllib.request.urlopen(request, timeout=120)


def fetch(url):
    with open_url(url) as response:
        return response.read()


def parse_stanzas(text):
    """Debian control format -> list of dicts (repeated fields joined)."""
    stanzas = []
    current = {}
    key = None
    for raw in text.splitlines():
        if not raw.strip():
            if current:
                stanzas.append(current)
                current = {}
                key = None
            continue
        if raw[0] in " \t" and key:
            current[key] = current[key] + "\n" + raw.strip()
            continue
        if ":" not in raw:
            continue
        key, value = raw.split(":", 1)
        key = key.strip()
        current[key] = value.strip()
    if current:
        stanzas.append(current)
    return stanzas


def bootstrap_packages(bootstrap_zip):
    """Package names the bootstrap already ships, from its dpkg status."""
    names = set()
    if not bootstrap_zip or not os.path.isfile(bootstrap_zip):
        return names
    with zipfile.ZipFile(bootstrap_zip) as archive:
        candidates = [n for n in archive.namelist()
                      if n.endswith("var/lib/dpkg/status")]
        if not candidates:
            return names
        text = archive.read(candidates[0]).decode("utf-8", "replace")
    for stanza in parse_stanzas(text):
        if stanza.get("Package") and stanza.get("Status", "").endswith("installed"):
            names.add(stanza["Package"])
        elif stanza.get("Package"):
            names.add(stanza["Package"])
    return names


def split_dependencies(value):
    """'a (>= 1) | b, c:any' -> [['a','b'], ['c']] (alternatives kept)."""
    if not value:
        return []
    groups = []
    for group in value.split(","):
        alternatives = []
        for alternative in group.split("|"):
            name = re.sub(r"\([^)]*\)", "", alternative)
            name = name.replace(":any", "").strip()
            name = name.split("[")[0].strip()
            if name:
                alternatives.append(name)
        if alternatives:
            groups.append(alternatives)
    return groups


def index_url(repo, suite, arch):
    return "%s/dists/%s/main/binary-%s/Packages.xz" % (repo.rstrip("/"), suite, arch)


def load_index(repo, suite, arch):
    url = index_url(repo, suite, arch)
    log("index: %s" % url)
    raw = fetch(url)
    try:
        text = lzma.decompress(raw).decode("utf-8", "replace")
    except lzma.LZMAError:
        text = raw.decode("utf-8", "replace")
    packages = {}
    provided = {}
    for stanza in parse_stanzas(text):
        name = stanza.get("Package")
        if not name:
            continue
        packages[name] = stanza
        for virtual in stanza.get("Provides", "").split(","):
            virtual = re.sub(r"\([^)]*\)", "", virtual).strip()
            if virtual:
                provided.setdefault(virtual, name)
    return packages, provided


def resolve(requested, packages, provided, prerequested, skip):
    """Breadth-first dependency resolution; returns names in install order."""
    order = []
    seen = set()
    queue = [(name, "requested") for name in requested]
    missing = []
    while queue:
        name, reason = queue.pop(0)
        if name in seen:
            continue
        seen.add(name)
        if name in skip or name in prerequested:
            log("skip %-22s (%s, provided by the bootstrap)" % (name, reason))
            continue
        stanza = packages.get(name)
        if stanza is None:
            virtual = provided.get(name)
            if virtual is not None:
                log("virtual %-19s -> %s" % (name, virtual))
                queue.append((virtual, "virtual:" + name))
                continue
            missing.append(name)
            log("MISSING %-19s (%s)" % (name, reason))
            continue
        order.append(name)
        for field in DEPENDENCY_FIELDS:
            for alternatives in split_dependencies(stanza.get(field, "")):
                chosen = next((alt for alt in alternatives
                               if alt in packages or alt in provided), None)
                if chosen is None:
                    missing.append("/".join(alternatives))
                    log("MISSING dependency %-14s of %s" % ("/".join(alternatives), name))
                    continue
                queue.append((chosen, "%s:%s" % (name, field.lower())))
    return order, missing


def download_deb(repo, stanza, out_dir, arch):
    filename = stanza.get("Filename")
    if not filename:
        raise SystemExit("package %s has no Filename in the index" % stanza.get("Package"))
    name = stanza["Package"]
    version = stanza.get("Version", "?")
    url = "%s/%s" % (repo.rstrip("/"), filename.lstrip("/"))
    destination = os.path.join(out_dir, os.path.basename(filename))
    expected = stanza.get("SHA256", "").strip().lower()
    if os.path.isfile(destination):
        digest = hashlib.sha256(open(destination, "rb").read()).hexdigest()
        if not expected or digest == expected:
            return destination, version, digest
        log("cached %s has the wrong hash; re-downloading" % name)
    log("fetch %-22s %-28s %s" % (name, version, os.path.basename(filename)))
    data = fetch(url)
    digest = hashlib.sha256(data).hexdigest()
    if expected and digest != expected:
        raise SystemExit("SHA-256 mismatch for %s\n  index: %s\n  file:  %s"
                         % (url, expected, digest))
    temporary = destination + ".part"
    with open(temporary, "wb") as handle:
        handle.write(data)
    os.replace(temporary, destination)
    return destination, version, digest


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--arch", required=True, choices=["aarch64", "x86_64"],
                        help="Termux architecture name (not the Android ABI name)")
    parser.add_argument("--out", required=True, help="directory for the .debs")
    parser.add_argument("--packages", nargs="+", required=True)
    parser.add_argument("--bootstrap", help="bootstrap zip, to skip what it ships")
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--suite", default="stable")
    arguments = parser.parse_args()

    os.makedirs(arguments.out, exist_ok=True)
    packages, provided = load_index(arguments.repo, arguments.suite, arguments.arch)
    prerequested = bootstrap_packages(arguments.bootstrap)
    skip = set(ALWAYS_SKIP) | prerequested
    log("bootstrap provides %d packages; index has %d"
        % (len(prerequested), len(packages)))

    order, missing = resolve(arguments.packages, packages, provided,
                             prerequested, skip)
    if missing:
        print("unresolved dependencies: %s" % ", ".join(sorted(set(missing))), file=sys.stderr)
        return 1

    manifest = []
    for name in order:
        stanza = packages[name]
        path, version, digest = download_deb(
            arguments.repo, stanza, arguments.out, arguments.arch)
        manifest.append({
            "package": name,
            "version": version,
            "file": os.path.basename(path),
            "sha256": digest,
            "installedSize": stanza.get("Installed-Size", "0"),
        })

    with open(os.path.join(arguments.out, "packages.json"), "w", encoding="utf-8") as handle:
        json.dump({"arch": arguments.arch, "repo": arguments.repo,
                   "suite": arguments.suite, "packages": manifest}, handle,
                  indent=2, sort_keys=True)
    total = sum(int(entry["installedSize"] or 0) for entry in manifest)
    print("fetched %d packages (%d KiB installed) for %s"
          % (len(manifest), total, arguments.arch))
    return 0


if __name__ == "__main__":
    sys.exit(main())
