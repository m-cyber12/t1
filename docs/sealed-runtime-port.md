# The sealed runtime, as a module the main harness can take

This is the merging instruction for `m-cyber12/OpenCode-app`: what to copy, what
to leave, and what the invariants are. It exists because "port the harness" was
the wrong unit — the part that took a device, an emulator and four failed runs to
get right is small, and it is now a module with no Android dependency.

## What moves

`sealed-runtime/` is a plain Java library (`java-library`, no Android SDK). Seven
classes, ~1,100 lines including tests:

| Class | Responsibility | Android APIs |
|---|---|---|
| `SealedInstaller` | the whole install: extract, verify, link, seal | none |
| `PayloadExtractor` | unzip into a prefix, verify each file while writing | none |
| `RuntimeManifest` | `sha256 ⇥ size ⇥ path` records, parse/verify/digest | none |
| `SealedLayout` | point each executable at the library the APK shipped | none |
| `ExecAudit` | measure "is this install publishable?" (binaries outside the APK) | none |
| `ExecutableProbe` | classify a file by its first bytes: ELF / script / data | none |
| `ExecutableKind` | the enum for the above | none |

What stays behind in `harness-core` (Android, and *should* stay behind):
`SealedPayloadInstaller` — the adapter that picks the ABI, opens `assets/`, and
names the native library directory. It is ~180 lines and contains no logic: it
builds a `SealedInstaller.Request` and forwards a `Result`. If OpenCode has no
`Context`, that class does not exist at all and the request is built from its own
paths.

## The three files the build emits

Produced by `scripts/build-sealed-payload.py`, one set per ABI:

```
assets/userland-<abi>.zip          data only; nothing in it is ever execve()d
assets/sealed-manifest-<abi>.txt   sha256 \<TAB> size \<TAB> path, one line per file
assets/sealed-exec-<abi>.txt       path \<TAB> lib<name>.so  (must exist in jniLibs/<abi>/)
assets/sealed-symlinks-<abi>.txt   path \<TAB> prefix-relative target (data, created at install)
jniLibs/<abi>/lib<name>.so         every executable, renamed; real files (extractNativeLibs=true)
```

The manifest is the reference harness's `runtime-manifest.json` in a
line-oriented form: the Python build writes it, Java verifies it, and the
TypeScript port will read it with `split("\t")` rather than a JSON schema. In
this repository it is newly added; the kernel treats a missing manifest as "no
verification", so an older payload still installs and the report says
`0 verified`.

## What to copy into OpenCode-app

1. `sealed-runtime/src/main/java/io/mcyber12/sealed/*` into
   `app/src/main/java/…` (or a sibling :sealed module if the app is Kotlin-only
   and you want isolation). No changes are needed; the package name is the only
   thing to rename if you want it under `io.github.mcyber12.opencode`.
2. `sealed-runtime/src/test/java/.../SealedInstallerTest.java` and
   `ExecSealingTest.java`. They run on the JVM in seconds and cover exactly the
   four failures that cost device runs here (see below).
3. `scripts/build-sealed-payload.py` if OpenCode wants to ship a Termux-shaped
   userland too. Its input is a Termux bootstrap zip plus a directory of `.deb`s;
   its output is the four assets above. Nothing about it is harness-specific.
4. The build-side facts, in the app's Gradle file:
   `jniLibs.useLegacyPackaging = true` (so the libraries are real files in
   `nativeLibraryDir`, which is what makes them executable), and
   `extractNativeLibs="true"` in the manifest.

## What not to copy

* **The provisioning tiers** (`core/provision/*`, 12 classes). They exist to run
  `apt` and `dpkg`, which is the thing the sealed build replaces. OpenCode should
  not have both mechanisms.
* **The bootstrap installer** (`HarnessBootstrap`), for the same reason.
* **The PTY and terminal emulator** (`terminal-emulator`, `terminal-view`).
  OpenCode is a server with a UI; if it wants a terminal view later, the modules
  are independent already, but they are not on the critical path here.

## Invariants the port must preserve

These are the four things that broke on device, in the order they broke. Each has
a test in `SealedInstallerTest`/`ExecSealingTest`, so a port that violates one
turns red in seconds rather than after a 30-minute emulator run.

1. **Executables are symlinks into `nativeLibraryDir`, never copies.** A copy
   under `files/` does not execute for any app targeting API 29+; the kernel
   resolves the symlink before it checks the permission, which is why the prefix
   keeps its absolute paths (`…/usr/bin/git`) and every `#!` line keeps working.
2. **A payload is verified while it is written.** The extractor hashes the bytes
   it just wrote; a digest or size mismatch is a reported problem, not a silent
   install. A payload that carries fewer files than the manifest promises is a
   truncated build, not a tolerable difference.
3. **Nothing resolves a dependency by name alone.** The bootstrap's pinned
   libraries can be older than a core package requires — that is what produced
   `node: cannot locate symbol _ZNSt6__ndk113__hash_memoryEPKvm` — and packages
   can rely on a `postinst` to create a soname (`perl: library libperl.so not
   found`). Resolve by Debian version, and create the soname links the payload
   needs from each binary's `DT_NEEDED` entries.
4. **An interpreter that lives in the APK cannot find its own prefix.** CPython
   derives `sys.prefix` from `/proc/self/exe`, which points into
   `nativeLibraryDir`; set `PYTHONHOME` (and `GEM_HOME`, `NODE_PATH` where they
   apply) explicitly. Same class of bug: the runtime's own identity is not
   discoverable from the path it was executed at.

## Order of work in the main harness (fast path)

Measured on this repository: the model works at `targetSdk 36` on API 36 and at
34 on API 34, eight tools including perl and ruby, zero binaries outside the
APK. The merge is therefore integration, not research:

1. Drop in `sealed-runtime`, port the adapter (an afternoon).
2. Point OpenCode's existing `RuntimeProcess`/`RuntimePaths` at the installed
   prefix instead of its own `bin/{bun,git,rg}` layout — one path switch.
3. Run `ExecAudit` at startup and log the split; it is the publishability number
   and costs one directory walk.
4. Only then decide about packs: in the sealed build optional packs must be Play
   Feature Delivery modules (a Play download), not `apt install`.

Nothing in this repository's `core/provision` or `HarnessBootstrap` needs to
travel for any of that.
