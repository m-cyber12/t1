# Porting provisioning into OpenCode

The package/bootstrap layer is meant to be lifted into OpenCode's harness
(the TypeScript agent). This document records the conventions the Java code
now follows, the module-by-module mapping, and what remains to be done in
TypeScript.

## Conventions adopted (from OpenCode v2)

OpenCode builds on Effect: modules export a `Service` built with
`Context.Service`, wired through `Layer`s, exchanging typed values
(`Info`, `Error`) and publishing events. The Java harness mirrors that shape
so the port is a translation, not a redesign.

| OpenCode convention | Java equivalent in `core/provision` |
|---|---|
| `export * as Provisioning from "./provisioning"` | `Provisioning` (final class acting as the module namespace) |
| `class Service extends Context.Service<...>("@opencode/Provisioning")` | `Provisioning.Service` (interface) |
| `Layer.effect(Service, ...)` / `LayerNode.make({deps})` | `Provisioning.create(config)` and `create(config, PackageManager)` — dependencies are constructor-injected |
| `Instance.state()` per location | `HarnessPackageInstaller.service(config)` caches one service per prefix |
| Effect service methods return typed errors | `ProvisioningError` with a stable `Kind` enum instead of raw exceptions |
| `Info` schemas (`Config.Info`, `Agent.Info`) | `PackageTier.Info` — immutable descriptor: id, label, packages, requiredCommands, enabledByDefault |
| Pure functions separated from effects | `ProvisioningPlan.make(...)` — no I/O, deterministic, unit-tested |
| Bus events / subscriber streams | `Provisioning.Listener` + `subscribe()`; every transition publishes an immutable `ProvisioningState` |
| Status enums rendered by clients | `ProvisioningStatus` (IDLE … READY, FAILED) |
| Logger services (`Log.create({service})`) | states carry `message`/`detail`; failures carry `ProvisioningError.describe()` |

Two properties matter for portability and are now enforced by the design:

1. **No globals, no ambient state.** The service owns its state; the only
   static is a per-prefix service cache (the `Instance` analogue).
2. **Effects behind one port.** Every process spawn, dpkg read and apt index
   check goes through `PackageManager`. A port supplies its own
   implementation and the rest of the module is unchanged.

## Module mapping

| File (Java) | Becomes (TypeScript) | Notes |
|---|---|---|
| `provision/PackageTier.java` | `src/provisioning/tier.ts` — `Info` schema + `CORE`, `BUILD_TOOLS` | Use an Effect `Schema.Struct` so tier config can be validated/overridden from config |
| `provision/ProvisioningPlan.java` | `src/provisioning/plan.ts` — `plan()`, pure | Direct port; keep it I/O-free |
| `provision/ProvisioningStatus.java` | `src/provisioning/status.ts` — schema enum | Serialised in events |
| `provision/ProvisioningState.java` | `src/provisioning/state.ts` — `State` struct | Immutable snapshot; add `updatedAt` |
| `provision/ProvisioningError.java` | `src/provisioning/error.ts` — `NamedError.create(...)` per `Kind` | Keep kinds stable; clients branch on them |
| `provision/Provisioning.java` | `src/provisioning/index.ts` — `Service` + `layer` | Wrap methods in `Effect.fn("Provisioning.provision")` |
| `provision/DefaultProvisioning.java` | `src/provisioning/service.ts` | Convert `try/catch` into Effect error channel; `Thread.sleep` retry → `Effect.retry` with a schedule |
| `provision/PackageManager.java` | `src/provisioning/package-manager.ts` — `Context.Service` interface | Implement with OpenCode's shell + filesystem services |
| `provision/PrefixPackageManager.java` | target-specific implementation (Android/termux runtime) | Only file that knows process spawning and dpkg layout |
| `provision/StorageHygiene.java` | `src/provisioning/hygiene.ts` | Pure-ish filesystem work; keep conservative rules |
| `HarnessSizeReport.java` | `src/provisioning/size.ts` | dpkg parsing + unique-inode accounting |
| `HarnessBootstrap.java` (bootstrap, certs, keyring, symlink relocation) | `src/provisioning/bootstrap.ts` | Platform-specific; keep behind an interface if OpenCode hosts the prefix elsewhere |
| `HarnessEnvironment.java` (apt conf, env, preload ordering) | `src/provisioning/environment.ts` | Configuration as data, not ad-hoc strings |

## Port checklist

1. Model `Tier.Info` and `State` as Effect schemas; publish them on the bus
   so the TUI/server can stream progress (no polling).
2. Replace `PackageManager` with an Effect service that shells out through
   OpenCode's existing process service; keep the same four methods
   (`run`, `installedPackages`, `installedSizes`, `listsAreFresh`).
3. Wrap `provision`/`installTier` in `Effect.fn` and use `Effect.retry` for
   the index-refresh retry (the 20 s backoff exists because Termux
   republishes and the CDN can serve a stale `InRelease`).
4. Keep the pure planner intact — it is the piece worth testing hardest.
5. Keep the completion event as the single source of truth for UI state.
   The Android bug this design fixes (setup finished but the shell and TOOLS
   view stayed stale until restart) is exactly what event-driven state
   removes.
6. Keep `defaultTierIds()` = `[core]`: Perl and Ruby belong to core; the
   compiler tier is opt-in.
