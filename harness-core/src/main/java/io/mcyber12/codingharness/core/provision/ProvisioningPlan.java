package io.mcyber12.codingharness.core.provision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure decision layer: given the requested tiers and the observed machine
 * state, decide what still has to be installed.
 *
 * <p>No I/O, no Android, no process spawning — deterministic input/output, so
 * it is fully unit-testable and ports to TypeScript unchanged (OpenCode keeps
 * the same separation between pure {@code plan} functions and effectful
 * services).</p>
 */
public final class ProvisioningPlan {

    /** Tiers whose required commands are all present — nothing to do. */
    public final List<PackageTier.Info> satisfiedTiers;
    /** Tiers that still need work, in request order. */
    public final List<PackageTier.Info> pendingTiers;
    /** Packages to request, excluding those dpkg already has installed. */
    public final List<String> packagesToInstall;
    /** Required commands that are missing right now. */
    public final List<String> missingCommands;

    private ProvisioningPlan(List<PackageTier.Info> satisfied,
                             List<PackageTier.Info> pending,
                             List<String> packagesToInstall,
                             List<String> missingCommands) {
        this.satisfiedTiers = Collections.unmodifiableList(satisfied);
        this.pendingTiers = Collections.unmodifiableList(pending);
        this.packagesToInstall = Collections.unmodifiableList(packagesToInstall);
        this.missingCommands = Collections.unmodifiableList(missingCommands);
    }

    /** True when every requested tier is already satisfied. */
    public boolean isSatisfied() { return pendingTiers.isEmpty(); }

    public static ProvisioningPlan make(List<PackageTier.Info> requested,
                                        Set<String> installedPackages,
                                        Set<String> presentCommands) {
        Set<String> installed = installedPackages == null
            ? Collections.<String>emptySet() : installedPackages;
        Set<String> present = presentCommands == null
            ? Collections.<String>emptySet() : presentCommands;

        List<PackageTier.Info> satisfied = new ArrayList<>();
        List<PackageTier.Info> pending = new ArrayList<>();
        Set<String> packages = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();

        for (PackageTier.Info tier : requested) {
            List<String> absent = new ArrayList<>();
            for (String command : tier.requiredCommands) {
                if (!present.contains(command)) absent.add(command);
            }
            if (absent.isEmpty()) {
                satisfied.add(tier);
                continue;
            }
            pending.add(tier);
            missing.addAll(absent);
            for (String pkg : tier.packages) {
                // Reinstalling what dpkg already has only costs time.
                if (!installed.contains(pkg)) packages.add(pkg);
            }
        }
        return new ProvisioningPlan(satisfied, pending,
            new ArrayList<>(packages), new ArrayList<>(missing));
    }
}
