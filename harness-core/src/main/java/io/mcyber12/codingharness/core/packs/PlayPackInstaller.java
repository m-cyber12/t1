package io.mcyber12.codingharness.core.packs;

import android.content.Context;
import android.content.pm.PackageManager;

import com.google.android.play.core.splitinstall.SplitInstallManager;
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory;
import com.google.android.play.core.splitinstall.SplitInstallRequest;
import com.google.android.play.core.splitinstall.SplitInstallSessionState;
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener;
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.codingharness.core.exec.SealedPayloadInstaller;

/**
 * The store build's pack channel: Play Feature Delivery.
 *
 * <p>On Google Play an app may not download executable code outside the store,
 * and it may not {@code execve} anything that did not come out of an APK. Both
 * rules are satisfied the same way: the pack is a dynamic feature module, Play
 * downloads it as an APK, and its executables arrive as {@code lib<tool>.so} in
 * the app's native library directory — the same place the base userland lives.
 * From there the existing {@link io.mcyber12.sealed.SealedInstaller} applies it
 * to the prefix, so a pack needs no installation logic of its own.</p>
 *
 * <p>Three sources are tried, in this order, because a build can legitimately
 * have any of them:</p>
 * <ol>
 *   <li>the payload is already on the device (a universal APK fuses the module,
 *       which is how a sideloaded or emulator build behaves);</li>
 *   <li>Play has the module installed (a previous download);</li>
 *   <li>Play can fetch the module (an on-demand install from the store).</li>
 * </ol>
 * <p>A device with no Play Store is not an error state: the pack reports
 * {@link PackResult.Kind#UNAVAILABLE} and names the channel that was missing,
 * which is exactly what an F-Droid user needs to see.</p>
 */
public final class PlayPackInstaller implements PackInstaller {

    /** Play's package name, used only to explain an unavailable pack. */
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";

    /** How long a store download may take before the UI is told it stalled. */
    private static final long DELIVERY_TIMEOUT_SECONDS = 600;

    private final Context context;
    private final HarnessConfig config;

    public PlayPackInstaller(Context context, HarnessConfig config) {
        this.context = context;
        this.config = config;
    }

    @Override public String channel() { return "play-feature-delivery"; }

    @Override
    public PackState state(Pack pack) {
        // What was delivered is recorded when it is delivered, because a pack's
        // payload is not always the same size: CI ships make and pkg-config, a
        // release ships those plus clang and cmake. Asking "are all four here?"
        // would call a perfectly good CI install broken.
        List<String> delivered = deliveredCommands(pack);
        if (!delivered.isEmpty()) {
            List<String> missing = PackCommands.missingFrom(config.toolDirectory, delivered);
            if (missing.isEmpty()) {
                return new PackState(pack, PackState.Status.INSTALLED, channel(), null);
            }
            return new PackState(pack, PackState.Status.AVAILABLE_LOCALLY, channel(), missing);
        }

        String abi = SealedPayloadInstaller.pickAbi(context, pack.assetPrefix);
        if (abi != null) {
            return new PackState(pack, PackState.Status.AVAILABLE_LOCALLY, channel(),
                PackCommands.missingFrom(config.toolDirectory, pack));
        }
        if (playStorePresent() || moduleInstalled(pack)) {
            return new PackState(pack, PackState.Status.AVAILABLE_FROM_STORE, channel(),
                PackCommands.missingFrom(config.toolDirectory, pack));
        }
        return new PackState(pack, PackState.Status.UNAVAILABLE, "no store on this device",
            PackCommands.missingFrom(config.toolDirectory, pack));
    }

    @Override
    public PackResult install(Pack pack) {
        List<String> alreadyDelivered = deliveredCommands(pack);
        if (!alreadyDelivered.isEmpty()
                && PackCommands.missingFrom(config.toolDirectory, alreadyDelivered).isEmpty()) {
            return PackResult.ok(PackResult.Kind.UNCHANGED, pack.id, "already installed");
        }

        // 1. Bytes already on the device (a fused module in a universal APK).
        if (SealedPayloadInstaller.pickAbi(context, pack.assetPrefix) != null) {
            return apply(pack);
        }

        // 2. A module Play already delivered: make its assets visible to this
        //    process, then apply them.
        boolean delivered = moduleInstalled(pack);
        // 3. Otherwise ask the store — but not if there is no store to ask.
        //    Without this check the request would wait out its full timeout on
        //    a device that could never answer, and a test would look hung.
        if (!delivered && !playStorePresent()) {
            return PackResult.failed(PackResult.Kind.UNAVAILABLE, pack.id,
                "this build carries no pack payload and there is no Play Store to fetch it");
        }
        if (!delivered) {
            PackResult delivery = requestDelivery(pack);
            if (delivery != null) return delivery;
        }
        return apply(pack);
    }

    @Override
    public PackResult remove(Pack pack) {
        String abi = SealedPayloadInstaller.pickAbi(context, pack.assetPrefix);
        if (abi == null) {
            return PackResult.ok(PackResult.Kind.UNCHANGED, pack.id,
                "this build does not carry the pack");
        }
        // Exact removal, from the payload's own manifests: the executables it
        // linked and the files it wrote, nothing else. The kernel does it, which
        // is why removal is unit-tested rather than hoped for.
        io.mcyber12.sealed.SealedUninstaller.Result removed =
            io.mcyber12.sealed.SealedUninstaller.remove(config.prefix,
                SealedPayloadInstaller.readManifestLines(context, abi, pack.assetPrefix, "exec"),
                SealedPayloadInstaller.readManifestLines(context, abi, pack.assetPrefix, "manifest"));

        // Release the module itself, so Play reclaims its share too.
        try {
            SplitInstallManagerFactory.create(context)
                .deferredUninstall(Collections.singletonList(pack.moduleName));
        } catch (Throwable noStore) {
            // No store: the files above are the whole story.
        }

        if (!removed.ok()) {
            return new PackResult(PackResult.Kind.INSTALL_FAILED, pack.id,
                "pack removed with problems", removed.bytes, removed.problems);
        }
        List<String> wasDelivered = deliveredCommands(pack);
        if (wasDelivered.isEmpty()) {
            wasDelivered = PackCommands.commandsIn(
                SealedPayloadInstaller.readManifestLines(context, abi, pack.assetPrefix, "exec"));
        }
        List<String> still = PackCommands.presentIn(config.toolDirectory, wasDelivered);
        if (!still.isEmpty()) {
            return new PackResult(PackResult.Kind.INSTALL_FAILED, pack.id,
                "tools are still present after removal", removed.bytes, still);
        }
        // The records go too, so a reinstall starts from a clean slate and the
        // next state() report does not describe files that are gone.
        File record = deliveredRecord(pack);
        if (record != null) record.delete();
        File report = SealedPayloadInstaller.reportFile(config, pack.assetPrefix);
        if (report != null) report.delete();
        return new PackResult(PackResult.Kind.REMOVED, pack.id,
            "removed; the core runtime is untouched", removed.bytes, null);
    }

    // ---- internals ------------------------------------------------------

    /**
     * Make a delivered split's assets visible to this process.
     *
     * <p>Called reflectively on purpose: the call is
     * {@code SplitCompat.install(Context)}, whose home and spelling have moved
     * between Play Core versions, and a pack that cannot be delivered without
     * the call must not stop the app from <em>building</em> against a version
     * that spells it differently. A failure here is expected on a fused module
     * or a device with no Play client, where the base assets are already open.</p>
     */
    private void installSplitsIntoProcess() {
        try {
            Class<?> splitCompat =
                Class.forName("com.google.android.play.core.splitcompat.SplitCompat");
            splitCompat.getMethod("install", Context.class).invoke(null, context);
        } catch (Throwable unavailable) {
            // Fused module, no Play client, or a renamed helper: the payload
            // lookup below is the same either way, and it reports what it finds.
        }
    }

    /** Apply the pack's sealed payload to the prefix and verify the tools. */
    private PackResult apply(Pack pack) {
        // A delivered split's assets are invisible to a plain AssetManager until
        // the split has been installed into this process. Without this, a pack
        // that downloaded perfectly looks like it has no payload.
        installSplitsIntoProcess();
        try {
            SealedPayloadInstaller.Result installed =
                SealedPayloadInstaller.install(context, config, pack.assetPrefix);
            if (installed == null) {
                return PackResult.failed(PackResult.Kind.UNAVAILABLE, pack.id,
                    "the pack's payload is not in this APK");
            }
            if (!installed.ok()) {
                return new PackResult(PackResult.Kind.INSTALL_FAILED, pack.id,
                    "the pack payload did not apply cleanly", 0, installed.problems);
            }

            // Verify what arrived. The payload's own exec manifest is the
            // authority: a build that ships two of the four advertised tools is
            // a smaller pack, not a broken install.
            String abi = installed.abi;
            List<String> delivered = PackCommands.commandsIn(
                SealedPayloadInstaller.readManifestLines(context, abi, pack.assetPrefix, "exec"));
            if (delivered.isEmpty()) {
                return PackResult.failed(PackResult.Kind.INSTALL_FAILED, pack.id,
                    "the pack payload carried no executables");
            }
            List<String> missing = PackCommands.missingFrom(config.toolDirectory, delivered);
            if (!missing.isEmpty()) {
                return new PackResult(PackResult.Kind.INSTALL_FAILED, pack.id,
                    "the payload applied but these commands do not run", 0, missing);
            }
            writeDelivered(pack, delivered);

            List<String> notInThisBuild = new ArrayList<>(pack.requiredCommands);
            notInThisBuild.removeAll(delivered);
            return new PackResult(PackResult.Kind.INSTALLED, pack.id,
                "installed from the store module (" + installed.executables
                    + " executables sealed"
                    + (notInThisBuild.isEmpty() ? "" : "; this build omits " + notInThisBuild)
                    + ")", installed.bytes, null);
        } catch (IOException failure) {
            return PackResult.failed(PackResult.Kind.INSTALL_FAILED, pack.id,
                "could not read the pack payload: " + failure.getMessage());
        }
    }

    /** Where a delivered pack records what it put down. */
    private File deliveredRecord(Pack pack) {
        File report = SealedPayloadInstaller.reportFile(config, pack.assetPrefix);
        if (report == null) return null;
        return new File(report.getParentFile(), "pack-" + pack.id + "-installed.txt");
    }

    private void writeDelivered(Pack pack, List<String> commands) {
        File record = deliveredRecord(pack);
        if (record == null) return;
        try {
            File parent = record.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(record)) {
                for (String command : commands) {
                    out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            // A missing record costs a repeated install, not a failed one.
        }
    }

    /** What a previous install delivered, or an empty list. */
    private List<String> deliveredCommands(Pack pack) {
        File record = deliveredRecord(pack);
        if (record == null || !record.isFile()) return Collections.emptyList();
        List<String> commands = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(record), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line.trim();
                if (!command.isEmpty() && !commands.contains(command)) commands.add(command);
            }
        } catch (IOException unreadable) {
            return Collections.emptyList();
        }
        return commands;
    }

    /**
     * Ask Play for the module and wait for the outcome.
     *
     * @return null when the module is now installed, otherwise the failure the
     *         caller should show.
     */
    private PackResult requestDelivery(Pack pack) {
        SplitInstallManager manager;
        SplitInstallStateUpdatedListener listener = null;
        try {
            manager = SplitInstallManagerFactory.create(context);
            if (manager.getInstalledModules().contains(pack.moduleName)) return null;

            CountDownLatch done = new CountDownLatch(1);
            final int[] finalStatus = new int[] {SplitInstallSessionStatus.PENDING};
            final int[] errorCode = new int[] {0};
            listener = state -> {
                int status = state.status();
                if (status == SplitInstallSessionStatus.DOWNLOADED
                    || status == SplitInstallSessionStatus.INSTALLED) {
                    finalStatus[0] = status;
                    done.countDown();
                } else if (status == SplitInstallSessionStatus.FAILED
                    || status == SplitInstallSessionStatus.CANCELED) {
                    finalStatus[0] = status;
                    errorCode[0] = state.errorCode();
                    done.countDown();
                }
            };
            manager.registerListener(listener);

            SplitInstallRequest request = SplitInstallRequest.newBuilder()
                .addModule(pack.moduleName)
                .build();
            // A request can also fail before any state arrives (no store, a
            // revoked session); the listener then never fires and the wait
            // would run to its timeout. The task's own failure ends it.
            manager.startInstall(request).addOnFailureListener(
                failure -> {
                    errorCode[0] = -1;
                    done.countDown();
                });
            boolean finished = done.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                return PackResult.failed(PackResult.Kind.DELIVERY_FAILED, pack.id,
                    "the store download did not finish in "
                        + (DELIVERY_TIMEOUT_SECONDS / 60) + " minutes");
            }
            if (finalStatus[0] == SplitInstallSessionStatus.DOWNLOADED) {
                // Everything is on the device; the split is usable as soon as the
                // session completes, which Play does at the next app boundary on
                // some devices. Applying the payload needs no restart.
                manager.completeInstall();
            }
            if (finalStatus[0] != SplitInstallSessionStatus.INSTALLED
                && finalStatus[0] != SplitInstallSessionStatus.DOWNLOADED) {
                return PackResult.failed(PackResult.Kind.DELIVERY_FAILED, pack.id,
                    describeDeliveryError(errorCode[0]));
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PackResult.failed(PackResult.Kind.DELIVERY_FAILED, pack.id,
                "the download was interrupted");
        } catch (Throwable noStore) {
            return PackResult.failed(PackResult.Kind.UNAVAILABLE, pack.id,
                "no Play Store on this device (" + noStore.getClass().getSimpleName() + ")");
        } finally {
            if (listener != null) {
                try {
                    SplitInstallManagerFactory.create(context).unregisterListener(listener);
                } catch (Throwable ignored) {
                    // Nothing to unregister if the manager could not be created.
                }
            }
        }
    }

    private boolean moduleInstalled(Pack pack) {
        try {
            return SplitInstallManagerFactory.create(context)
                .getInstalledModules().contains(pack.moduleName);
        } catch (Throwable noStore) {
            return false;
        }
    }

    /**
     * Play's own words for a failed download.
     *
     * <p>The numeric codes are Play's, not ours, and the constant names in the
     * client library have moved between versions; the message therefore quotes
     * the code instead of pretending to know the name. The three states worth
     * naming are handled by the caller.</p>
     */
    private static String describeDeliveryError(int code) {
        if (code == 0) return "the store download failed";
        if (code < 0) return "the store refused the request before it started";
        return "the store download failed (Play code " + code + ")";
    }

    private boolean playStorePresent() {
        try {
            context.getPackageManager().getPackageInfo(PLAY_STORE_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException absent) {
            return false;
        }
    }
}
