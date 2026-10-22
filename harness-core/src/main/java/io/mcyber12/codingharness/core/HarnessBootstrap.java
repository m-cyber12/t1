package io.mcyber12.codingharness.core;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;

import io.mcyber12.codingharness.core.exec.ExecAudit;
import io.mcyber12.codingharness.core.exec.SealedLayout;
import java.util.zip.ZipInputStream;

/**
 * Installs the small Termux bootstrap supplied as an APK asset.
 *
 * <p>This is deliberately the bootstrap layer, not a copy of the complete
 * Termux app. It supplies apt/dpkg, the shell, certificates and the base Unix
 * tools. The package installer then adds the language runtimes needed by a
 * coding harness.</p>
 */
public final class HarnessBootstrap {
    public static final String BOOTSTRAP_VERSION = "2026.02.12-r1";
    private static final String MARKER = ".bootstrap-version";

    private HarnessBootstrap() {}

    /** Directory holding the install marker and staging area (the harness root, NOT a prefix ancestor). */
    private static File harnessRoot(HarnessConfig config) {
        File dpkgRoot = HarnessEnvironment.dpkgRoot(config);
        File parent = dpkgRoot.getParentFile();
        return parent != null ? parent : dpkgRoot;
    }

    public static boolean isInstalled(HarnessConfig config) {
        File marker = new File(harnessRoot(config), MARKER);
        File apt = new File(config.prefix, "bin/apt-get");
        if (!marker.isFile() || !apt.isFile()) return false;
        try (FileInputStream input = new FileInputStream(marker)) {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            String value = new String(bytes, 0, Math.max(0, count), StandardCharsets.UTF_8).trim();
            return BOOTSTRAP_VERSION.equals(value);
        } catch (IOException e) {
            return false;
        }
    }

    /** Install the ABI-specific bootstrap from the APK assets if it is absent. */
    public static void ensureInstalled(Context context, HarnessConfig config) throws IOException {
        // Also provision on the fast path: installations from before the
        // preload library existed must gain it without a reinstall.
        ensureHarnessExecLibrary(context, config);
        if (isInstalled(config)) {
            // Heal the sealed layout too: an app upgrade may add executables,
            // and the prefix must never be left pointing at a stale binary.
            ensureSealedLayout(context, config);
            return;
        }

        String assetName = assetName();
        File root = harnessRoot(config);
        File staging = new File(root, "usr-staging");
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Unable to create bootstrap staging directory: " + staging);
        }

        List<Symlink> symlinks = new ArrayList<>();
        try (InputStream raw = context.getAssets().open(assetName, android.content.res.AssetManager.ACCESS_STREAMING);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("SYMLINKS.txt".equals(name)) {
                    readSymlinks(zip, symlinks);
                    continue;
                }

                File output = safeChild(staging, name);
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        throw new IOException("Unable to create bootstrap directory: " + output);
                    }
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Unable to create bootstrap parent: " + parent);
                }
                try (FileOutputStream file = new FileOutputStream(output)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) file.write(buffer, 0, count);
                }
                if (isExecutableEntry(name)) {
                    try { Os.chmod(output.getAbsolutePath(), 0700); }
                    catch (Exception ignored) { output.setExecutable(true, true); }
                }
            }
        }

        if (symlinks.isEmpty()) throw new IOException("Bootstrap did not contain SYMLINKS.txt");
        for (Symlink symlink : symlinks) {
            File link = safeChild(staging, symlink.destination);
            File parent = link.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Unable to create symlink parent: " + parent);
            }
            link.delete();
            try {
                Os.symlink(symlink.source, link.getAbsolutePath());
            } catch (Exception e) {
                throw new IOException("Unable to create bootstrap symlink: " + link, e);
            }
        }

        File oldPrefix = config.prefix;
        File prefixParent = oldPrefix.getParentFile();
        if (prefixParent != null && !prefixParent.isDirectory() && !prefixParent.mkdirs()
            && !prefixParent.isDirectory()) {
            throw new IOException("Unable to create bootstrap prefix parent: " + prefixParent);
        }
        deleteRecursively(oldPrefix);
        if (!staging.renameTo(oldPrefix)) {
            throw new IOException("Unable to activate extracted bootstrap");
        }
        File marker = new File(root, MARKER);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(BOOTSTRAP_VERSION.getBytes(StandardCharsets.UTF_8));
        }
        relocateOfficialPrefixSymlinks(config);
        ensureHarnessExecLibrary(context, config);
        // Must run after the payload tree exists: sealing replaces executable
        // files with pointers into the APK's native library directory.
        ensureSealedLayout(context, config);
    }

    /** Asset name of the build-generated sealed-exec manifest, when present. */
    public static final String SEALED_MANIFEST_ASSET = "sealed-exec.txt";

    /**
     * Apply the sealed file layout, when this build ships one.
     *
     * <p>A sealed build packages every executable as a JNI library inside the
     * APK and records the prefix path it has to answer to in
     * {@code assets/sealed-exec.txt}. Applying it here keeps the prefix
     * self-consistent ({@code …/usr/bin/git} still exists, still has its
     * absolute path, still runs) while the bytes it runs live in the one
     * location Android allows a modern app to execute from. After applying, an
     * audit is written to {@code exec-audit.txt} so "is anything left that
     * needs the data-dir exec exemption?" is answerable from a device.</p>
     *
     * <p>Returns null for a non-sealed build (no manifest asset), which is the
     * current default: those builds install real packages under the prefix and
     * keep the compatibility target SDK.</p>
     */
    public static SealedLayout.Result ensureSealedLayout(Context context, HarnessConfig config) {
        if (context == null || config == null || config.nativeLibraryDirectory == null) return null;
        List<SealedLayout.Link> links;
        try (InputStream input = context.getAssets().open(SEALED_MANIFEST_ASSET)) {
            links = SealedLayout.parse(readUtf8(input));
        } catch (IOException absent) {
            return null;
        }
        SealedLayout.Result result =
            SealedLayout.apply(config.prefix, config.nativeLibraryDirectory, links);
        ExecAudit.Report report = ExecAudit.audit(config.prefix, config.nativeLibraryDirectory);
        writeText(new File(harnessRoot(config), "exec-audit.txt"),
            result.summary() + "\n" + report.toText());
        return result;
    }

    private static String readUtf8(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeText(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // Diagnostics must never be able to fail an install.
        }
    }

    /** Official Termux data directory every bootstrap/.deb path is built for. */
    private static final String OFFICIAL_DATA_DIR = "/data/data/com.termux";

    /**
     * Rewrites symlinks inside the prefix whose targets are absolute paths
     * into the official Termux data directory so they point at the mirrored
     * location inside this harness instead.
     *
     * <p>Bootstrap SYMLINKS.txt entries and Termux .debs contain absolute
     * symlinks such as share/pacman/keyrings/*.gpg -&gt;
     * /data/data/com.termux/files/usr/share/termux-keyring/*.gpg. Path
     * translation in the preload library cannot help: the KERNEL follows a
     * symlink's target, so when dpkg stats (dereferences) an existing link
     * while unpacking an upgrade, a stock phone answers EACCES for the other
     * app's private directory and dpkg aborts ("failed to stat (dereference)
     * existing symlink ... Permission denied" — seen upgrading
     * termux-keyring). Emulator filesystems return ENOENT for the missing
     * target, which dpkg tolerates, so this only bites on real devices.
     * Relocating the targets keeps the links working with official-Termux
     * semantics. Best effort: never throws, returns the number of links
     * rewritten.</p>
     */
    public static int relocateOfficialPrefixSymlinks(HarnessConfig config) {
        try {
            File mirror = new File(HarnessEnvironment.dpkgRoot(config),
                "data/data/com.termux");
            return relocateSymlinks(config.prefix, OFFICIAL_DATA_DIR, mirror);
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Pure path-rewriting step (unit-testable without a Context). */
    static int relocateSymlinks(File tree, String officialRoot, File relocatedRoot) {
        int rewritten = 0;
        List<File> pending = new ArrayList<>();
        pending.add(tree);
        while (!pending.isEmpty()) {
            File current = pending.remove(pending.size() - 1);
            if (Files.isSymbolicLink(current.toPath())) {
                try {
                    java.nio.file.Path target = Files.readSymbolicLink(current.toPath());
                    String text = target.toString();
                    if (text.equals(officialRoot) || text.startsWith(officialRoot + "/")) {
                        String suffix = text.substring(officialRoot.length());
                        while (suffix.startsWith("/")) suffix = suffix.substring(1);
                        File relocated = suffix.isEmpty()
                            ? relocatedRoot : new File(relocatedRoot, suffix);
                        Files.delete(current.toPath());
                        Files.createSymbolicLink(current.toPath(), relocated.toPath());
                        rewritten++;
                    }
                } catch (Exception ignored) {
                    // A single unreadable link must not block the repair pass.
                }
                continue; // Never descend through a symlink.
            }
            File[] children = current.listFiles();
            if (children != null) {
                for (File child : children) pending.add(child);
            }
        }
        return rewritten;
    }

    /**
     * libharness-exec.so (the execve/shebang relocation preload) ships as a
     * jniLib inside the APK, but modern AGP keeps it compressed there, so
     * the absolute path LD_PRELOAD needs does not exist after install.
     * Extract it next to the bootstrap marker (app-private, writable) the
     * same way the bootstrap itself is provisioned.
     */
    public static void ensureHarnessExecLibrary(Context context, HarnessConfig config) {
        try {
            File destination = new File(harnessRoot(config), "libharness-exec.so");
            String abi = null;
            for (String candidate : Build.SUPPORTED_ABIS) {
                if ("arm64-v8a".equals(candidate) || "x86_64".equals(candidate)) {
                    abi = candidate;
                    break;
                }
            }
            if (abi == null) return;
            extractHarnessExecLibrary(
                new File(context.getPackageCodePath()), abi, destination);
        } catch (Exception ignored) {
            // Best effort: HarnessEnvironment only preloads the library if
            // the file exists, and missing interception surfaces later as
            // maintainer-script "bad interpreter" failures in diagnostics.
        }
    }

    /** Pure-java extraction step (unit-testable without a Context). */
    static void extractHarnessExecLibrary(File apk, String abi, File destination)
            throws IOException {
        String entryName = "lib/" + abi + "/libharness-exec.so";
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("APK has no " + entryName);
            }
            if (destination.isFile() && destination.length() == entry.getSize()) return;
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                && !parent.isDirectory()) {
                throw new IOException("Unable to create directory: " + parent);
            }
            try (InputStream input = zip.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
        try {
            android.system.Os.chmod(destination.getAbsolutePath(), 0700);
        } catch (Exception ignored) {
            destination.setExecutable(true, true);
            destination.setReadable(true, true);
        }
    }

    /**
     * Installs the bundled CA certificate bundle. Termux clients (apt's https
     * method, curl, git) have the official cert store path compiled in, which
     * does not exist in this relocated prefix; without a bundle every TLS
     * handshake fails certificate verification.
     */
    public static void ensureCertificates(Context context, HarnessConfig config) throws IOException {
        File certBundle = new File(config.prefix, "etc/tls/cert.pem");
        if (certBundle.isFile() && certBundle.length() > 1000) return;
        File directory = certBundle.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create certificate directory: " + directory);
        }
        writeAssetFile(context, "cacert.pem", certBundle);
    }

    /**
     * Writes an asset as a REGULAR file. The Termux bootstrap's SYMLINKS.txt
     * creates symlinks at some of these locations (e.g. every entry of
     * etc/apt/trusted.gpg.d points at share/termux-keyring under the official
     * /data/data/com.termux prefix). In this relocated prefix those links
     * dangle: isFile() is false, and FileOutputStream then follows the dead
     * link and fails with ENOENT (or, worse, apt silently finds no usable
     * key and reports NO_PUBKEY). Delete any link/non-regular entry first.
     */
    private static void writeAssetFile(Context context, String asset, File target) throws IOException {
        try {
            if (Files.isSymbolicLink(target.toPath())
                || (target.exists() && !target.isFile())) {
                Files.delete(target.toPath());
            }
        } catch (IOException ignored) {
            // Fall through; the write below surfaces any real problem.
        }
        if (target.isFile() && target.length() > 0) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create directory: " + parent);
        }
        try (InputStream input = context.getAssets().open(asset, android.content.res.AssetManager.ACCESS_STREAMING);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        target.setReadable(true, false);
    }

    /**
     * Installs the Termux repository signing keys from APK assets. The raw
     * bootstrap has an empty etc/apt/trusted.gpg.d (termux-keyring creates
     * its links only at package build time), so without this apt reports
     * "repository is not signed" even after a successful download.
     */
    public static void ensureKeyring(Context context, HarnessConfig config) throws IOException {
        String[] keys;
        try {
            keys = context.getAssets().list("termux-keyring");
        } catch (IOException e) {
            keys = null;
        }
        if (keys == null) return;
        File trusted = new File(config.prefix, "etc/apt/trusted.gpg.d");
        if (!trusted.isDirectory() && !trusted.mkdirs() && !trusted.isDirectory()) {
            throw new IOException("Unable to create trusted keyring directory: " + trusted);
        }
        // Canonical Termux location (share/termux-keyring), mirrored so any
        // tool looking there finds the same keys as trusted.gpg.d.
        File shared = new File(config.prefix, "share/termux-keyring");
        if (!shared.isDirectory() && !shared.mkdirs() && !shared.isDirectory()) {
            throw new IOException("Unable to create shared keyring directory: " + shared);
        }
        for (String key : keys) {
            if (!key.endsWith(".gpg")) continue;
            for (File directory : new File[] {trusted, shared}) {
                writeAssetFile(context, "termux-keyring/" + key, new File(directory, key));
            }
        }
    }

    public static String assetName() throws IOException {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return "bootstrap-aarch64.zip";
            if ("x86_64".equals(abi)) return "bootstrap-x86_64.zip";
        }
        throw new IOException("Coding Harness supports arm64-v8a and x86_64 only");
    }

    private static boolean isExecutableEntry(String name) {
        return name.startsWith("bin/") || name.startsWith("libexec/")
            || name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods/");
    }

    private static void readSymlinks(InputStream input, List<Symlink> symlinks) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] pieces = line.split("←", 2);
            if (pieces.length != 2 || pieces[0].isEmpty() || pieces[1].isEmpty()) {
                throw new IOException("Malformed bootstrap symlink entry");
            }
            symlinks.add(new Symlink(pieces[0], pieces[1]));
        }
    }

    private static File safeChild(File base, String relative) throws IOException {
        File child = new File(base, relative);
        String basePath = base.getCanonicalPath() + File.separator;
        String childPath = child.getCanonicalPath();
        if (!childPath.startsWith(basePath)) throw new IOException("Bootstrap path traversal: " + relative);
        return child;
    }

    private static void deleteRecursively(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            if (!file.delete() && file.exists()) throw new IOException("Unable to delete symlink " + file);
            return;
        }
        if (!file.exists() && !file.isDirectory()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete() && file.exists()) throw new IOException("Unable to delete " + file);
    }

    private static final class Symlink {
        final String source;
        final String destination;
        Symlink(String source, String destination) {
            this.source = source;
            this.destination = destination;
        }
    }
}
