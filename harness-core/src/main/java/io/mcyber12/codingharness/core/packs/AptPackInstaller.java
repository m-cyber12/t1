package io.mcyber12.codingharness.core.packs;

import io.mcyber12.codingharness.core.provision.ProvisioningState;

/**
 * The installable build's pack channel: `apt`.
 *
 * <p>This is the F-Droid / direct-APK path, where fetching packages at runtime is
 * allowed. It delegates to the existing provisioning service, so tier install,
 * tier removal and the dependency sweep are unchanged — the point of this class
 * is that the UI no longer has to know which channel it is talking to.</p>
 */
public final class AptPackInstaller implements PackInstaller {

    /** What the provisioning service can do, narrowed to what a pack needs. */
    public interface Tiers {
        ProvisioningState installTier(String tierId);
        ProvisioningState removeTier(String tierId);
        boolean isReady();
    }

    private final Tiers tiers;
    private final java.io.File binDirectory;

    public AptPackInstaller(Tiers tiers, java.io.File binDirectory) {
        this.tiers = tiers;
        this.binDirectory = binDirectory;
    }

    @Override public String channel() { return "apt"; }

    @Override
    public PackState state(Pack pack) {
        if (PackCommands.present(binDirectory, pack)) {
            return new PackState(pack, PackState.Status.INSTALLED, channel(), null);
        }
        // The pack is installable only if the package manager itself is ready:
        // saying "available" on a prefix that has no apt yet would send the UI
        // into an install that cannot start.
        if (tiers.isReady()) {
            return new PackState(pack, PackState.Status.AVAILABLE_FROM_PACKAGES, channel(),
                PackCommands.missingFrom(binDirectory, pack));
        }
        return new PackState(pack, PackState.Status.UNAVAILABLE, "setup has not finished",
            PackCommands.missingFrom(binDirectory, pack));
    }

    @Override
    public PackResult install(Pack pack) {
        if (PackCommands.present(binDirectory, pack)) {
            return PackResult.ok(PackResult.Kind.UNCHANGED, pack.id, "already installed");
        }
        ProvisioningState state = tiers.installTier(pack.tierId);
        if (state != null && state.isFailed()) {
            return PackResult.failed(PackResult.Kind.INSTALL_FAILED, pack.id,
                state.message == null ? "provisioning failed" : state.message);
        }
        if (!PackCommands.present(binDirectory, pack)) {
            return PackResult.failed(PackResult.Kind.INSTALL_FAILED, pack.id,
                "tools still missing after install: "
                    + PackCommands.missingFrom(binDirectory, pack));
        }
        return PackResult.ok(PackResult.Kind.INSTALLED, pack.id,
            "installed from the package repository");
    }

    @Override
    public PackResult remove(Pack pack) {
        if (!PackCommands.present(binDirectory, pack)) {
            return PackResult.ok(PackResult.Kind.UNCHANGED, pack.id, "not installed");
        }
        ProvisioningState state = tiers.removeTier(pack.tierId);
        if (state != null && state.isFailed()) {
            return PackResult.failed(PackResult.Kind.INSTALL_FAILED, pack.id,
                state.message == null ? "removal failed" : state.message);
        }
        return PackResult.ok(PackResult.Kind.REMOVED, pack.id, "removed with its dependencies");
    }
}
