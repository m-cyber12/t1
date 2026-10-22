package io.mcyber12.codingharness.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures where the harness's storage actually goes, so size is managed
 * from data instead of guesses.
 *
 * <p>Package sizes come from dpkg's own status database
 * ({@code var/lib/dpkg/status}: {@code Installed-Size} per package, in
 * KiB, maintained by dpkg itself). Filesystem figures (total prefix,
 * apt download cache) are walked directly. The summary attributes bytes
 * to the install tiers defined in {@link HarnessPackageInstaller} and
 * names the largest individual packages; it is shown in the TOOLS
 * dialog, written into setup results, and asserted against budget in
 * the emulator E2E.</p>
 */
public final class HarnessSizeReport {

    private HarnessSizeReport() {}

    /** Package name -> installed size in bytes, in dpkg status order. */
    static Map<String, Long> parseInstalledSizes(String statusText) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        if (statusText == null) return sizes;
        String currentPackage = null;
        for (String line : statusText.split("\n")) {
            if (line.startsWith("Package: ")) {
                currentPackage = line.substring("Package: ".length()).trim();
            } else if (line.startsWith("Installed-Size: ") && currentPackage != null) {
                try {
                    sizes.put(currentPackage,
                        Long.parseLong(line.substring("Installed-Size: ".length()).trim()) * 1024L);
                } catch (NumberFormatException ignored) {
                    // A malformed stanza must not break the whole report.
                }
                currentPackage = null;
            } else if (line.isEmpty()) {
                currentPackage = null;
            }
        }
        return sizes;
    }

    static Map<String, Long> readInstalledSizes(HarnessConfig config) {
        File status = new File(config.prefix, "var/lib/dpkg/status");
        try {
            String text = new String(java.nio.file.Files.readAllBytes(status.toPath()),
                StandardCharsets.UTF_8);
            return parseInstalledSizes(text);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    /** One-line total used in installer result messages. */
    public static String totalInstalledSize(HarnessConfig config) {
        long total = 0L;
        for (long bytes : readInstalledSizes(config).values()) total += bytes;
        return "packages total " + formatBytes(total);
    }

    /**
     * Full human-readable breakdown: total, per-tier attribution,
     * largest packages, and the non-package consumers (apt cache,
     * whole-prefix filesystem footprint).
     */
    public static String summarize(HarnessConfig config) {
        Map<String, Long> sizes = readInstalledSizes(config);
        long total = 0L;
        for (long bytes : sizes.values()) total += bytes;

        StringBuilder out = new StringBuilder();
        out.append("Storage (dpkg Installed-Size)\n");
        out.append("all packages: ").append(formatBytes(total))
            .append(" (").append(sizes.size()).append(" packages)\n");
        long tierBytes = appendTier(out, "core (default)", HarnessPackageInstaller.CORE_PACKAGES, sizes);
        tierBytes += appendTier(out, "build tools (on demand)",
            HarnessPackageInstaller.BUILD_TOOL_PACKAGES, sizes);
        tierBytes += appendTier(out, "extra languages (on demand)",
            HarnessPackageInstaller.EXTRA_LANGUAGE_PACKAGES, sizes);
        out.append("base system + shared libraries: ")
            .append(formatBytes(Math.max(0L, total - tierBytes))).append('\n');

        List<Map.Entry<String, Long>> largest = new ArrayList<>(sizes.entrySet());
        largest.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        out.append("largest packages:");
        int shown = 0;
        for (Map.Entry<String, Long> entry : largest) {
            if (shown++ >= 6) break;
            out.append("\n  ").append(entry.getKey()).append(' ')
                .append(formatBytes(entry.getValue()));
        }
        long cache = directoryBytes(new File(config.prefix, "var/cache/apt/archives"));
        long onDisk = directoryBytes(config.prefix);
        out.append("\napt download cache: ").append(formatBytes(cache));
        out.append("\nprefix on disk: ").append(formatBytes(onDisk));
        // dpkg only counts files it installed; the bootstrap-era base
        // system, apt state and any stragglers live on disk outside that
        // database. Attribute the gap to top-level directories so the
        // next optimization is a measurement, not a guess.
        out.append("\nnot tracked by dpkg: ")
            .append(formatBytes(Math.max(0L, onDisk - total)));
        out.append("\nlargest directories:");
        for (Map.Entry<String, Long> entry : topDirectories(config.prefix, 6).entrySet()) {
            out.append("\n  ").append(entry.getKey()).append(' ')
                .append(formatBytes(entry.getValue()));
        }
        return out.toString();
    }

    /** Byte totals of the prefix's top-level directories, largest first. */
    static Map<String, Long> topDirectories(File prefix, int limit) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        File[] children = prefix.listFiles();
        if (children == null) return sizes;
        for (File child : children) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue;
            long bytes = child.isDirectory() ? directoryBytes(child) : child.length();
            sizes.put(child.getName(), bytes);
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(sizes.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : sorted) {
            if (result.size() >= limit) break;
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static long appendTier(StringBuilder out, String label,
                                   List<String> packages, Map<String, Long> sizes) {
        long tierTotal = 0L;
        List<String> installed = new ArrayList<>();
        for (String name : packages) {
            Long bytes = sizes.get(name);
            if (bytes != null) {
                tierTotal += bytes;
                installed.add(name);
            }
        }
        out.append(label).append(": ").append(formatBytes(tierTotal));
        if (installed.size() < packages.size()) {
            out.append(" (").append(installed.size()).append('/')
                .append(packages.size()).append(" installed)");
        }
        out.append('\n');
        return tierTotal;
    }

    /** Recursive byte total of regular files below a directory (links not followed). */
    static long directoryBytes(File directory) {
        if (directory == null || !directory.isDirectory()) return 0L;
        long total = 0L;
        List<File> pending = new ArrayList<>();
        pending.add(directory);
        while (!pending.isEmpty()) {
            File current = pending.remove(pending.size() - 1);
            if (java.nio.file.Files.isSymbolicLink(current.toPath())) continue;
            File[] children = current.listFiles();
            if (children == null) {
                if (current.isFile()) total += current.length();
                continue;
            }
            for (File child : children) {
                if (!java.nio.file.Files.isSymbolicLink(child.toPath()) && child.isDirectory()) {
                    pending.add(child);
                } else if (child.isFile()) {
                    total += child.length();
                }
            }
        }
        return total;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024.0) return String.format(java.util.Locale.US, "%.0f KiB", kib);
        double mib = kib / 1024.0;
        if (mib < 1024.0) return String.format(java.util.Locale.US, "%.1f MiB", mib);
        return String.format(java.util.Locale.US, "%.2f GiB", mib / 1024.0);
    }
}
