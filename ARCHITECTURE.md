# Coding Harness — Architecture Notes

Version 0.33.1 (versionCode 40). Last updated after the pack-removal work.
Companion documents: `docs/opencode-port.md` (porting to the TypeScript
harness), `docs/size-management.md` (storage policy and measurements),
`./TERMUX_SOURCE.md` (which Termux sources/layers are used).

---

## 1. What this is

A standalone Android terminal harness that provisions a Termux-style
userland inside its own app sandbox and hands it to an agentic coding
assistant. It is deliberately narrower than the Termux distribution: it
ships only the bootstrap, the apt/dpkg layer and a declared set of
coding tools, and it is built so that its reusable parts can be lifted
into the main OpenCode-style app later (see §9).

Goals that shaped the design:

- start a shell and be useful without the Termux app being installed;
- install/verify/repair a known toolchain on-device, with actionable
  diagnostics instead of opaque dpkg failures;
- keep the footprint proportional to what the user actually needs;
- keep policy (which tools, which tiers) declarative and testable.

---

## 2. Module layout

```
app/                 Host Activity + E2E instrumentation tests
harness-core/        All provisioning logic (no UI, JVM-testable)
  core/              Bootstrap, environment, paths, session, size report
  core/provision/    Provisioning module (tiers, planner, service, port)
terminal-emulator/   Termux terminal emulator + JNI helper + preload lib
terminal-view/       Termux terminal view/renderer
scripts/             Manual emulator E2E driver
.github/workflows/   JDK builder + emulator E2E
docs/                Porting, size, architecture notes
```

Dependency direction is one-way: `app → harness-core → (Android APIs)`,
and `harness-core` depends on `terminal-emulator` only for the PTY
session type. Nothing in `harness-core` knows about Activities or Views.

The one rule that keeps the project portable: **UI never contains
provisioning logic**, and provisioning never contains UI.

---

## 3. Runtime layout on the device

```
<app files>/coding-harness/                  HAR = harness root
├── rootfs/                                  DPKG_ROOT
│   └── data/data/com.termux/files/usr/      PREFIX (the apt/dpkg "prefix")
├── workspace/                               agent working directory
├── home/
├── <xdg dirs>/
├── libharness-exec.so                       extracted from the APK
├── .coding-toolchain-installed              fast-path marker
├── setup-result.txt                         "OK …" / "FAILED …" (last run)
└── bootstrap-error.log                      append-only failure log
<prefix>/var/lib/dpkg/                       dpkg database (source of truth)
<prefix>/var/lib/apt/lists/                  apt index
<prefix>/var/log/harness-command.log         every command the harness ran
```

Two prefix facts drive most of the complexity:

1. Files inside `.deb`s are stored at their absolute official paths
   (`./data/data/com.termux/files/usr/...`), so the extraction root must
   be a **real directory tree** that mirrors `/` — `rootfs/` — with
   `DPKG_ROOT` set to it.
2. The *official* Termux prefix is still baked into binaries and
   scripts (absolute paths, dpkg's compile-time CONFIGDIR, symlink
   targets). Those must be translated or rewritten, because on a stock
   phone that path belongs to another app.

---

## 4. Boot and provisioning lifecycle

```
MainActivity.onCreate
  └─ HarnessBootstrap.ensureInstalled       extract rootfs, preload lib, certs
  └─ ensureCertificates / ensureKeyring     APK assets → prefix
  └─ HarnessSession.start                   PTY (bash --noprofile --norc -i)
  └─ Provisioning.Service.subscribe(listener)
        └─ provision(["core"])              worker thread
              RESOLVING → UPDATING_INDEX → UPGRADING_BASE
              → INSTALLING → VERIFYING → CLEANING → READY | FAILED
```

Every transition publishes an immutable `ProvisioningState` snapshot to
subscribers. The Activity renders that snapshot and, on `READY`,
re-attaches a dead PTY and refreshes its status line — so completion is
observable **without an app restart** (this replaced an earlier design
that polled a boolean and left the shell and TOOLS view stale).

`onResume` re-reads the current snapshot, so returning to the app after
a background install shows the finished state immediately.

Design rules:

- one provisioning run at a time per prefix (`AtomicBoolean`), concurrent
  callers get the in-flight state;
- the worker body is wrapped in `try/catch(Throwable)/finally`, so a
  crash can never latch setup permanently;
- provisioning depends only on the prefix, never on the PTY being alive;
- every failure carries `ProvisioningError.Kind` (INDEX, BASE_UPGRADE,
  INSTALL, REMOVE, VERIFY, IO, INTERRUPTED) plus a compacted log excerpt.

---

## 5. The exec/preload layer (`terminal-emulator/src/main/jni/harness-exec.c`)

An `LD_PRELOAD` library that adapts official-Termux assumptions to the
relocated prefix. It is loaded first in the environment
(`HarnessEnvironment.create`), with the prefix's own termux-exec libs
after it.

Translation map (official path → environment variable):

| Official path prefix | Replaced with |
|---|---|
| `/data/data/com.termux/files/usr`, `/data/user/0/com.termux/files/usr` | `$TERMUX__PREFIX` |
| `/data/data/com.termux/files`, `/data/user/0/com.termux/files` | `$TERMUX__ROOTFS` |
| `/data/data/com.termux`, `/data/user/0/com.termux` | `$TERMUX_APP__DATA_DIR` |

Four mechanisms, each added to fix a specific real-device failure:

1. **exec path translation** — exec targets are rewritten before
   `execve` (bootstrap scripts and binaries name the official prefix).
2. **shebang handling** — the kernel cannot run `#!` scripts whose
   interpreter lives elsewhere once paths are relocated; the library
   re-execs the interpreter itself. Includes the arg-less-shebang case
   (`#!…/bash` with no argument) which must not consume the script's
   first line.
3. **directory/file open translation** — `scandir`, `scandir64`,
   `opendir`, `fopen`, `fopen64`. Required because dpkg's config
   directory is a **compile-time** constant (no environment override):
   it opens `/data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d`, and
   any error other than ENOENT is fatal on a stock phone (EACCES).
4. **argv translation** — arguments of executed programs are rewritten
   when they start with an official prefix. Maintainer scripts invoke
   ordinary tools with absolute paths (`rm -f …/share/glib-2.0/…`), and
   without this the tool operates on another app's private directory,
   producing "Permission denied" and aborting the dpkg transaction.

Not translated, by design: relative paths, paths that merely *contain*
the prefix inside a longer argument, and anything not starting with an
official prefix.

---

## 6. Package layer

### 6.1 Bootstrap (`HarnessBootstrap`)

- Extracts the per-ABI Termux bootstrap zip from APK assets into `rootfs`.
- Extracts `lib/<abi>/libharness-exec.so` from the APK to the harness
  root (AGP ships jniLibs compressed, so it cannot be `dlopen`ed from
  inside the APK; a real file is required for `LD_PRELOAD`).
- Installs the CA certificate and the Termux keyring as **real files**
  (dangling keyring symlinks were the original `NO_PUBKEY` cause).
- **Relocates absolute symlinks**: any symlink in the prefix whose
  target is under `/data/data/com.termux/...` is rewritten to the
  mirrored location. The kernel resolves symlink targets itself, so no
  userspace translation can help; dpkg's stat-dereference of such a link
  is what killed the `termux-keyring` unpack. Runs at bootstrap and again
  from `HarnessEnvironment.prepare()` before every command, so existing
  installs heal in place.

### 6.2 Environment (`HarnessEnvironment`)

`prepare()` is idempotent and runs before every command. It:

1. heals official-prefix symlinks (§6.1);
2. provisions cert + keyring, rewriting `sources.list` with
   `signed-by=` pointing at our key file;
3. writes `harness-apt.conf`: `CaInfo`, relative `Dir`, absolute
   `Dir::Bin::{dpkg,apt-key,compressors}`, `DPkg::Path "<prefix>/bin"`,
   `--force-script-chrootless` (no unprivileged chroot), `--force-confold`
   (no interactive conffile prompts), and `APT::Install-Recommends` /
   `Install-Suggests = false` (installed set is exactly what tiers say).

`create()` builds the child environment: `DPKG_ADMINDIR`, `DPKG_ROOT`,
`PREFIX`/`HOME`/`TMPDIR`, and the `LD_PRELOAD` order from §5.

### 6.3 Provisioning module (`harness-core/src/main/java/.../core/provision`)

```
PackageTier.Info     declarative tiers: id, label, packages,
                     requiredCommands, enabledByDefault, removable
ProvisioningPlan     pure planner: requested tiers × (installed packages,
                     present commands) → pending tiers + exact package list
PackageManager       port (run / installedPackages / installedSizes /
                     listsAreFresh) — the only process-touching abstraction
PrefixPackageManager apt/dpkg implementation over the harness prefix
PackageCommands      pure builders: install / remove --purge / autoremove
Provisioning(Service) observable service: provision(), installTier(),
                     removeTier(), state(), subscribe()
DefaultProvisioning  the lifecycle implementation
ProvisioningState    immutable snapshot (status, message, detail, error)
ProvisioningStatus   IDLE … REMOVING … READY | FAILED
StorageHygiene       reclaim apt cache, docs/man/locale, __pycache__
```

Tier policy today:

| Tier | Packages | Default | Removable |
|---|---|---|---|
| core | git, ripgrep, python, python-pip, nodejs, npm, perl, ruby, jq, tree, which, file, openssh | yes | no |
| build-tools | make, pkg-config, clang, cmake | no | yes |

Removal purges the tier's packages, then runs a dependency sweep
(`apt-get --purge autoremove`) which is where the real payload goes
(libllvm). The service then verifies: pack gone, every core package
still installed, database usable; measures bytes reclaimed before/after;
and repairs the database (`dpkg --configure -a`) if the sweep failed
part-way.

### 6.4 Size accounting (`HarnessSizeReport`)

Reads dpkg's `Installed-Size` per package, attributes bytes to tiers,
lists largest packages and directories, and reports **unique-inode**
bytes as storage truth — Termux hard-links many command names to one
binary (git-core: ~180 names, 543 MB apparent vs 21 MB real), so naive
sums overstate storage several-fold.

---

## 7. Invariants worth preserving

1. `rootfs` is a real directory tree; never a symlink (dpkg refuses).
2. `dpkg`'s database is the source of truth for "installed"; command
   presence is the source of truth for "ready".
3. Nothing writes into `/data/data/com.termux`; everything is mirrored
   under the harness root.
4. `prepare()` is idempotent, cheap, and runs before every command.
5. A failure always yields a typed kind, a compacted cause and a
   log/dialog path — never a bare exit code.
6. Setup state is published, never polled; UI reacts to events.
7. Core can be repaired but not removed; only packs are removable.

---

## 8. Verification

Two GitHub Actions workflows (both required for a change to be trusted):

- **Android JDK builder** — compiles all modules, runs JVM unit tests,
  assembles the debug APK, verifies APK contents (cert, 8 keyring files,
  both bootstrap zips, `libharness-exec.so` per ABI).
- **Emulator setup E2E** — boots an `aosp_atd` API 34 x86_64 emulator
  (TCG, no KVM), installs the app, and runs instrumentation tests against
  the real Termux repository:
  - `a01` build chain: bootstrap, TLS/apt-key, `apt-get update` (with
    retry for the CDN's republish window), one package install;
  - `a02` full setup: core install, every tool executed, Perl+Ruby
    present, compiler absent, size report, and proof the app observed
    completion in-process (`setup-result.txt` = OK);
  - `a03` service surface: ready snapshot, listener delivery, no-op
    re-provision fast path;
  - `a04` pack lifecycle: install build tools, run clang/cmake, remove,
    assert `dpkg --audit` clean, apt still usable, core intact, bytes
    reclaimed (measured: 1,045,258,240 B → 424,767,488 B, byte-identical
    to pre-install).
  - Diagnostics: logs and reports are published to
    `diagnostics/e2e-<run_id>` every 5 minutes, so evidence survives.

- **Exec model (targetSdk 34)** — the publishability gate, a separate
  workflow (`ci/play-exec-model.yml`): builds the `sealed` variant at
  `-PharnessTargetSdk=34`, verifies the APK actually sets
  `extractNativeLibs`, then runs `ExecModelTest` on an emulator: native-library
  exec works, the same bytes in app data are denied, a prefix symlink into the
  APK works, a script whose `#!` names the official Termux prefix runs through
  the preload, direct script exec is denied, and the audit finds zero
  executables outside the APK. It needs no network, so it cannot flake on a
  repository mirror.

Unit tests cover the pure parts (planner, tiers, command strings,
pruning, size parsing, symlink relocation, preload extraction).

---

## 9. Porting into the OpenCode harness

The module is shaped like OpenCode's Effect conventions so the port is a
translation, not a redesign: declarative `Info` registries, a pure
planner, typed errors, an injectable port for effects, an observable
service with per-location instances, status enums instead of log
parsing. Details and a file-by-file mapping are in
`docs/opencode-port.md`.

---

## 10. Distribution and Google Play readiness

**The migration plan is `docs/play-store-plan.md`; the execution model it
relies on is implemented in `sealed-runtime/` (a module with no Android APIs,
so the main harness can take it whole — `docs/sealed-runtime-port.md`) and
measured by the `ExecModelTest` gate. Read this section as the constraint analysis and that
document as the way through it.**

Short answer: **the current build cannot be published on Google Play, and
that is not caused by anything in the recent work.** The blockers are
structural and identical to the ones that pushed Termux itself off the Play
Store. Three independent reasons:

### 10.1 Target API level

`targetSdkVersion = 28` (deliberate, see §10.2). Google Play requires:

- from **31 August 2026**, new apps and **app updates** must target
  **Android 16 (API 36)** or higher; an extension to 1 November 2026 can
  be requested in Play Console;
- existing published apps must target **API 35** or higher to stay
  available to new users on newer devices.

A submission with `targetSdk 28` is rejected at upload. This is a hard,
mechanical blocker.

### 10.2 Why the target cannot simply be raised (W^X)

Since Android 10 (API 29), untrusted apps **may not `execve()` files in
their own home/data directory** — executing writable files is treated as
a W^X violation. This harness executes the entire userland it installs
under its data directory, so it needs the pre-API-29 compatibility
behaviour. This is exactly why Termux stays at `targetSdk 28` and is
distributed via F-Droid; its own Play builds were frozen in 2020/2022
and then deprecated.

Raising `targetSdk` without changing the execution model would not
"break compliance" — it would break the product: the shell would not
start on modern devices.

The known workaround (`system_linker_exec`, used by the retired
`termux-play-store` builds) is a linker hack that Android can disable at
any time, and the Termux maintainers describe it as policy-violating
even while it works. It is not a foundation for a product.

### 10.3 Play policy on downloaded executable code

Play's **Device and Network Abuse** policy states that an app "may not
download executable code (such as dex, JAR, .so files) from a source
other than Google Play", with an exception only for code running in a VM
or interpreter with indirect access to Android APIs. This harness
downloads a Linux userland (native executables) and then installs
`.deb`s of native code from the Termux repository at runtime — squarely
inside the prohibition.

### 10.4 Technical items that would also need attention

Not blockers for the current build, but required the moment a Play
submission is attempted:

| Item | State | Action |
|---|---|---|
| 16 KB page size | **Done.** NDK 27.2 is pinned (it does not align by itself), so both ndk-build modules and the exec probe pass `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`. Play requires 16 KB support for apps targeting API 35+ (hard block on non-compliant updates from 1 Feb 2027). | — |
| 64-bit | OK — `arm64-v8a` and `x86_64` are built | — |
| App Bundle size | Two bootstrap zips plus assets; base module stays well under Play's 200 MB limit | Verify on the release AAB |
| Signing | CI signs with the debug keystore | Release keystore + Play App Signing |
| minSdk | 29 — fine | — |
| Edge-to-edge / API 36 behaviours | Not applicable yet (targets 28) | Handle insets if the target is ever raised |

### 10.5 The way out: a sealed userland

Both blockers 10.2 and 10.3 come from one property: the bytes the kernel
executes live in app-private storage and arrive over the network. Android
allows `execve` only from the APK's native library directory, and Play allows
native code only from the APK, so a publishable build has to ship its
executables and keep everything else as data:

- every executable in the prefix becomes a **symlink** into `nativeLibraryDir`
  (`SealedLayout`), which keeps its absolute path — apt, dpkg and every `#!`
  line keep working — while the inode that runs came out of the APK;
- everything that is only *read* (stdlib, gems, node_modules, `#!`` scripts)
  is extracted from an asset payload;
- scripts are never `execve`-d: the existing preload translates their
  interpreter, which is why dpkg's maintainer scripts survived relocation in
  the first place;
- optional packs become **Play Feature Delivery modules** instead of
  `apt install`, so on-demand installs are still Play downloads.

`ExecAudit` answers "is this install publishable?" with a number — binary
executables in the prefix that are not backed by the APK — and the sealed build
asserts it is zero. `scripts/build-sealed-payload.py` performs the split on the
bootstrap *plus* the core package set (`535` executables into `252` JNI
libraries, `13858` data files, `1173` data symlinks); `SealedPayloadInstaller`
applies it on device, and the `SealedShellTest` gate runs `bash 5.3.9`, `sed`,
`git 2.56.0`, `python`, `node v26.12.1`, `perl v5.44.0`, `ruby 4.0.7` and
`ripgrep 15.2.0` from the APK at `targetSdk 34` with the audit at zero.

Three problems in that split are structural rather than incidental, and each is
now handled where the payload is built, not where it fails:

1. **A pinned bootstrap library can be older than a core package requires.**
   `node` died with `cannot locate symbol _ZNSt6__ndk113__hash_memoryEPKvm`
   because the bootstrap's `libc++ 29` predates the `libc++ 30` that `nodejs`
   links against, and the resolver skipped anything the bootstrap already had
   *by name*. Dependencies are now resolved by version; a package both sides
   ship is taken from the repository when the versions differ.
2. **Packages rely on postinsts for their sonames.** `perl` ships
   `libperl.so.5.44.0` and lets `postinst` create `libperl.so`; nothing runs
   postinsts in a payload. The builder reads each sealed binary's `DT_NEEDED`
   entries and creates the links, failing the build with the executable and the
   library named when one cannot be satisfied.
3. **Interpreters cannot always find their own prefix.** CPython derives
   `sys.prefix` from `/proc/self/exe`, which points into `nativeLibraryDir`;
   `HarnessEnvironment` sets `PYTHONHOME` for the sealed layout.

This changes nothing for the current `.deb`-based build, which remains the only
one that can install new native code on demand — and the sealed build still
lacks a first-run provisioning UI, and is measured at 34 while a submission
needs 36 (the gate now also builds and measures 36-on-36).

### 10.6 Realistic distribution options

1. **F-Droid / direct APK (recommended today).** Same model as Termux;
   no target-API floor and no downloaded-code policy. This is what the
   current build is suited for.
2. **Play, with a VM-based execution model.** The policy exception for
   code running in a VM underlies Google's own Linux Terminal approach
   (Android Virtualization Framework). A harness that runs the userland
   inside a VM, rather than `execve`-ing downloaded binaries from app
   data, is the only architecturally clean Play path — a different
   product, not a build flag.
3. **Play, APK-packaged binaries only.** Ship every executable inside
   the APK/AAB (native-library trick) and drop `apt`/`dpkg`. That
   contradicts the requirement to use the Termux package layer, so it is
   out of scope by design.

Nothing in the recent changes (provisioning module, tier policy, symlink
relocation, argv translation, pack removal) affects this analysis
either way: they all operate inside the existing execution model.
If Play distribution becomes a goal, the decision to make is about the
execution model (§10.5.2), not about SDK numbers alone.

---

## 11. Known limitations / next steps

- Only the compiler tier is an optional pack; a "minimal" core variant
  (no Perl/Ruby) is not offered, though the tier system supports it.
- Removal cannot restore files that `StorageHygiene` pruned from other
  packages; a reinstall of the affected package does.
- If a dependency's maintainer script fails during a sweep, unused
  dependencies stay installed (reported in the result, database repaired).
- `docs/opencode-port.md` step 7 (removeTier) is done in Java, not yet
  in TypeScript.
- 16 KB alignment (§10.4) is a one-line fix worth doing proactively, so
  the native layer is ready for devices with 16 KB pages regardless of
  store choice.
