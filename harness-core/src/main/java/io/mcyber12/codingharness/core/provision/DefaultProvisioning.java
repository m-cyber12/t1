package io.mcyber12.codingharness.core.provision;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.codingharness.core.HarnessSizeReport;

/**
 * Default {@link Provisioning} implementation: resolve → update index →
 * upgrade base → install → verify → clean, publishing an immutable
 * {@link ProvisioningState} at every step.
 *
 * <p>Deliberately dependency-injected ({@link PackageManager}) and free of
 * global state so the same flow ports to OpenCode's Effect service with a
 * different {@code PackageManager} layer.</p>
 */
final class DefaultProvisioning implements Provisioning.Service {

    private static final String MARKER = ".coding-toolchain-installed";
    private static final long UPDATE_TIMEOUT = 30 * 60_000L;
    private static final long CONFIGURE_TIMEOUT = 30 * 60_000L;
    private static final long UPGRADE_TIMEOUT = 45 * 60_000L;
    private static final long INSTALL_TIMEOUT = 45 * 60_000L;
    private static final long CLEAN_TIMEOUT = 5 * 60_000L;

    private final HarnessConfig config;
    private final PackageManager packages;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ProvisioningState state = ProvisioningState.idle();

    DefaultProvisioning(HarnessConfig config, PackageManager packages) {
        if (config == null) throw new IllegalArgumentException("config is required");
        if (packages == null) throw new IllegalArgumentException("package manager is required");
        this.config = config;
        this.packages = packages;
    }

    @Override public ProvisioningState state() { return state; }

    @Override public void subscribe(Listener listener) {
        if (listener == null) return;
        listeners.add(listener);
        listener.onState(state); // clients never need a separate initial read
    }

    @Override public void unsubscribe(Listener listener) { listeners.remove(listener); }

    @Override public boolean isReady() {
        return presentCommands(PackageTier.CORE.requiredCommands)
            .containsAll(PackageTier.CORE.requiredCommands);
    }

    @Override public List<String> defaultTierIds() {
        List<String> ids = new ArrayList<>();
        for (PackageTier.Info tier : PackageTier.defaults()) ids.add(tier.id);
        return ids;
    }

    @Override
    public ProvisioningState provision(List<String> tierIds) {
        List<PackageTier.Info> tiers = resolve(tierIds);
        if (!running.compareAndSet(false, true)) {
            return state; // one provisioning run at a time; report current phase
        }
        try {
            publish(ProvisioningStatus.RESOLVING, "checking installed tools");
            ProvisioningPlan plan = planFor(tiers);
            if (plan.isSatisfied()) {
                writeMarker();
                return publish(ProvisioningStatus.READY,
                    "coding toolchain already present", sizeSummary(tiers));
            }
            if (!packages.listsAreFresh()) {
                publish(ProvisioningStatus.UPDATING_INDEX, "refreshing package index");
                PackageManager.CommandResult update = updateWithRetry();
                if (!update.isSuccess()) {
                    return fail(new ProvisioningError(ProvisioningError.Kind.INDEX,
                        "apt-get update failed", compact(update.output)));
                }
            }

            // A previous run may have died mid-transaction (device-only
            // failures), leaving packages unpacked-but-unconfigured: dpkg
            // then refuses further work. Configure pending packages first;
            // a no-op on a clean system.
            publish(ProvisioningStatus.UPGRADING_BASE, "upgrading base system");
            bestEffort(run(Arrays.asList("dpkg", "--force-script-chrootless",
                "--force-confold", "--configure", "-a"), CONFIGURE_TIMEOUT));
            PackageManager.CommandResult upgrade = run(
                Arrays.asList("apt-get", "-y", "upgrade"), UPGRADE_TIMEOUT);
            if (!upgrade.isSuccess()) {
                return fail(new ProvisioningError(ProvisioningError.Kind.BASE_UPGRADE,
                    "base system upgrade failed", compact(upgrade.output)));
            }

            publish(ProvisioningStatus.INSTALLING,
                "installing " + plan.packagesToInstall.size() + " packages");
            PackageManager.CommandResult install = run(
                installCommand(plan.packagesToInstall), INSTALL_TIMEOUT);
            if (!install.isSuccess()) {
                return fail(new ProvisioningError(ProvisioningError.Kind.INSTALL,
                    "package install failed", compact(install.output)));
            }

            publish(ProvisioningStatus.VERIFYING, "verifying tools");
            List<String> missing = missingCommands(tiers);
            if (!missing.isEmpty()) {
                return fail(new ProvisioningError(ProvisioningError.Kind.VERIFY,
                    "required commands missing after install: " + missing,
                    sizeSummary(tiers)));
            }

            publish(ProvisioningStatus.CLEANING, "reclaiming storage");
            String hygiene = applyHygiene();
            writeMarker();
            return publish(ProvisioningStatus.READY,
                "ready: " + labels(tiers) + " (" + hygiene + ")", sizeSummary(tiers));
        } catch (IOException e) {
            return fail(new ProvisioningError(ProvisioningError.Kind.IO,
                "package setup I/O failed: " + e.getMessage(), ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(new ProvisioningError(ProvisioningError.Kind.INTERRUPTED,
                "package setup was interrupted", ""));
        } finally {
            running.set(false);
        }
    }

    @Override
    public ProvisioningState installTier(String tierId) {
        PackageTier.Info tier = PackageTier.byId(tierId);
        if (!running.compareAndSet(false, true)) return state;
        try {
            ProvisioningPlan plan = planFor(Collections.singletonList(tier));
            if (plan.isSatisfied()) {
                return publish(ProvisioningStatus.READY, tier.label + " already installed",
                    sizeSummary(Collections.singletonList(tier)));
            }
            if (!packages.listsAreFresh()) {
                publish(ProvisioningStatus.UPDATING_INDEX, "refreshing package index");
                PackageManager.CommandResult update = updateWithRetry();
                if (!update.isSuccess()) {
                    return fail(new ProvisioningError(ProvisioningError.Kind.INDEX,
                        tier.label + ": apt-get update failed", compact(update.output)));
                }
            }
            publish(ProvisioningStatus.INSTALLING, "installing " + tier.label);
            PackageManager.CommandResult install = run(
                installCommand(plan.packagesToInstall), INSTALL_TIMEOUT);
            if (!install.isSuccess()) {
                return fail(new ProvisioningError(ProvisioningError.Kind.INSTALL,
                    tier.label + " install failed", compact(install.output)));
            }
            publish(ProvisioningStatus.VERIFYING, "verifying " + tier.label);
            List<String> missing = missingCommands(Collections.singletonList(tier));
            if (!missing.isEmpty()) {
                return fail(new ProvisioningError(ProvisioningError.Kind.VERIFY,
                    tier.label + ": required commands missing: " + missing, ""));
            }
            publish(ProvisioningStatus.CLEANING, "reclaiming storage");
            String hygiene = applyHygiene();
            return publish(ProvisioningStatus.READY,
                tier.label + " installed (" + hygiene + ")",
                sizeSummary(Collections.singletonList(tier)));
        } catch (IOException e) {
            return fail(new ProvisioningError(ProvisioningError.Kind.IO,
                tier.label + " I/O failed: " + e.getMessage(), ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(new ProvisioningError(ProvisioningError.Kind.INTERRUPTED,
                tier.label + " install was interrupted", ""));
        } finally {
            running.set(false);
        }
    }

    // ---- planning -------------------------------------------------------

    private List<PackageTier.Info> resolve(List<String> tierIds) {
        if (tierIds == null || tierIds.isEmpty()) return PackageTier.defaults();
        List<PackageTier.Info> tiers = new ArrayList<>();
        for (String id : tierIds) tiers.add(PackageTier.byId(id));
        return tiers;
    }

    private ProvisioningPlan planFor(List<PackageTier.Info> tiers) {
        return ProvisioningPlan.make(tiers, packages.installedPackages(),
            presentCommands(requiredCommands(tiers)));
    }

    private static List<String> requiredCommands(List<PackageTier.Info> tiers) {
        Set<String> commands = new LinkedHashSet<>();
        for (PackageTier.Info tier : tiers) commands.addAll(tier.requiredCommands);
        return new ArrayList<>(commands);
    }

    private Set<String> presentCommands(List<String> commands) {
        Set<String> present = new LinkedHashSet<>();
        for (String command : commands) {
            File executable = new File(config.toolDirectory, command);
            if (executable.isFile() && executable.canExecute()) present.add(command);
        }
        return present;
    }

    private List<String> missingCommands(List<PackageTier.Info> tiers) {
        List<String> required = requiredCommands(tiers);
        Set<String> present = presentCommands(required);
        List<String> missing = new ArrayList<>();
        for (String command : required) {
            if (!present.contains(command)) missing.add(command);
        }
        return missing;
    }

    private static List<String> installCommand(List<String> packagesToInstall) {
        List<String> command = new ArrayList<>();
        command.add("apt-get");
        command.add("-y");
        command.add("install");
        command.addAll(packagesToInstall);
        return command;
    }

    // ---- effects --------------------------------------------------------

    private PackageManager.CommandResult run(List<String> command, long timeout)
            throws IOException, InterruptedException {
        return packages.run(command, timeout);
    }

    /** Some steps (dpkg --configure -a) are advisory only. */
    private void bestEffort(List<String> command) {
        try {
            run(command, CONFIGURE_TIMEOUT);
        } catch (IOException | InterruptedException ignored) {
            // The upgrade that follows surfaces real problems.
        }
    }

    /**
     * Termux republishes its index frequently and the CDN can briefly serve a
     * stale InRelease against a newer Packages ("unexpected size"). The window
     * closes in seconds, so one backed-off retry keeps setup robust without
     * hiding persistent failures.
     */
    private PackageManager.CommandResult updateWithRetry()
            throws IOException, InterruptedException {
        PackageManager.CommandResult update = run(
            Arrays.asList("apt-get", "update"), UPDATE_TIMEOUT);
        if (update.isSuccess()) return update;
        Thread.sleep(20_000L);
        return run(Arrays.asList("apt-get", "update"), UPDATE_TIMEOUT);
    }

    /** apt cache + documentation payload; returns a short summary. */
    private String applyHygiene() {
        long cacheBefore = HarnessSizeReport.directoryBytes(
            new File(config.prefix, "var/cache/apt/archives"));
        try {
            run(Arrays.asList("apt-get", "clean"), CLEAN_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Java-side prune below still reclaims the payload bytes.
        }
        long pruned = StorageHygiene.prune(config.prefix);
        long cacheAfter = HarnessSizeReport.directoryBytes(
            new File(config.prefix, "var/cache/apt/archives"));
        long reclaimed = Math.max(0L, cacheBefore - cacheAfter) + pruned;
        return "reclaimed " + HarnessSizeReport.formatBytes(reclaimed);
    }

    private String sizeSummary(List<PackageTier.Info> tiers) {
        StringBuilder out = new StringBuilder();
        out.append(HarnessSizeReport.totalInstalledSize(config));
        out.append("; tiers: ");
        for (int i = 0; i < tiers.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(tiers.get(i).id);
        }
        return out.toString();
    }

    private static String labels(List<PackageTier.Info> tiers) {
        StringBuilder out = new StringBuilder();
        for (PackageTier.Info tier : tiers) {
            if (out.length() > 0) out.append(" + ");
            out.append(tier.label);
        }
        return out.toString();
    }

    private void writeMarker() {
        File marker = new File(config.prefix.getParentFile(), MARKER);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write("coding-toolchain-v2".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The command check stays authoritative; the marker is a hint.
        }
    }

    // ---- events ---------------------------------------------------------

    private ProvisioningState publish(ProvisioningStatus status, String message) {
        return publish(status, message, "");
    }

    private ProvisioningState publish(ProvisioningStatus status, String message, String detail) {
        ProvisioningState next = ProvisioningState.of(status, message, detail);
        state = next;
        for (Listener listener : listeners) {
            try {
                listener.onState(next);
            } catch (RuntimeException ignored) {
                // A broken observer must never break provisioning.
            }
        }
        return next;
    }

    private ProvisioningState fail(ProvisioningError error) {
        ProvisioningState next = ProvisioningState.failed(error);
        state = next;
        for (Listener listener : listeners) {
            try {
                listener.onState(next);
            } catch (RuntimeException ignored) {
                // See above.
            }
        }
        return next;
    }

    // ---- text helpers ---------------------------------------------------

    /**
     * Keeps the head, error-bearing lines (the cause usually sits mid-log) and
     * the tail within a small budget so dialogs stay actionable.
     */
    static String compact(String output) {
        if (output == null) return "no output";
        String normalized = output.replace('\r', '\n');
        if (normalized.length() <= 900) return normalized.replace('\n', ' ').trim();
        StringBuilder errors = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String candidate = line.trim();
            String lower = candidate.toLowerCase(Locale.US);
            if (lower.contains("error") || lower.contains("denied")
                || lower.contains("cannot") || lower.contains("overwrite")
                || lower.contains("no space") || lower.contains("failed")
                || lower.startsWith("e:") || lower.startsWith("dpkg:")) {
                if (errors.length() > 0) errors.append(" | ");
                errors.append(candidate);
                if (errors.length() >= 1100) break;
            }
        }
        String flat = normalized.replace('\n', ' ').trim();
        String head = flat.substring(0, 300);
        String tail = flat.substring(flat.length() - 300);
        return head + " … " + errors + " … " + tail;
    }
}
