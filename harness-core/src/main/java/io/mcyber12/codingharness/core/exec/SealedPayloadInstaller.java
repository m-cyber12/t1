package io.mcyber12.codingharness.core.exec;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import io.mcyber12.codingharness.core.HarnessConfig;

/**
 * Installs the sealed userland that ships inside the APK.
 *
 * <p>Three assets describe it, all produced by
 * {@code scripts/build-sealed-payload.py} from the Termux bootstrap of the same
 * ABI:</p>
 * <ul>
 *   <li>{@code userland-<abi>.zip} — every file that is only <em>read</em>
 *       (shell scripts, shared libraries, {@code /etc}, {@code /share}, keys);</li>
 *   <li>{@code sealed-exec-<abi>.txt} — paths that must be executable, each
 *       mapped to the JNI library that carries the bytes;</li>
 *   <li>{@code sealed-symlinks-<abi>.txt} — links whose target is data
 *       (versioned libraries, script aliases).</li>
 * </ul>
 *
 * <p>The result is a normal Termux-shaped prefix ({@code …/usr/bin/bash} etc.)
 * in which nothing needs Android's data-directory exec exemption: the
 * executables are symlinks into the app's native library directory, which is
 * the one place an app targeting API 29+ may {@code execve()} from.</p>
 *
 * <p>Installation is idempotent and safe to repeat on upgrade: files are
 * overwritten, links are re-pointed, and nothing outside the prefix is
 * touched.</p>
 */
public final class SealedPayloadInstaller {

    private SealedPayloadInstaller() {}

    /** What an install attempt produced, for the log and for tests. */
    public static final class Result {
        public final String abi;
        public final int files;
        public final int links;
        public final int executables;
        public final List<String> problems;

        Result(String abi, int files, int links, int executables, List<String> problems) {
            this.abi = abi;
            this.files = files;
            this.links = links;
            this.executables = executables;
            this.problems = Collections.unmodifiableList(problems);
        }

        public boolean ok() { return problems.isEmpty() && executables > 0; }

        public String summary() {
            return "sealed payload (" + abi + "): " + files + " data files, " + links
                + " data links, " + executables + " executables from the APK"
                + (problems.isEmpty() ? "" : ", problems: " + problems);
        }
    }

    /** Asset names are per ABI, because the bytes are per ABI. */
    public static String userlandAsset(String abi) { return "userland-" + abi + ".zip"; }
    public static String execAsset(String abi) { return "sealed-exec-" + abi + ".txt"; }
    public static String linksAsset(String abi) { return "sealed-symlinks-" + abi + ".txt"; }

    /** True when this APK carries a sealed payload for {@code abi}. */
    public static boolean isAvailable(Context context, String abi) {
        try {
            String[] names = context.getAssets().list("");
            for (String name : names) {
                if (userlandAsset(abi).equals(name)) return true;
            }
        } catch (IOException ignored) {
            // An unreadable asset directory is simply "not available".
        }
        return false;
    }

    /** Pick the sealed payload matching the device, or null when there is none. */
    public static String pickAbi(Context context) {
        for (String candidate : android.os.Build.SUPPORTED_ABIS) {
            if (!"arm64-v8a".equals(candidate) && !"x86_64".equals(candidate)) continue;
            if (isAvailable(context, candidate)) return candidate;
        }
        return null;
    }

    /**
     * Extract the payload into {@code config.prefix} and seal it.
     *
     * @return null when this APK has no payload for the device (the apt-based
     *         build), otherwise the install result.
     */
    public static Result install(Context context, HarnessConfig config) throws IOException {
        String abi = pickAbi(context);
        if (abi == null) return null;
        if (config.nativeLibraryDirectory == null) {
            throw new IOException("sealed payload needs the native library directory");
        }
        List<String> problems = new ArrayList<>();
        AssetManager assets = context.getAssets();

        File prefix = config.prefix;
        if (!prefix.isDirectory() && !prefix.mkdirs() && !prefix.isDirectory()) {
            throw new IOException("cannot create prefix: " + prefix);
        }

        int files = extract(assets.open(userlandAsset(abi), AssetManager.ACCESS_STREAMING),
            prefix, problems);

        List<SealedLayout.Link> executableLinks = new ArrayList<>();
        List<String> execLines = readLines(assets.open(execAsset(abi)));
        for (String line : execLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] pieces = trimmed.split("\\s+");
            if (pieces.length != 2) { problems.add("bad exec line: " + trimmed); continue; }
            executableLinks.add(new SealedLayout.Link(pieces[0], pieces[1]));
        }

        int links = 0;
        for (String line : readLines(assets.open(linksAsset(abi)))) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] pieces = trimmed.split("\\s+");
            if (pieces.length != 2) { problems.add("bad link line: " + trimmed); continue; }
            try {
                createLink(prefix, pieces[0], pieces[1]);
                links++;
            } catch (Exception e) {
                problems.add("link " + pieces[0] + ": " + e.getMessage());
            }
        }

        SealedLayout.Result sealed =
            SealedLayout.apply(prefix, config.nativeLibraryDirectory, executableLinks);
        problems.addAll(sealed.errors);
        problems.addAll(sealed.missingTargets);

        Result result = new Result(abi, files, links, sealed.linked + sealed.alreadyCorrect, problems);
        writeReport(config, result);
        return result;
    }

    private static void createLink(File prefix, String path, String target) throws Exception {
        File link = new File(prefix, path);
        File parent = link.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        Files.deleteIfExists(link.toPath());
        // Targets are prefix-relative by construction (the build script writes
        // them that way), so a link can never point out of the userland.
        Os.symlink(new File(prefix, target).getAbsolutePath(), link.getAbsolutePath());
    }

    /** Extract a prefix-relative zip, rejecting any entry that escapes it. */
    static int extract(InputStream raw, File prefix, List<String> problems) throws IOException {
        int count = 0;
        String root = prefix.getAbsolutePath();
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            byte[] buffer = new byte[64 * 1024];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.isEmpty()) continue;
                if (name.startsWith("/") || name.contains("..")) {
                    problems.add("unsafe payload path: " + name);
                    continue;
                }
                File output = new File(prefix, name);
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        problems.add("cannot create " + name);
                    }
                    continue;
                }
                if (!output.getAbsolutePath().startsWith(root + File.separator)) {
                    problems.add("payload path escapes the prefix: " + name);
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                    && !parent.isDirectory()) {
                    problems.add("cannot create parent of " + name);
                    continue;
                }
                try (FileOutputStream file = new FileOutputStream(output)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) file.write(buffer, 0, read);
                }
                count++;
            }
        }
        return count;
    }

    private static List<String> readLines(InputStream input) throws IOException {
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static void writeReport(HarnessConfig config, Result result) {
        File root = config.rootfs != null ? config.rootfs.getParentFile() : config.prefix;
        if (root == null) return;
        try (FileOutputStream output =
                 new FileOutputStream(new File(root, "sealed-payload.txt"))) {
            output.write((result.summary() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Diagnostics must never fail an install.
        }
    }
}
