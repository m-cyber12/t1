#!/usr/bin/env python3
"""End-to-end test of the payload pipeline against a synthetic repository.

Builds a fake Termux repo (Packages index + .deb files), resolves it with
fetch-core-debs.py, splits the result with build-sealed-payload.py, and asserts
the properties the sealed build depends on:

  * dependencies resolve transitively, alternatives and virtual names included;
  * a package the bootstrap already provides is not fetched;
  * SHA-256 mismatches are rejected;
  * hard-linked binaries become ONE library referenced by several paths;
  * scripts stay data, libraries stay data, executables become libraries;
  * the output contains no executable ELF in the payload.

Run: python3 scripts/tests/test-sealed-payload.py
"""

import hashlib
import io
import json
import lzma
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
sys.path.insert(0, SCRIPTS)

ELF = b"\x7fELF\x02\x01\x01" + b"\x00" * 57  # distinguishable by content below


def elf(tag):
    return ELF + tag.encode()


def make_deb(path, files, links=None, depends=None):
    """files: path -> bytes; links: path -> target; one control member is added."""
    links = links or {}
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as tar:
        info = tarfile.TarInfo("data/data/com.termux/files/usr")
        info.type = tarfile.DIRTYPE
        tar.addfile(info)
        for name, content in files.items():
            full = "./data/data/com.termux/files/usr/" + name
            info = tarfile.TarInfo(full)
            info.size = len(content)
            info.mode = 0o755 if name.startswith("bin/") else 0o644
            tar.addfile(info, io.BytesIO(content))
        for name, target in links.items():
            full = "./data/data/com.termux/files/usr/" + name
            info = tarfile.TarInfo(full)
            info.type = tarfile.SYMTYPE
            info.linkname = target
            tar.addfile(info)
    data = lzma.compress(buffer.getvalue())

    control = ("Package: %s\nVersion: 1.0\n" % os.path.basename(path)).encode()
    members = [(b"debian-binary", b"2.0\n"),
               (b"control.tar.gz", b""),
               (b"data.tar.xz", data)]
    with open(path, "wb") as handle:
        handle.write(b"!<arch>\n")
        for name, payload in members:
            if name == b"control.tar.gz":
                continue
            header = (name + b"/").ljust(16) + b"0".ljust(12) + b"0".ljust(6) \
                + b"0".ljust(6) + b"100644".ljust(8) + str(len(payload)).encode().ljust(10) \
                + b"`\n"
            handle.write(header)
            handle.write(payload)
            if len(payload) % 2:
                handle.write(b"\n")


def elf_with_needed(needed):
    """A minimal 64-bit LE ELF whose dynamic section lists one DT_NEEDED."""
    strtab = b"\x00" + needed.encode() + b"\x00"          # offset 0 is the empty name
    strtab_offset = 64 + 2 * 56                              # after header + 2 phdrs
    dyn_offset = strtab_offset + len(strtab)
    dynamic = b""
    dynamic += (1).to_bytes(8, "little") + (1).to_bytes(8, "little")   # DT_NEEDED -> "libx"
    dynamic += (5).to_bytes(8, "little") + strtab_offset.to_bytes(8, "little")
    dynamic += (0).to_bytes(8, "little") + (0).to_bytes(8, "little")
    total = dyn_offset + len(dynamic)

    ident = b"\x7fELF\x02\x01\x01" + b"\x00" * 9
    header = ident + (2).to_bytes(2, "little") + (183).to_bytes(2, "little")  # ET_DYN, x86_64
    header += (1).to_bytes(4, "little") + (0).to_bytes(8, "little")            # version, entry
    header += (64).to_bytes(8, "little") + (0).to_bytes(8, "little")           # phoff, shoff
    header += (0).to_bytes(4, "little")                                        # flags
    header += (64).to_bytes(2, "little") + (56).to_bytes(2, "little") + (2).to_bytes(2, "little")
    header += (0).to_bytes(2, "little") + (0).to_bytes(2, "little") + (0).to_bytes(2, "little")
    header = header.ljust(64, b"\x00")

    def phdr(kind, offset, size, flags):
        return (kind.to_bytes(4, "little") + flags.to_bytes(4, "little")
                + offset.to_bytes(8, "little") + offset.to_bytes(8, "little")
                + (0).to_bytes(8, "little") + size.to_bytes(8, "little")
                + size.to_bytes(8, "little") + (0x1000).to_bytes(8, "little"))

    body = header + phdr(1, 0, total, 5) + phdr(2, dyn_offset, len(dynamic), 6)
    blob = body + strtab + dynamic
    assert len(blob) == total, (len(blob), total)
    return blob


def stanza(name, version, filename, data, depends=None, provides=None):
    text = "Package: %s\nVersion: %s\nArchitecture: aarch64\nFilename: %s\n" \
           "SHA256: %s\nInstalled-Size: %d\n" % (
               name, version, filename, hashlib.sha256(data).hexdigest(), len(data) // 1024)
    if depends:
        text += "Depends: %s\n" % depends
    if provides:
        text += "Provides: %s\n" % provides
    return text + "\n"


def run(command, expect=0):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != expect:
        print(result.stdout)
        print(result.stderr, file=sys.stderr)
        raise SystemExit("command failed (%d, expected %d): %s"
                         % (result.returncode, expect, " ".join(command)))
    return result


def main():
    root = tempfile.mkdtemp(prefix="sealed-payload-test-")
    try:
        repo = os.path.join(root, "repo")
        pool = os.path.join(repo, "pool")
        os.makedirs(pool)
        os.makedirs(os.path.join(repo, "dists/stable/main/binary-aarch64"))

        # bootstrap: bash + a library + its own dpkg database
        bootstrap = os.path.join(root, "bootstrap-aarch64.zip")
        with zipfile.ZipFile(bootstrap, "w") as archive:
            archive.writestr("bin/bash", elf("bash"))
            archive.writestr("bin/sh", b"stub")
            archive.writestr("lib/libssl.so.3", elf("libssl"))
            archive.writestr("var/lib/dpkg/status",
                             "Package: bash\nStatus: install ok installed\nVersion: 5.3\n\n"
                             "Package: libandroid-support\nStatus: install ok installed\n\n"
                             "Package: libc++\nStatus: install ok installed\nVersion: 26.0\n\n")
        # SYMLINKS.txt uses Termux's arrow notation
        with zipfile.ZipFile(bootstrap, "a") as archive:
            archive.writestr("SYMLINKS.txt", "/data/data/com.termux/files/usr/bin/bash\u2190bin/sh")
            archive.writestr("share/doc/readme.txt", b"docs")

        # packages: python (dep -> libpython -> libffi | libffi8), git (hardlinks),
        # perl (script alias), and a package the bootstrap already provides.
        deb = os.path.join(pool, "python.deb")
        make_deb(deb, {"bin/python3.12": elf("python"), "lib/python3.12/os.py": b"# os\n"},
                 links={"bin/python": "python3.12"})
        data = open(deb, "rb").read()

        deblib = os.path.join(pool, "libpython.deb")
        make_deb(deblib, {"lib/libpython3.12.so": elf("libpython")})
        libdata = open(deblib, "rb").read()

        deffi = os.path.join(pool, "libffi8.deb")
        make_deb(deffi, {"lib/libffi.so.8": elf("libffi")})
        ffidata = open(deffi, "rb").read()

        degit = os.path.join(pool, "git.deb")
        make_deb(degit, {"bin/git": elf("git-core"), "libexec/git-core/git-add": elf("git-core"),
                         "libexec/git-core/git-commit": elf("git-core"),
                         "share/git/template": b"template"})
        gitdata = open(degit, "rb").read()

        # A package whose dependency the bootstrap *appears* to satisfy but does
        # not: the bootstrap ships libc++ 26, node needs >= 27. Skipping it by
        # name alone links node against an old library ("cannot locate symbol").
        denode = os.path.join(pool, "nodejs.deb")
        make_deb(denode, {"bin/node": elf("node")})
        nodedata = open(denode, "rb").read()
        delibcxx = os.path.join(pool, "libc++.deb")
        make_deb(delibcxx, {"lib/libc++_shared.so": elf("libc++27")})
        libcxxdata = open(delibcxx, "rb").read()

        deperl = os.path.join(pool, "perl.deb")
        make_deb(deperl, {"bin/perl": elf_with_needed("libperl.so"),
                          "lib/libperl.so.5.40.0": elf("libperl"),
                          "bin/perl5.40.0": elf("perl"),
                          "share/perl5/core_perl/find.pl": b"#!/usr/bin/perl\n"},
                 links={"bin/core_perl/find": "/data/data/com.termux/files/usr/share/perl5/core_perl/find.pl"})
        perldata = open(deperl, "rb").read()

        debbash = os.path.join(pool, "bash.deb")
        make_deb(debbash, {"bin/bash": elf("bash")})
        bashdata = open(debbash, "rb").read()

        index = "".join([
            stanza("python", "3.12.1", "pool/python.deb", data,
                   depends="libpython (>= 3.12), libffi | libffi8"),
            stanza("libpython", "3.12.1", "pool/libpython.deb", libdata),
            stanza("libffi8", "3.4", "pool/libffi8.deb", ffidata, provides="libffi"),
            stanza("git", "2.48.1", "pool/git.deb", gitdata),
            stanza("nodejs", "24.0.0", "pool/nodejs.deb", nodedata,
                   depends="libc++ (>= 27)"),
            stanza("libc++", "28.0", "pool/libc++.deb", libcxxdata),
            stanza("perl", "5.40.1", "pool/perl.deb", perldata),
            stanza("bash", "5.3", "pool/bash.deb", bashdata),
        ])
        with open(os.path.join(repo, "dists/stable/main/binary-aarch64/Packages.xz"), "wb") as handle:
            handle.write(lzma.compress(index.encode()))

        debs = os.path.join(root, "debs")
        fetched = run([sys.executable, os.path.join(SCRIPTS, "fetch-core-debs.py"),
                       "--arch", "aarch64", "--out", debs,
                       "--bootstrap", bootstrap,
                       "--repo", "file://" + repo,
                       "--packages", "python", "git", "perl", "bash", "nodejs"])
        fetched_names = sorted(name for name in os.listdir(debs) if name.endswith(".deb"))
        assert fetched_names == ["git.deb", "libc++.deb", "libffi8.deb", "libpython.deb",
                                 "nodejs.deb", "perl.deb", "python.deb"], fetched_names
        # the version constraint overrides "the bootstrap has it"
        assert "refresh libc++" in fetched.stderr, fetched.stderr
        assert "skip bash" in fetched.stderr, fetched.stderr
        assert "virtual" in fetched.stderr, fetched.stderr

        out = os.path.join(root, "out")
        run([sys.executable, os.path.join(SCRIPTS, "build-sealed-payload.py"),
             "--bootstrap", bootstrap, "--abi", "arm64-v8a", "--out", out,
             "--deb-dir", debs])

        report = json.load(open(os.path.join(out, "assets/sealed-payload-arm64-v8a.json")))
        execs = dict(line.split("\t") for line in
                     open(os.path.join(out, "assets/sealed-exec-arm64-v8a.txt")).read().splitlines()
                     if line and not line.startswith("#"))
        links = dict(line.split("\t") for line in
                     open(os.path.join(out, "assets/sealed-symlinks-arm64-v8a.txt")).read().splitlines()
                     if line and not line.startswith("#"))

        # hard links collapse to one library
        assert execs["bin/git"] == execs["libexec/git-core/git-add"] == execs["libexec/git-core/git-commit"], execs
        # perl's launcher is its own ELF (it links against libperl); the
        # versioned binary is separate content and gets its own library
        assert execs["bin/perl"] != execs["bin/perl5.40.0"], execs
        # the alias link resolves into the sealed set (it is an executable
        # name, so it must point at a library, not sit in the payload)
        assert execs["bin/python"] == execs["bin/python3.12"], execs
        assert "bin/python" not in links
        # a script link stays a data link, and the script itself is data
        assert links.get("bin/core_perl/find") == "share/perl5/core_perl/find.pl", links
        # libraries are never executables
        assert "lib/libpython3.12.so" not in execs
        # a binary that needs libperl.so gets the link its package omits
        assert links.get("lib/libperl.so") == "lib/libperl.so.5.40.0", links
        # the audit of the produced payload
        assert report["libraries"] == report["uniqueBinaries"]
        assert report["scripts"] >= 1, report
        parser = subprocess.run([sys.executable, "-c",
            "import importlib.util;s=importlib.util.spec_from_file_location('b',%r);"
            "m=importlib.util.module_from_spec(s);s.loader.exec_module(m);"
            "print(m.elf_needed(open('/bin/true','rb').read()))" % os.path.join(SCRIPTS, "build-sealed-payload.py")],
            capture_output=True, text=True)
        assert parser.returncode == 0 and "libc" in parser.stdout, parser.stderr
        assert report["executables"] >= 6, report
        assert not any("executable ELF" in line for line in report.get("problems", []))

        # zip contains no executable ELF
        with zipfile.ZipFile(os.path.join(out, "assets/userland-arm64-v8a.zip")) as archive:
            for info in archive.infolist():
                head = archive.read(info)[:4]
                assert not (head == b"\x7fELF" and not info.filename.startswith("lib/")), info.filename

        # hash mismatch is fatal
        with open(os.path.join(repo, "dists/stable/main/binary-aarch64/Packages.xz"), "wb") as handle:
            handle.write(lzma.compress(index.replace(
                hashlib.sha256(gitdata).hexdigest(), "0" * 64).encode()))
        broken = run([sys.executable, os.path.join(SCRIPTS, "fetch-core-debs.py"),
                      "--arch", "aarch64", "--out", os.path.join(root, "debs2"),
                      "--bootstrap", bootstrap, "--repo", "file://" + repo,
                      "--packages", "git"], expect=1)
        assert "SHA-256 mismatch" in broken.stderr, broken.stderr

        print("test-sealed-payload: OK")
        print("  executables=%d libraries=%d data=%d links=%d scripts=%d"
              % (report["executables"], report["libraries"], report["dataFiles"],
                 report["symlinks"], report["scripts"]))
    finally:
        shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    main()
