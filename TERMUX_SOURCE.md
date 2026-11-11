# Termux source integration

The terminal engine in this project is based on the upstream Termux app. Only
the reusable pieces needed for an embedded coding terminal were copied:

- `terminal-emulator/src/main/java/com/termux/terminal/**`
- `terminal-emulator/src/main/jni/**`
- `terminal-view/src/main/java/com/termux/view/**`
- `terminal-view/src/main/java/com/termux/view/support/**`
- `terminal-view/src/main/java/com/termux/view/textselection/**`

The source revision is recorded in `README.md`. The modules remain separate
Gradle Android libraries rather than being flattened into the app, which makes
it possible to replace them with a newer upstream revision or add the modules
to the main app directly.

## Bootstrap and packages

The standalone app also downloads the official Termux `apt-android-7`
bootstrap archives at build time. The bootstrap supplies the base `PREFIX`
layout, apt/dpkg, certificates, shell and base utilities. It does not contain
the entire Termux package catalog.

On first run, `HarnessPackageInstaller` runs the Termux package manager for the
focused coding set: Python, Node.js/npm, Perl, Ruby, Git, ripgrep, core shell
utilities, archive/network tools and build helpers. This keeps the APK source
small while giving the installed app the expected coding harness environment.

## Updating the Termux pieces

1. Check out the desired `termux-app` revision.
2. Compare the two upstream module directories with this repository.
3. Copy only the paths listed above; do not copy Termux's complete application,
   plugin integrations or settings UI.
4. If the package/bootstrap release changes, update its version and SHA-256 in
   `app/build.gradle` and the matching runtime marker in `HarnessBootstrap`.
5. Re-run `./gradlew test :app:assembleDebug`.
6. Update the revision in `README.md` and this document.

The JNI helper is required for a real PTY. It is built for `arm64-v8a` and
`x86_64`, matching the coding harness's first device targets.
