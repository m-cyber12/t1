# Release checklist

Run through this before every upload. Items marked **(gate)** are enforced by CI;
the rest are human steps in the Play Console.

## Build

1. **(gate)** `Exec model (sealed)` is green on both matrix legs — API 34 and
   API 36 — with `Tests 3/3` each. That covers the core tier running from the
   APK, the payload verifying against its manifest, the second-launch skip,
   dpkg agreeing with what shipped, and the pack channel refusing `apt`.
2. **(gate)** The App Bundle carries `pack_buildtools` **on demand** and its
   payload; the base module stays under Play's 200 MB limit (the gate prints the
   size).
3. **(gate)** `Android JDK builder` is green: JVM tests (provisioning, sealed
   runtime, pack install/removal) plus the debug APK.
4. Build the artifact worth uploading:

   ```bash
   ./gradlew :app:bundleRelease -PharnessTargetSdk=36 -PharnessCompileSdk=36
   ```

   With `keystore.properties` present the task logs
   `signing bundleRelease with the release key (…)`. Without it, it logs that the
   debug key was used — **do not upload that artifact**.

5. `keystore.properties` (git-ignored) or the environment:

   ```
   storeFile=/absolute/path/to/upload.jks
   storePassword=…
   keyAlias=upload
   keyPassword=…
   ```

   Keep the key outside the repository and back it up. Play App Signing is
   enabled on first upload; the upload key is the one you keep.

## Console

6. Upload the `.aab` to the internal testing track first, install from Play, and
   confirm on a real device: first launch installs the toolchain, `bash` starts,
   `git --version` works, the build-tools pack installs and removes, and no
   permission dialog appears that the listing does not declare.
7. Data safety: answers in `store/data-safety.md`. Nothing is collected.
8. Content rating questionnaire: utility/productivity, 13+, no interactive
   features beyond a terminal.
9. Store listing: `store/listing.md`, screenshots and graphics as listed there.
10. Privacy policy URL: publish `store/privacy-policy.md` at a stable public URL
    and paste it in.
11. Target API level: 36 (required for new apps and updates since 2026-08-31).
12. Roll out to internal → closed → production, watching for install failures on
    16 KB page-size devices (the alignment gate covers the objects we build; the
    shipped Termux userland is reported separately by
    `scripts/check-page-alignment.py`).
