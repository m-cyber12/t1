# Coding Harness for Android

A small, reusable Android terminal harness for coding agents. This is a
separate app today, but the process/runtime code is kept in `harness-core` so it
can be added to the OpenCode Android app later without carrying the sample UI
with it.

## Scope

This is intentionally **not a complete Termux distribution**. It provides the
pieces an on-device coding agent needs:

- a real PTY-backed interactive shell;
- a terminal emulator and touch/keyboard terminal view;
- a stable app-private `HOME`, `TMPDIR`, XDG directories and workspace;
- a tool directory where the main app can place Bun, Git and ripgrep;
- a one-shot command runner for agent tools;
- explicit tool discovery, including the runtimes that are intentionally absent.

The standalone APK uses Android's `/system/bin/sh`. It does not bundle a Linux
userspace, package manager, Python, npm, Perl or Ruby. Bun, Git and ripgrep are
recognized by the harness and can be supplied later by the main app's existing
runtime payload. This keeps this repository small and avoids duplicating the
OpenCode runtime.

## Architecture

```
app/                 small demo Activity; replaceable
harness-core/        reusable process, paths, environment and tool APIs
terminal-view/       Termux terminal UI source
terminal-emulator/   Termux terminal emulator + PTY JNI source
```

The two terminal modules are copied from the upstream Termux app source and
kept as independent Android libraries. The reusable integration surface is:

```java
HarnessConfig config = HarnessConfig.builder(context)
    .workspace(existingWorkspace)
    .home(existingHome)
    .toolDirectory(existingToolDirectory)
    .libraryDirectory(existingLibraryDirectory)
    .shellPath("/system/bin/sh")
    .build();

HarnessSession session = HarnessSession.start(context, config, terminalSessionClient);
terminalView.attachSession(session.terminalSession());
```

The future main app can therefore reuse `harness-core` and the two terminal
libraries while providing its own Compose/UI layer, runtime supervisor and
workspace store. No `Activity`, `View`, OpenCode class or app package is
referenced by `harness-core`.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew test
```

The supported user ABI targets are `arm64-v8a` and `x86_64`. The PTY helper is
compiled with the Android NDK. CI uses JDK 17, installs the pinned Android SDK
and NDK, runs unit tests, then builds the debug APK.

## CI

`.github/workflows/android.yml` is the JDK builder workflow. It explicitly
selects Temurin JDK 17, installs Android platform/build tools plus NDK, verifies
that the four Gradle modules are present, runs tests, and produces the debug APK
as a workflow artifact.

## Termux source provenance

`terminal-emulator/` and `terminal-view/` are derived from:

- repository: https://github.com/termux/termux-app
- source revision: `8629e632fcb95da272221be327db653fb24befe9`
- source modules: `terminal-emulator`, `terminal-view`

They retain the upstream `com.termux.terminal` and `com.termux.view` package
names so upstream fixes can be merged cleanly. The copied modules are the
terminal engine and UI only; Termux's complete app shell, bootstrap archive,
package manager, plugins and preferences are deliberately not included.

See `TERMUX_SOURCE.md` for the integration notes and update procedure.
