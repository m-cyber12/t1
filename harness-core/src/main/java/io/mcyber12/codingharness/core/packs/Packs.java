package io.mcyber12.codingharness.core.packs;

import android.content.Context;

import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.codingharness.core.HarnessPackageInstaller;
import io.mcyber12.codingharness.core.exec.SealedPayloadInstaller;

/**
 * Chooses the pack channel for this installation.
 *
 * <p>One predicate decides: does this APK carry a sealed payload? If it does, the
 * build is the store-shaped one and packs must come from Play Feature Delivery —
 * it may not fetch executables from the network at runtime, and its own binaries
 * already run out of the APK. If it does not, this is the F-Droid/direct build,
 * where {@code apt} is both allowed and expected.</p>
 *
 * <p>Keeping the decision in one place is what stops a store build from quietly
 * falling back to {@code apt} (which would break the policy) and an installable
 * build from asking for a module that is not in its bundle.</p>
 */
public final class Packs {

    private Packs() {}

    /** True when this APK is the sealed (store-shaped) build. */
    public static boolean isSealedBuild(Context context) {
        return SealedPayloadInstaller.pickAbi(context) != null;
    }

    /**
     * The installer this build should use.
     *
     * @param config the harness configuration (prefix and tool directory)
     */
    public static PackInstaller installer(Context context, HarnessConfig config) {
        if (isSealedBuild(context)) {
            return new PlayPackInstaller(context.getApplicationContext(), config);
        }
        return new AptPackInstaller(
            new AptPackInstaller.Tiers() {
                @Override
                public io.mcyber12.codingharness.core.provision.ProvisioningState
                        installTier(String tierId) {
                    return HarnessPackageInstaller.service(config).installTier(tierId);
                }

                @Override
                public io.mcyber12.codingharness.core.provision.ProvisioningState
                        removeTier(String tierId) {
                    return HarnessPackageInstaller.service(config).removeTier(tierId);
                }

                @Override
                public boolean isReady() {
                    return HarnessPackageInstaller.service(config).isReady();
                }
            },
            config.toolDirectory);
    }
}
