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
import gzip
import hashlib
import io
import json
import lzma
import os
import re
import sys
import urllib.error
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


def debian_version_compare(left, right):
    """Debian version ordering (policy 5.6.12), enough for dependency checks."""
    def split(version):
        version = version.strip()
        epoch = 0
        if ":" in version:
            head, version = version.split(":", 1)
            try:
                epoch = int(head)
            except ValueError:
                epoch = 0
        if "-" in version:
            upstream, revision = version.rsplit("-", 1)
        else:
            upstream, revision = version, "0"
        return epoch, upstream, revision

    def order(char):
        if char == "":
            return 0          # end of string sorts before any real character
        if char == "~":
            return -1
        if char.isdigit():
            return 0
        if char.isalpha():
            return ord(char)
        return ord(char) + 256

    def compare_part(left, right):
        i = j = 0
        while i < len(left) or j < len(right):
            # non-digit run
            while (i < len(left) and not left[i].isdigit()) or \
                    (j < len(right) and not right[j].isdigit()):
                left_char = left[i] if i < len(left) else ""
                right_char = right[j] if j < len(right) else ""
                if order(left_char or "") != order(right_char or ""):
                    return -1 if order(left_char or "") < order(right_char or "") else 1
                i += 1
                j += 1
            # digit run
            start_left, start_right = i, j
            while i < len(left) and left[i].isdigit():
                i += 1
            while j < len(right) and right[j].isdigit():
                j += 1
            left_number = int(left[start_left:i] or "0")
            right_number = int(right[start_right:j] or "0")
            if left_number != right_number:
                return -1 if left_number < right_number else 1
        return 0

    left_epoch, left_upstream, left_revision = split(left)
    right_epoch, right_upstream, right_revision = split(right)
    if left_epoch != right_epoch:
        return -1 if left_epoch < right_epoch else 1
    result = compare_part(left_upstream, right_upstream)
    if result:
        return result
    return compare_part(left_revision, right_revision)


def satisfies(version, operator, required):
    if not version or not operator:
        return True
    comparison = debian_version_compare(version, required)
    if operator in (">=",):
        return comparison >= 0
    if operator in ("<=",):
        return comparison <= 0
    if operator in ("=", "=="):
        return comparison == 0
    if operator in ("<<", "<"):
        return comparison < 0
    if operator in (">>", ">"):
        return comparison > 0
    return True


def bootstrap_packages(bootstrap_zip):
    """Package name -> version, from the bootstrap's own dpkg database."""
    names = {}
    if not bootstrap_zip or not os.path.isfile(bootstrap_zip):
        return names
    with zipfile.ZipFile(bootstrap_zip) as archive:
        candidates = [n for n in archive.namelist()
                      if n.endswith("var/lib/dpkg/status")]
        if not candidates:
            return names
        text = archive.read(candidates[0]).decode("utf-8", "replace")
    for stanza in parse_stanzas(text):
        if stanza.get("Package"):
            names[stanza["Package"]] = stanza.get("Version", "")
    return names


def split_dependencies(value):
    """'a (>= 1) | b, c:any' -> [[('a','>=','1'), ('b','','')], [('c','','')]]."""
    if not value:
        return []
    groups = []
    for group in value.split(","):
        alternatives = []
        for alternative in group.split("|"):
            constraint = re.search(r"\(([^)]*)\)", alternative)
            operator = required = ""
            if constraint:
                pieces = constraint.group(1).split()
                if len(pieces) >= 2:
                    operator, required = pieces[0], pieces[1]
                elif pieces:
                    operator, required = "=", pieces[0]
            name = re.sub(r"\([^)]*\)", "", alternative)
            name = name.replace(":any", "").strip()
            name = name.split("[")[0].strip()
            if name:
                alternatives.append((name, operator, required))
        if alternatives:
            groups.append(alternatives)
    return groups


def index_candidates(repo, suite, arch):
    base = "%s/dists/%s/main/binary-%s/Packages" % (repo.rstrip("/"), suite, arch)
    return [base + ".xz", base + ".gz", base]


def load_index(repo, suite, arch):
    text = None
    tried = []
    for url in index_candidates(repo, suite, arch):
        log("index: %s" % url)
        try:
            raw = fetch(url)
        except urllib.error.HTTPError as error:
            tried.append("%s -> HTTP %s" % (url, error.code))
            continue
        except urllib.error.URLError as error:
            tried.append("%s -> %s" % (url, error.reason))
            continue
        if url.endswith(".xz"):
            try:
                text = lzma.decompress(raw).decode("utf-8", "replace")
            except lzma.LZMAError:
                tried.append("%s -> not xz" % url)
                continue
        elif url.endswith(".gz"):
            try:
                text = gzip.decompress(raw).decode("utf-8", "replace")
            except OSError:
                tried.append("%s -> not gzip" % url)
                continue
        else:
            text = raw.decode("utf-8", "replace")
        break
    if text is None:
        raise SystemExit("could not read a package index from %s\n  %s"
                         % (repo, "\n  ".join(tried)))
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


def resolve(requested, packages, provided, bootstrap, skip):
    """Breadth-first dependency resolution; returns names in install order.

    A dependency the bootstrap already provides is skipped only when the
    bootstrap's version actually satisfies the constraint. Keeping a stale
    bootstrap library because it happens to share a name is how a sealed build
    links a new binary against an old libc++ and dies with "cannot locate
    symbol" - the bootstrap is pinned, the package set is not.
    """
    order = []
    seen = set()
    queue = [(name, "", "", "requested") for name in requested]
    missing = []
    while queue:
        name, operator, required, reason = queue.pop(0)
        key = (name, operator, required)
        if key in seen:
            continue
        seen.add(key)
        provided_version = bootstrap.get(name)
        if provided_version:
            if satisfies(provided_version, operator, required):
                log("skip %-22s %-12s (%s; bootstrap has %s)"
                    % (name, provided_version, reason, provided_version))
                continue
            log("refresh %-19s bootstrap has %s, %s needs %s"
                % (name, provided_version, reason, required))
        elif name in skip:
            log("skip %-22s (%s, excluded)" % (name, reason))
            continue
        stanza = packages.get(name)
        if stanza is None:
            virtual = provided.get(name)
            if virtual is not None:
                log("virtual %-19s -> %s" % (name, virtual))
                queue.append((virtual, operator, required, "virtual:" + name))
                continue
            missing.append(name)
            log("MISSING %-19s (%s)" % (name, reason))
            continue
        order.append(name)
        for field in DEPENDENCY_FIELDS:
            for alternatives in split_dependencies(stanza.get(field, "")):
                chosen = next((alt for alt in alternatives
                               if alt[0] in packages or alt[0] in provided
                               or alt[0] in bootstrap), None)
                if chosen is None:
                    missing.append("/".join(alt[0] for alt in alternatives))
                    log("MISSING dependency %-14s of %s"
                        % ("/".join(alt[0] for alt in alternatives), name))
                    continue
                queue.append((chosen[0], chosen[1], chosen[2],
                              "%s:%s" % (name, field.lower())))
    # A package fetched later must not be replaced by an earlier entry for the
    # same name (the refresh case above), and install order stays as resolved.
    deduped = []
    for name in order:
        if name not in deduped:
            deduped.append(name)
    return deduped, missing


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
    bootstrap = bootstrap_packages(arguments.bootstrap)
    skip = set(ALWAYS_SKIP)
    log("bootstrap provides %d packages; index has %d"
        % (len(bootstrap), len(packages)))

    order, missing = resolve(arguments.packages, packages, provided,
                             bootstrap, skip)
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
