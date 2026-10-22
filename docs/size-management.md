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
| Core | git, ripgrep, python, python-pip, nodejs, npm, jq, tree, which, file, openssh | yes |
| Build tools | make, pkg-config, clang, cmake | on demand (TOOLS dialog) |
| Extra languages | perl, ruby | on demand (TOOLS dialog) |

Notes:

- `python-pip` is named explicitly: it is only a *Recommends* of
  Termux's `python`, and recommends are disabled (below).
- clang pulls libllvm — at ~34 MB download / ~200 MB installed the
  largest single block in the old 1.2 GB — yet is exercised only when
  compiling native code (C/C++ projects, node-gyp, pip source builds).
- ruby carries a large stdlib and serves only Ruby projects; perl is
  in the same pack (git merely *suggests* it).

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
5. **Measurement, not estimates.** `HarnessSizeReport` reads dpkg's
   `Installed-Size` database, attributes bytes to tiers, lists the
   largest packages, and walks the prefix/cache. It is shown in the
   TOOLS dialog, appended to setup results, printed by the emulator
   E2E, and covered by unit tests. New packages may only join the core
   tier with a measured size justification in review.
