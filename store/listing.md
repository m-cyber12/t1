# Play Store listing (draft)

Everything a listing needs, in the order Play Console asks for it. The values
that are *claims* are marked with the evidence that backs them, because a
listing is the one place where an unsupported claim becomes a policy problem.

## App identity

| Field | Value |
|---|---|
| App name | Coding Harness |
| Package | `io.mcyber12.codingharness` |
| Category | Developer tools |
| Contact | (support address, set in the console) |
| Default language | English (United States) |

## Store presence

**Short description (80 characters max)**

```
A real Linux toolchain for coding agents: bash, git, Python, Node, Perl, Ruby.
```
(78 characters.)

**Full description**

```
Coding Harness gives your Android device a complete, working development
environment — the same tools a coding agent or a human expects on a laptop —
without a root shell, without a VM, and without installing Termux first.

WHAT YOU GET

• A real bash shell with a real prefix, not an emulator or a mock
• git 2.56, ripgrep, Python 3 with pip, Node with npm, Perl, Ruby, jq, tree,
  file, openssh and curl
• An on-device package manager, so the environment can repair and extend itself
• An optional build-tools pack (clang, cmake, make, pkg-config) you install only
  if you compile native code — and remove again when you are done
• Sizes reported honestly: the harness counts unique bytes on disk, not the sum
  of every hard link, so "git uses 543 MB" never happens here

HOW IT WORKS

The toolchain ships inside the app. Android only allows an app to execute code
that came from its own APK, so the executables are packaged as native libraries
and linked into a normal `…/usr` prefix: `bin/git`, `bin/python` and `bin/ruby`
are ordinary paths that your scripts, `#!` lines and build systems already
expect. Everything that is only read — Python's standard library, gems, the
package database — is unpacked as data.

Your projects live in your own storage. Nothing is collected, nothing is sent
anywhere, and the app works offline once installed.

WHO IT IS FOR

• Developers who want a real shell on a tablet or a phone
• Coding agents that need git, a language runtime and a package manager
• Anyone learning Unix who does not want to root a device
```

## Graphics

| Asset | Size | Status |
|---|---|---|
| App icon | 512 × 512 PNG, 32-bit | **to produce** — a terminal prompt glyph on a dark field; no third-party marks |
| Feature graphic | 1024 × 500 PNG/JPEG | **to produce** — a shell session transcript, legible at thumbnail size |
| Phone screenshots | 2–8, min 1080 px on the short side | **to capture** — (1) a fresh setup, (2) `git status` in a real repo, (3) `python -c` + `pip install`, (4) the toolchain dialog with sizes, (5) the build-tools pack before/after |
| 7-inch / 10-inch tablet screenshots | optional | reuse the phone set if the layout holds |

No screenshot may show a real user's project names, API keys or terminal history.

## Store settings

| Setting | Answer | Why |
|---|---|---|
| App or game | App | — |
| Free or paid | Free | — |
| Contains ads | No | No ad SDK in the dependency graph |
| In-app purchases | No | Packs are free store modules, not purchases |
| Content rating questionnaire | Utility / productivity, no user-generated content sharing, no violence, no gambling | The app has no social or content features |
| Target audience | 13+ (developer audience) | A terminal is not a child-directed experience |
| Data safety | **No data collected, no data shared** — see `store/data-safety.md` | The app has no analytics, no crash reporting, no accounts |
| News app | No | — |
| COVID-19 apps | No | — |
| Government app | No | — |
| Financial features | No | — |
| Health | No | — |
| Permissions declaration | None of the sensitive-permission forms apply; the app requests no `MANAGE_EXTERNAL_STORAGE`, no `QUERY_ALL_PACKAGES`, no location, no SMS, no call log | Verified by the merged manifest gate (`ci/play-exec-model.yml`) |
| Ads declaration | No ads | — |

## Release notes (first submission)

```
First release. Ships bash 5.3, git 2.56, Python 3, Node, Perl, Ruby, ripgrep and
the usual Unix utilities inside the app, so a coding environment is ready on
first launch with no download. An optional build-tools pack adds clang, cmake,
make and pkg-config and can be removed to reclaim its space.
```
