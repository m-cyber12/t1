# Size management

The harness reached ~1.2 GB installed when every conceivable tool was
installed by default. This document records what an agentic harness
actually needs, and the mechanisms that keep the footprint proportional
to use. Implemented in `HarnessPackageInstaller` (tiers, hygiene),
`HarnessEnvironment` (apt policy) and `HarnessSizeReport` (measurement).

## What an agent actually runs

A Claude Code / OpenCode-class agent drives a shell, works a git repo,
searches code (ripgrep), and runs project code and tests — today that is
overwhelmingly Python and JavaScript/TypeScript, with MCP servers
commonly launched via `npx`. It does not need a C/C++ compiler or extra
language runtimes until a specific project does.

## Tiers (HarnessPackageInstaller)

| Tier | Packages | Installed by default |
|---|---|---|
| Core | git, ripgrep, python, python-pip, nodejs, npm, perl, ruby, jq, tree, which, file, openssh | yes |
| Build tools | make, pkg-config, clang, cmake | on demand (TOOLS dialog) |

Perl and Ruby moved from an on-demand pack into the core tier by product
decision: a coding harness should be ready for any project without an extra
step, and together they cost about 20 MB on device (360 MB → 380 MB measured)
while the compiler tier alone is ~600 MB.

Notes:

- `python-pip` is named explicitly: it is only a *Recommends* of
  Termux's `python`, and recommends are disabled (below).
- clang pulls libllvm — at ~34 MB download / ~200 MB installed the
  largest single block in the old 1.2 GB — yet is exercised only when
  compiling native code (C/C++ projects, node-gyp, pip source builds).
- perl and ruby are part of the default install: small next to the compiler
  tier, and they remove a whole category of "install this first" friction.

## Rules

1. **Default install = core only.** Setup gates readiness on core
   commands (bash, git, rg, python, pip, node, npm, jq, curl).
2. **Explicit dependencies, no recommends.** `APT::Install-Recommends`
   and `APT::Install-Suggests` are `false` in harness-apt.conf, so the
   installed set is exactly what the tiers declare — no silent trees.
3. **On-demand packs** install through the same verified pipeline
   (update if stale → install → commands must execute → hygiene) via
   `installBuildTools` / `installExtraLanguages`, exposed as buttons in
   the TOOLS dialog while a pack is missing.
4. **Post-install hygiene** (`applySizeHygiene`): `apt-get clean`
   clears the .deb cache (after a full setup it duplicates the entire
   download), and `pruneNonRuntimePayload` deletes man/doc/gtk-doc/
   info/locale payloads and Python `__pycache__` caches — never
   binaries, libraries, headers, stdlib code, or user site-packages.
   Reinstalling a package restores anything pruned.
5. **Storage truth = unique bytes.** Termux uses hard links heavily
   (git-core exposes ~180 command names for one binary), so "size of
   every path added up" overstates storage ~4x. The report prints
   unique-inode bytes as the figure to budget against, alongside the
   apparent total, and "not tracked by dpkg" must stay at 0.
6. **Measurement, not estimates.** `HarnessSizeReport` reads dpkg's
   `Installed-Size` database, attributes bytes to tiers, lists the
   largest packages, and walks the prefix/cache. It is shown in the
   TOOLS dialog, appended to setup results, printed by the emulator
   E2E, and covered by unit tests. New packages may only join the core
   tier with a measured size justification in review.

## Measured results (0.32.0, emulator E2E run 39796078354, 3/3 tests)

Default (core) install now includes Perl and Ruby, x86_64 emulator:

```
all packages: 405.1 MiB (112 packages, dpkg Installed-Size)
core (default): 250.8 MiB        <- includes perl + ruby
build tools (on demand): 0 B (0/4 installed)
apt download cache: 0 B          <- hygiene reclaimed 112.2 MiB
unique bytes on disk: 341.7 MiB (apparent 1.10 GiB)
not tracked by dpkg: 0 B
```

On the user's device (arm64) the app's own storage screen reported
360 MB for core and 380 MB once Perl and Ruby were added, versus 0.98 GB
for the full installation — the difference between those figures and the
emulator's is Android's own accounting of the app sandbox.

Previous core-only measurement (0.31.x, run 39769843224): 291.2 MiB
installed, 250.0 MiB unique, 137.2 MiB core tier.

Interpretation:

- The ~1.2 GB reported on-device was largely **apparent** size: one
  `libexec` tree weighs 543 MiB by name but only 21.2 MiB on disk.
- Real footprint of the default harness is **~250 MiB unique**, of which
  ~137 MiB is the core tier and ~113 MiB the Termux base system.
- On-demand packs are genuinely absent from a default install (0 B), and
  installing the extra languages pack costs ~114 MiB (405.1 MiB total)
  while reclaiming 33.8 MiB through hygiene.
- Remaining large items are irreducible for an agent harness: the Node
  runtime + ICU (92.7 MiB combined) is what runs JS/TS projects and
  `npx`-based MCP servers; python 25.6 MiB; git 24.4 MiB.
- Setup time fell with the smaller set: the connected-test stage
  dropped from 1h05m to 13m57s on the same image.
