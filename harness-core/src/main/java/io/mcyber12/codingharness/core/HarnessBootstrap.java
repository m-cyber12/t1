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

    public static boolean isInstalled(HarnessConfig config) {
        File marker = new File(config.prefix.getParentFile(), MARKER);
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
        if (isInstalled(config)) return;

        String assetName = assetName();
        File root = config.prefix.getParentFile();
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
        deleteRecursively(oldPrefix);
        if (!staging.renameTo(oldPrefix)) {
            throw new IOException("Unable to activate extracted bootstrap");
        }
        File marker = new File(root, MARKER);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(BOOTSTRAP_VERSION.getBytes(StandardCharsets.UTF_8));
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
