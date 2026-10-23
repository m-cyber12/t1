package io.mcyber12.codingharness.core.exec;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.sealed.SealedInstaller;

/**
 * Android adapter over the sealed runtime kernel.
 *
 * <p>Everything that defines the mechanism — extracting the payload, verifying
 * it against the manifest, recreating data links, pointing executables at the
 * libraries the APK shipped — lives in {@link SealedInstaller}, which has no
 * Android dependency and is unit-tested on the JVM. This class does only the
 * four things that need a {@link Context}: pick the ABI, open the assets, name
 * the native library directory, and write the install report.</p>
 *
 * <p>That split is the point: the main harness consumes the kernel
 * ({@code sealed-runtime}) and writes its own version of this adapter in a few
 * dozen lines. See {@code docs/sealed-runtime-port.md}.</p>
 *
 * <p>Four assets describe the payload, all produced by
 * {@code scripts/build-sealed-payload.py} for the same ABI:</p>
 * <ul>
 *   <li>{@code userland-<abi>.zip} — every file that is only <em>read</em>;</li>
 *   <li>{@code sealed-manifest-<abi>.txt} — sha256 and size per data file;</li>
 *   <li>{@code sealed-exec-<abi>.txt} — prefix paths mapped to JNI libraries;</li>
 *   <li>{@code sealed-symlinks-<abi>.txt} — links whose target is data.</li>
 * </ul>
 */
public final class SealedPayloadInstaller {

    private SealedPayloadInstaller() {}

    /** What an install attempt produced, for the log and for tests. */
    public static final class Result {
        public final String abi;
        public final int files;
        public final int verified;
        public final long bytes;
        public final int links;
        public final int executables;
        public final List<String> problems;

        Result(String abi, int files, int verified, long bytes, int links, int executables,
               List<String> problems) {
            this.abi = abi;
            this.files = files;
            this.verified = verified;
            this.bytes = bytes;
            this.links = links;
            this.executables = executables;
            this.problems = problems;
        }

        static Result from(String abi, SealedInstaller.Result kernel) {
            return new Result(abi, kernel.files, kernel.verified, kernel.bytes, kernel.links,
                kernel.executables + kernel.alreadySealed, kernel.problems);
        }

        public boolean ok() { return problems.isEmpty() && executables > 0; }

        public String summary() {
            return "sealed payload (" + abi + "): " + files + " data files, " + links
                + " data links, " + executables + " executables from the APK, "
                + verified + " verified"
                + (problems.isEmpty() ? "" : ", problems: " + problems);
        }
    }

    /** Asset names are per ABI, because the bytes are per ABI. */
    public static String userlandAsset(String abi) { return "userland-" + abi + ".zip"; }
    /**
     * Asset names for a prefixed payload (an on-demand pack): the same files,
     * named so a pack and the base payload can live in one AssetManager.
     */
    public static String userlandAsset(String abi, String prefix) {
        return prefix + "userland-" + abi + ".zip";
    }
    /** Short identity of the payload, written by the build script. */
    public static String idAsset(String abi) { return "sealed-payload-id-" + abi + ".txt"; }

    private static String manifestAsset(String abi, String prefix) {
        return prefix + "sealed-manifest-" + abi + ".txt";
    }

    private static String execAsset(String abi, String prefix) {
        return prefix + "sealed-exec-" + abi + ".txt";
    }

    private static String linksAsset(String abi, String prefix) {
        return prefix + "sealed-symlinks-" + abi + ".txt";
    }
    public static String manifestAsset(String abi) { return "sealed-manifest-" + abi + ".txt"; }
    public static String execAsset(String abi) { return "sealed-exec-" + abi + ".txt"; }
    public static String linksAsset(String abi) { return "sealed-symlinks-" + abi + ".txt"; }

    /** True when this APK carries a sealed payload for {@code abi}. */
    public static boolean isAvailable(Context context, String abi) {
        return isAvailable(context, abi, "");
    }

    /** True when the payload named by {@code prefix} is present for {@code abi}. */
    public static boolean isAvailable(Context context, String abi, String prefix) {
        try {
            for (String name : context.getAssets().list("")) {
                if (userlandAsset(abi, prefix).equals(name)) return true;
            }
        } catch (IOException ignored) {
            // An unreadable asset directory is simply "not available".
        }
        return false;
    }

    /** Pick the sealed payload matching the device, or null when there is none. */
    public static String pickAbi(Context context) {
        return pickAbi(context, "");
    }

    /** Pick the prefixed payload matching the device, or null. */
    public static String pickAbi(Context context, String prefix) {
        for (String candidate : android.os.Build.SUPPORTED_ABIS) {
            if (!"arm64-v8a".equals(candidate) && !"x86_64".equals(candidate)) continue;
            if (isAvailable(context, candidate, prefix)) return candidate;
        }
        return null;
    }

    /** Read one of a payload's manifests as text lines; empty when absent. */
    public static List<String> readManifestLines(Context context, String abi, String prefix,
                                                 String which) {
        String name = "manifest".equals(which) ? manifestAsset(abi, prefix)
            : "exec".equals(which) ? execAsset(abi, prefix)
            : linksAsset(abi, prefix);
        try {
            return readLines(context.getAssets(), name);
        } catch (IOException absent) {
            return new ArrayList<>();
        }
    }

    /**
     * The payload's identity, or null when this APK has no payload (or an old
     * one without an identity asset).
     */
    public static String payloadIdentity(Context context, String abi) {
        try (InputStream input = context.getAssets().open(idAsset(abi))) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[256];
            int count;
            while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
            String value = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException absent) {
            return null;
        }
    }

    /**
     * Extract the payload into {@code config.prefix} and seal it.
     *
     * @return null when this APK has no payload for the device (the apt-based
     *         build), otherwise the install result.
     */
    public static Result install(Context context, HarnessConfig config) throws IOException {
        return install(context, config, "");
    }

    /**
     * Install the payload named by {@code prefix} — {@code ""} for the base
     * userland, {@code "pack-buildtools-"} for an on-demand pack.
     */
    public static Result install(Context context, HarnessConfig config, String prefix)
            throws IOException {
        String abi = SealedPayloadInstaller.pickAbi(context, prefix);
        if (abi == null) return null;
        if (config.nativeLibraryDirectory == null) {
            throw new IOException("sealed payload needs the native library directory");
        }
        AssetManager assets = context.getAssets();

        SealedInstaller.Request request = new SealedInstaller.Request()
            .prefix(config.prefix)
            .nativeLibraryDirectory(config.nativeLibraryDirectory)
            .execManifest(readLines(assets, execAsset(abi, prefix)))
            .linkManifest(readLines(assets, linksAsset(abi, prefix)));

        try (InputStream payload =
                 assets.open(userlandAsset(abi, prefix), AssetManager.ACCESS_STREAMING);
             InputStream manifest = openOptional(assets, manifestAsset(abi, prefix))) {
            request.payload(payload).manifest(manifest);
            SealedInstaller.Result kernel = SealedInstaller.install(request);
            Result result = Result.from(abi, kernel);
            writeReport(config, result, prefix);
            return result;
        }
    }

    private static InputStream openOptional(AssetManager assets, String name) {
        try {
            return assets.open(name, AssetManager.ACCESS_STREAMING);
        } catch (IOException absent) {
            // An older payload without a manifest still installs; it simply has
            // nothing to verify, which the report says (0 verified).
            return null;
        }
    }

    private static List<String> readLines(AssetManager assets, String name) throws IOException {
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(assets.open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    /**
     * Where a payload's own record goes.
     *
     * <p>A pack writes its own file, so installing one never overwrites the
     * record of what the core userland did. Public because the pack installer
     * writes its marker in the same directory, and two different ideas of
     * "the harness root" is how diagnostics end up in the wrong place.</p>
     */
    public static File reportFile(HarnessConfig config, String prefix) {
        File root = config.rootfs != null ? config.rootfs.getParentFile() : config.prefix;
        if (root == null) return null;
        String name = prefix == null || prefix.isEmpty()
            ? "sealed-payload.txt"
            : "sealed-payload-" + prefix.replaceAll("-$", "") + ".txt";
        return new File(root, name);
    }

    private static void writeReport(HarnessConfig config, Result result, String prefix) {
        File report = reportFile(config, prefix);
        if (report == null) return;
        try (FileOutputStream output = new FileOutputStream(report)) {
            output.write((result.summary() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Diagnostics must never fail an install.
        }
    }
}
