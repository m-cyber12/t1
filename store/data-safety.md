# Data safety answers

Play requires a declaration for every app. These are the answers this app can
honestly give, with the code that makes them true.

## Summary

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data encrypted in transit? | Not applicable — no data leaves the device |
| Do you provide a way for users to request that their data be deleted? | Not applicable — the app stores data only on the device; uninstalling removes it |
| Do you have a privacy policy? | Yes — `store/privacy-policy.md`, hosted at a public URL before submission |

## Why "no collection" is accurate

| Data type | Collected? | Evidence |
|---|---|---|
| Personal info (name, email, address, ID) | No | No account, no sign-in, no `INTERNET` calls other than the package repository the user drives themselves |
| Financial info | No | No billing integration |
| Health / fitness | No | — |
| Messages, contacts, calendar | No | No such permissions requested |
| Location | No | No location permissions; no location APIs referenced |
| Web browsing history | No | No WebView history is read or transmitted |
| App activity / in-app search | No | Terminal input is not logged off-device; `harness-command.log` stays in the app's own directory |
| Device or other IDs | No | No advertising ID, no `ANDROID_ID` read, no analytics SDK |
| Crash logs / diagnostics | No | No crash-reporting SDK; failures are written to `bootstrap-error.log` **on the device** |

## What the app does with the network

Two things, both user-visible and neither involving a person's data:

1. **The installable build's package manager** fetches signed package archives
   from the public Termux repository when the user runs setup or installs a
   pack. This is the app's own toolchain, not user data.
2. **Nothing else.** The Play build does not fetch code at runtime at all: its
   toolchain ships in the APK and its optional pack is a Play Feature Delivery
   module (`pack_buildtools/`).

## Permissions

The app declares `INTERNET` (the package manager) and nothing else that Play
treats as sensitive. The merged manifest is read on every CI run, and the
publishable build is gated on it — see the "Verify the sealed APK shape" step in
`ci/play-exec-model.yml`.

## Children

The app is a developer tool aimed at a general 13+ audience. It does not collect
data from anyone, so it makes no difference to the declaration, but it is not
designed or marketed for children.
