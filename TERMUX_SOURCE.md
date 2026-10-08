# Termux source integration

The terminal engine in this project is based on the upstream Termux app. Only
the reusable pieces needed for an embedded terminal were copied:

- `terminal-emulator/src/main/java/com/termux/terminal/**`
- `terminal-emulator/src/main/jni/**`
- `terminal-view/src/main/java/com/termux/view/**`
- `terminal-view/src/main/java/com/termux/view/support/**`
- `terminal-view/src/main/java/com/termux/view/textselection/**`

The source revision is recorded in `README.md`. The modules remain separate
Gradle Android libraries rather than being flattened into the app, which makes
it possible to replace them with a newer upstream revision or add the modules
to the main app directly.

## Updating the Termux pieces

1. Check out the desired `termux-app` revision.
2. Compare the two upstream module directories with this repository.
3. Copy only the paths listed above; do not copy Termux's complete application,
   bootstrap archives, package manager or plugin integrations.
4. Re-run `./gradlew test :app:assembleDebug`.
5. Update the revision in `README.md` and this document.

The JNI helper is required for a real PTY. It is built for `arm64-v8a` and
`x86_64`, matching the coding harness's first device targets.
