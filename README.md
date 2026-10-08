# Coding Harness for Android

A separate Android terminal app for coding agents. It uses the reusable terminal
engine from Termux, installs a small Termux-style userspace, and provisions the
language/tool packages that coding harnesses commonly need.

This project is intentionally separate from the OpenCode Android app. The
standalone APK is `app`; the code that the main app can later reuse is in
`harness-core`, `terminal-emulator`, and `terminal-view`.

## What this app provides

This is not the complete Termux product or a general-purpose package catalog.
It is a focused coding harness containing:

- a real PTY-backed interactive shell;
- Termux terminal emulation, touch input, keyboard input, scrollback and
  copy/paste;
- an app-private Termux-style `PREFIX`, `HOME`, `TMPDIR`, XDG directories and
  workspace;
- the Termux bootstrap layer with `apt`/`dpkg`, shell, certificates and base
  Unix tools;
- first-run installation of Python, Node.js/npm, Perl, Ruby, Git, ripgrep and
  common build/archive/network utilities;
- a one-shot command runner for agent tools;
- explicit tool discovery and a setup/retry path in the standalone UI.

The first-run package setup uses the official Termux package repository. After
setup, the terminal has `python`, `node`, `npm`, `perl`, `ruby`, `git`, `rg`,
`bash`, `curl`, `make`, `tar`, `zip` and related tools in its private prefix.
The package installation is done inside the app sandbox; it does not need
Termux to be installed separately.

Bun is not duplicated here. The standalone app recognizes Bun as an optional
host-provided tool, and the future OpenCode app can point `HarnessConfig` at its
existing Bun/runtime directory.

## Architecture

```
app/                 actual standalone Coding Harness APK
harness-core/        reusable bootstrap, package, process, paths and tool APIs
terminal-view/       Termux terminal UI source
terminal-emulator/   Termux terminal emulator + PTY JNI source
ci/                  CI workflow template; copied manually into GitHub Actions
```

The reusable integration surface is:

```java
HarnessConfig config = HarnessConfig.builder(context)
    .workspace(existingWorkspace)
    .home(existingHome)
    .prefix(existingPrefix)
    .toolDirectory(existingToolDirectory)
    .libraryDirectory(existingLibraryDirectory)
    .shellPath("/system/bin/sh")
    .build();

HarnessSession session = HarnessSession.start(context, config, terminalSessionClient);
terminalView.attachSession(session.terminalSession());
```

The future main app can therefore reuse the process/runtime modules while
providing its own Compose/UI layer, OpenCode runtime supervisor and workspace
store. No `Activity`, `View`, OpenCode class or app package is referenced by
`harness-core`.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew test
```

Building the APK downloads and SHA-256 verifies the arm64-v8a and x86_64
Termux bootstrap archives into Gradle's generated build directory. They are not
checked into Git. The resulting APK contains both ABI bootstrap assets; the
runtime selects the device ABI at first launch.

The first app launch extracts the base bootstrap and then runs `apt-get update`
and installs the coding package set. That step needs network access and may
take a few minutes. If it fails, the shell remains usable and the SETUP button
retries it.

## CI workflow template

The workflow template is deliberately stored outside `.github/workflows`:

```text
ci/android-jdk-builder.yml
```

Copy it manually to `.github/workflows/android-jdk-builder.yml` in the target
repository. It selects Temurin JDK 17, installs Android SDK 35 and NDK
27.2.12479018, runs tests, downloads the bootstrap assets during the Gradle
build, and uploads the debug APK.

## Termux source provenance

`terminal-emulator/` and `terminal-view/` are derived from:

- repository: https://github.com/termux/termux-app
- source revision: `8629e632fcb95da272221be327db653fb24befe9`
- source modules: `terminal-emulator`, `terminal-view`

The bootstrap archive is fetched from the official `termux/termux-packages`
release during the build and is pinned by version and SHA-256 in
`app/build.gradle`. The copied modules retain the upstream
`com.termux.terminal` and `com.termux.view` package names so upstream fixes can
be merged cleanly.

See `TERMUX_SOURCE.md` for the integration and update procedure.
