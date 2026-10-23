# Publishing the harness on Google Play

Status: **the sealed core tier exists and runs from the APK.** At
`targetSdk 34` on an API 34 device, `bash`, `sed`, `git`, `python`, `node`,
`perl`, `ruby` and `rg` all execute out of `nativeLibraryDir`, with zero binary
executables outside the APK. Remaining work is the submission target (36), the
in-app provisioning UI, and Play Feature Delivery for packs. Read this together
with `ARCHITECTURE.md` §10 (the constraint analysis) and
`docs/size-management.md` (what the toolchain costs).

## Status on 2026-10-23 (read this first)

| Artifact | `targetSdk` | Is it a working app? | Publishable? |
|---|---|---|---|
| `coding-harness-debug-targetSdk28` (what a human installs) | **28** | **yes** — full setup, tools, packs | **no** (the upload is rejected: Play wants 36) |
| `sealed-targetSdk34-from-APK` | 34 | **yes for the core tier** — shell, git, python, node, perl, ruby, ripgrep run from the APK; no provisioning UI yet | **no** — target is 34, not 36 |
| `sealed-targetSdk36-from-APK` | **36** | **yes for the core tier** — the same eight tools, measured on an API 36 device | **yes for the runtime**; still needs the provisioning UI and Feature Delivery for packs |

**The core-tier milestone is done at both API levels** — run `39931163636`, a
two-entry matrix, `Tests 2/2` on each leg (0 skipped, 0 failed), aosp_atd
emulators, `targetSdk 36` built against `compileSdk 36` on API 36 and
`targetSdk 34` against `compileSdk 35` on API 34. The API 36 leg, i.e. the level
Play requires:

```
payload abi=x86_64 targetSdk=36
sealed payload (x86_64): 13858 data files, 1173 data links, 535 executables from the APK
bash   -> exit 0 : SEALED-SHELL-OK | version=5.3.9(1)-release | prefix=…/usr
sed    -> exit 0 : A1 | A2
bin/git    -> exit 0 : git version 2.56.0
bin/python -> exit 0 : py-ssl OpenSSL 3.6.5 29 Sep 2026
bin/node   -> exit 0 : v26.12.1
bin/perl   -> exit 0 : v5.44.0
bin/ruby   -> exit 0 : ruby 4.0.7 (2026-09-15) +PRISM [x86_64-linux-android]
bin/rg     -> exit 0 : ripgrep 15.2.0 features:+pcre2
exec audit: 560 executable entries, 535 shipped in the APK,
            0 binary executables outside it, 25 scripts outside it (run through the preload)
```

The API 34 leg reports the identical tool versions, byte for byte. That is the
whole `core` tier — the packages the harness promises on first run —
running from the APK on a device where the data-directory exec exemption does
not exist. The 535/560 ratio is the design in one line: everything executable
came out of the APK; the 25 leftovers are package scripts, interpreted through
the preload rather than `exec`'d. 252 unique ELF objects per ABI back those 535
executable names.

Four failures had to be fixed to get here, and each one is now covered by the
pipeline test (`scripts/tests/test-sealed-payload.py`), which builds a synthetic
repository and payload with no network:

| Symptom on device | Cause | Fix |
|---|---|---|
| `python`: "Could not find platform independent libraries" | CPython cannot derive `sys.prefix` when `/proc/self/exe` points into `nativeLibraryDir` | set `PYTHONHOME` to the prefix (`HarnessEnvironment`) |
| `node`: `cannot locate symbol _ZNSt6__ndk113__hash_memoryEPKvm` | the bootstrap's pinned `libc++` is older than the one `nodejs` links against; the resolver skipped it *by name* | resolve by version: a package both sides ship is taken from the repository when the versions differ |
| `perl`: `library "libperl.so" not found` | the package ships `libperl.so.5.44.0` and leaves the soname to its postinst; a payload runs no postinsts | the builder reads each binary's `DT_NEEDED` and creates the missing soname link |
| build: `libclang_rt.asan` missing | the bootstrap carries the Termux packages' own sanitizer test binaries | those test trees are excluded from the payload by prefix |

Remaining gap to a submission, in order:

1. **Ship the provisioning UI** — the payload installs and runs, but the sealed
   build has no first-run screen yet; in the shipping build that job is done by
   `apt`. Without it there is nothing to tap from a fresh install.
2. **Target 36** — done as a measurement: the gate builds `targetSdk 36`
   against `compileSdk 36` and runs the whole core tier on an API 36 device
   (Play has required 36 for new apps and updates since 2026-08-31, with an
   extension possible until 2026-11-01). The shipping build still defaults to 28
   because its only install path is `apt`; the switch happens together with
   point 1.
3. **Keep runtime installs out of the Play build** — `apt-get` fetching `.deb`s
   is the Device & Network Abuse problem; packs become Play Feature Delivery
   modules. Plus a release keystore and the store listing. (16 KB page alignment
   is done: `-Wl,-z,max-page-size=16384` on every native object, ahead of the
   2027-02-01 block.)

Sizes from the same run: **252 JNI libraries per ABI** carry the 535 executable
names, and the payload is 13858 data files + 1173 links; the run took ~4 minutes
to install on the software emulator, a one-time first-run cost. Extraction is
covered in `docs/size-management.md`.

Short version:

* The current build (`targetSdk 28`) is **not publishable** — Play rejects the
  upload, and the reason is the Termux reason: it executes the userland it
  installed under its own data directory, which Android 10+ forbids for apps
  targeting API 29+.
* Raising `targetSdk` is not a build flag, it is a **packaging change**: the
  bytes that run must come out of the APK. That change is now done for the
  shell and the whole core tier.
* The reference harness (`m-cyber12/OpenCode-app`, `arena/01a0b9d5-opencode-app`)
  already proves the model works on a real arm64 phone at `targetSdk 34`. This
  document takes its recipe, applies it to a Termux-shaped userland, and says
  exactly what is proven, what is measured, and what is left.

---

## 1. What the reference harness does (and why it works)

`OpenCode-app` is useful as evidence, not as a template: it runs the *upstream
OpenCode server* as a child process of an Android app, and it does so at
`targetSdk 34` / `minSdk 29` without a Terminal, without proot, and without a
data-directory exec exemption. The mechanism, from its own sources:

| Piece | File | What it establishes |
|---|---|---|
| Executables ship as JNI libs | `app/build.gradle.kts` (header comment) | "Executables (bun, git, rg per ABI) are packaged as JNI libs (`lib<name>.so` under `jniLibs/<abi>/`). Android extracts these into the read-only, exec-allowed nativeLibraryDir — the only place an app can exec from on API 29+ (W^X). `extractNativeLibs=true` so they are real files." |
| Paths for those executables | `runtime/RuntimePaths.kt` | `bin/{bun,git,rg}` are **symlinks** into `nativeLibraryDir`, so tools that look up `bun`/`git`/`rg` by name (or by absolute path) find them. |
| Everything else is data | `runtime/PayloadExtractor.kt` | The server bundle, `node_modules` and the launcher ship as `assets/runtime-payload.tar.gz` + `runtime-manifest.json` (per-file sha256 + size) and are extracted into `filesDir`. Reading is unrestricted; nothing there is executed. |
| Launch path | `runtime/RuntimeProcess.kt` | `exec` only ever targets `nativeLibraryDir`: either a PIE shim (`libexecshim.so`) or `libbun.so` directly. |
| App-uid syscall policy | `phase4/payload/native/seccomp-shim.c`, `exec-shim.c` | Android's Zygote filter traps new syscalls with **SIGSYS**, not `-ENOSYS`; a preloaded SIGSYS handler plus a BPF filter turn those into the errnos callers expect, so bionic-linked binaries boot. |
| Target | `app/build.gradle.kts` | `compileSdk 34`, `minSdk 29`, `targetSdk 34`, `extractNativeLibs=true`, `abiFilters arm64-v8a, x86_64`. |

The consequence for that project is written down honestly in its own
`docs/CAPABILITY-MATRIX.md`: the **shell is `/system/bin/sh` (mksh) with pipes**
— no PTY, no bash, no coreutils beyond toybox — and `apt`/`dpkg` do not exist.
That is the price of the exec model *if you do not have a sealed userland*.
It is exactly the gap this harness fills, and the reason the model has to be
extended rather than copied.

## 2. Why this harness is stuck at `targetSdk 28`

`ARCHITECTURE.md` §6 explains the install: a Termux bootstrap is unpacked into
`rootfs/`, `apt-get` fetches `.deb`s from the Termux repository, dpkg runs
maintainer scripts, and every one of those files is executed **in place** under
`<app files>/coding-harness/`. For `targetSdk >= 29` the kernel and SELinux
refuses that `execve` with `EACCES`. Nothing about `minSdk` or `compileSdk`
matters; the target alone switches the policy.

Two facts make a way out possible, and both are already in this codebase:

1. **`execve` is what is restricted; `mmap(PROT_EXEC)` is not.** That is why
   `dlopen()` of an app-private `.so` keeps working (all of Python's C
   extensions, Ruby gems with native code, Node addons), and why the retired
   `termux-play-store` build could run binaries at all by handing them to
   `/system/bin/linker64` instead of `execve`-ing them.
2. **A symlink is resolved before the permission check.** So a prefix path can
   point at an inode in `nativeLibraryDir` and execute normally, with its
   original absolute path intact.

## 3. The sealed model

A *sealed* install keeps the Termux shape (a real prefix, real absolute paths,
`#!` lines and scripts unchanged) and moves only the bytes that the kernel
executes:

```
APK
├── lib/<abi>/libbash.so          ← /usr/bin/bash,  renamed
├── lib/<abi>/libgit.so           ← /usr/bin/git,   renamed
├── lib/<abi>/libpython.so        ← /usr/bin/python, renamed
└── assets/
    ├── userland.tar.gz           ← everything that is only *read*
    │                               (stdlib, site-packages, gems, perl libs,
    │                                terminfo, certs, dpkg data, /etc)
    └── sealed-exec.txt           ← bin/bash<TAB>libbash.so, bin/git<TAB>libgit.so, …

at install time:
  assets/userland.tar.gz  →  <files>/coding-harness/rootfs/…      (data only)
  sealed-exec.txt         →  <prefix>/bin/bash  ->  nativeLibraryDir/libbash.so
  exec-audit.txt          =  every executable accounted for
```

Three mechanisms already exist in this repository and are reused unchanged:

* **exec-path and shebang translation** (`harness-exec.c`): scripts keep saying
  `#!/data/data/com.termux/files/usr/bin/bash`; the preload translates the
  interpreter to our prefix and starts it itself, so a *script* is never the
  thing being `execve`-d.
* **argv translation** (added for dpkg's maintainer scripts): `rm -f
  /data/data/com.termux/files/usr/share/…` becomes our prefix path.
* **directory-open translation**: dpkg's compile-time config dir cannot be
  overridden by environment; the preload covers it.

And one mechanism is new, added with this document:

* **`SealedLayout`** (`harness-core/.../core/exec/`): applies `sealed-exec.txt`
  at first run and after every app upgrade, idempotently, and refuses to
  invent anything the APK did not ship.
* **`scripts/build-sealed-payload.py`** + **`SealedPayloadInstaller`**: split a
  Termux bootstrap into the two halves and put them back on device. On the real
  bootstrap (`2026.02.12-r1`, x86_64) the split is **324 executables → 199 JNI
  libraries (16.2 MiB)**, **3169 data files (21.7 MiB)** and **956 data
  symlinks**; **112 scripts stay in the payload** and run through the
  interpreter layer instead of being exec'd.
* **`ExecAudit`**: classifies the prefix by first bytes (ELF / `#!` script /
  data) and reports how many executables are *not* backed by the APK. That
  number is the publishability answer, and it is written to
  `exec-audit.txt` on the device.

### 3.1 What this changes about packages

The policy licence and the exec model agree on the same rule: **native code
comes from the APK, not from the network at runtime.**

| Today (F-Droid/direct build) | Play build |
|---|---|
| bootstrap + `apt-get` from the Termux repository | one sealed payload, built in CI from `termux-packages` |
| core tier installed on first run | core tier shipped in the APK |
| packs (`build-tools`) installed on demand, removable | packs are **Play Feature Delivery modules** (`split_clang`, …), installed and removed by Play, still `lib*.so` + data |
| user can `apt install anything` | a curated catalogue; anything the APK did not ship stays out. `apt` may remain as an *offline, data-only* tool, never as a source of executables |

Play's **Device and Network Abuse** policy forbids downloading executable code
from anywhere but Google Play; a feature module satisfies it by construction
(Play downloads it, Play installs it, the app just uses it). Play's base module
is capped at 200 MB and the whole app at 4 GB across modules; the core toolchain
of this harness is ~425 MB installed on device but its *compressed* payload is
far smaller, and the compiler pack (~620 MB installed, ~110 MB compressed
delta) is the natural first feature module.

### 3.2 What stays the same, and works better

* The prefix keeps its absolute paths, so `pip`, `npm`, `gem`, `cpan`, `git`,
  `rg`, `python -c`, `node -e`, and every `configure` script behave exactly as
  before — they are all data plus the sealed binaries.
* `pip install` of pure-Python wheels is unaffected. Wheels with C extensions
  compile via the sealed `clang` pack and `dlopen` fine (mmap is allowed).
* `npm install` runs the sealed `node`/`npm`; native addons that *compile* are
  fine; addons that ship a prebuilt `binding.node` are `dlopen`ed, not exec'd.
* The PTY, the terminal, the toolchain tiers, the removal logic, the size
  reporting: all unchanged.

### 3.3 What is lost, honestly

* `apt-get install` of arbitrary packages (new native binaries) cannot be part
  of the Play build. That is the policy line, not an Android limitation.
* `#!/usr/bin/env python3` in a *user's* script still works only because the
  interpreter is sealed; a user-installed script whose interpreter is not
  sealed will not run under a Play build (their `pip`-installed console script
  is data → `ENOEXEC`-class failure unless it is run as `python -m`). This is
  the same limitation every Play-sandboxed runtime has.
* Install size grows: every executable exists both compressed in the APK and
  extracted in `nativeLibraryDir` (`extractNativeLibs=true`).

## 4. Store checklist

| Item | Requirement | State |
|---|---|---|
| `targetSdk` | API 36 for new apps/updates from 2026-08-31 (extension to 2026-11-01) | `sealed` variant is built and tested at 34 today; the property is `-PharnessTargetSdk=36` (compileSdk must be 36 too) |
| `minSdk` | any | 29 — fine |
| Exec model | no `execve` from app data | sealed layout + `ExecAudit` gate |
| Downloaded code | native code only from Play | sealed payload + feature modules |
| 16 KB pages | required for API 35+ targets; hard block 2027-02-01 | **not done**: `Android.mk` sets no alignment flags and NDK 27 is used. Add `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` to both modules (or move to NDK r28+) |
| ABI / bitness | 64-bit | arm64-v8a + x86_64 |
| App Bundle | base ≤ 200 MB, total ≤ 4 GB | payload must be measured once built; expect the base module to fit, the compiler pack to be a feature module |
| Signing | upload key + Play App Signing | CI signs debug only today (`RELEASE.md` procedure is the template) |
| Permissions | minimum necessary, declared use | `INTERNET` only |
| Data safety | declare what leaves the device | model provider traffic only; no telemetry |
| Store listing | screenshots, description, privacy policy | to write |

## 5. Status: what is proven, what is measured, what is left

**Proven on an emulator** (`ExecModel (targetSdk 34)` run `39856436772`:
`Tests 1/1 completed. (0 skipped) (0 failed)`, aosp_atd API 34 x86_64, built
with `-PharnessTargetSdk=34 -PharnessTestBuildType=sealed`. The gate first
verifies that the APK really sets `extractNativeLibs` and ships the expected
native libraries, and afterwards asserts from the run log that the test
executed — a self-skipped test fails the gate instead of passing it):

1. executing a file from `nativeLibraryDir` works at `targetSdk 34`;
2. executing the *same bytes* copied into app-private storage is **denied** at
   `targetSdk 34` and works at `targetSdk 28` — the target switch in one test;
3. executing a prefix **symlink** into `nativeLibraryDir` works at 34;
4. a script whose `#!` names the official Termux prefix runs through the
   preload at 34 (interpreter translated, script read, never exec'd);
5. direct execution of a data-dir script is denied at 34 and allowed at 28;
6. `ExecAudit` reports the split, and the gate asserts the sealed prefix has
   **zero** executables outside the APK.

**Also learned from the gate, and worth writing down:** `LD_PRELOAD` did not
take effect in `/system/bin/sh` (a system binary), but works exactly as designed
in an app-owned executable from `nativeLibraryDir` — which is what every
runtime in the sealed prefix is. The gate therefore measures the preload from
the sealed binary itself, and the product path matches it.

**Measured against the current install (targetSdk 28):** the audit runs first in
every variant and reports how many executables in the installed prefix are not
backed by the APK — that number is the size of the packaging job, and the reason
this is a migration rather than a flip. On the apt-installed prefix the walk is
bounded (40 000 entries, 20 s) and may end truncated; the sealed gate treats a
truncated audit as a failure, because there the number has to be certifiable.

**Left to build (in this order, each independently verifiable):**

1. **Payload builder in CI** (`scripts/build-sealed-payload.sh`, runs on a
   networked runner): fetch the Termux bootstrap + the core tier `.deb`s at
   pinned versions, unpack them, split each artifact into "ELF that must be
   exec'd" → `lib<name>.so` (with `sealed-exec.txt` alongside) and "everything
   else" → `userland.tar.gz`. Emit a manifest with per-file sha256, as the
   reference harness does.
2. **Build-time verifier** (`scripts/check-sealed-manifest.sh`): fails the build
   if the payload contains an executable that is not in `sealed-exec.txt`, if a
   manifest entry has no library, or if a library is missing for an ABI.
3. **Installer profile** in `harness-core`: the existing `Provisioning` service
   gains a `SealedPayloadProvisioner` implementation of the same port
   (`PackageManager`), so `MainActivity`, the tier UI, the diagnostics and the
   tests are untouched.
4. **Packs as feature modules**: `:pack_clang` as a dynamic feature module with
   its own `jniLibs` + `sealed-exec.txt` fragment, delivered on demand and
   removable (Play removes the split, the app removes its `sealed-exec.txt`
   lines) — the removal path already exists for the `.deb` build.
5. **Flip the target**: `-PharnessTargetSdk=36`, `compileSdk 36`, 16 KB flags,
   and move the emulator gate to API 36.
6. **Release plumbing**: upload key, Play App Signing, listings, data-safety
   form.

Nothing in steps 1–6 changes the architecture: the provisioning module already
talks to packages through one port (§`PackageManager`), and the sealed layout is
applied by the bootstrap before any of it runs.

## 6. Why two build flavours, not one

The `.deb` build is not a legacy artefact — it is the only build that can
install *new* native code on demand, and it is the one that works today, is
audited, and matches upstream Termux semantics. The sealed build trades that
freedom for store distribution. Keeping both over the same `harness-core` costs
one Gradle variant and one asset (`sealed-exec.txt`); pretending one of them is
unnecessary costs either the Play listing or the ability to `apt install`.

```
:app            one code path, two profiles
  ├── default   targetSdk 28, apt from the Termux repository (F-Droid / direct)
  └── sealed    targetSdk 34+, APK-shipped userland, Play Feature Delivery packs
```

The measured baseline in `ARCHITECTURE.md` §8 (`a01`–`a04`) stays the
correctness gate for the shared 90 %; the new `ExecModelTest` gate is the
publishability gate.
