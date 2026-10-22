package io.mcyber12.codingharness.core.exec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Measures how much of an installed prefix can actually be executed on
 * Android 10+ under a modern {@code targetSdkVersion}.
 *
 * <p>The rule the audit encodes (see {@code docs/play-store-plan.md}): on
 * API 29+ apps, the kernel and SELinux refuse {@code execve()} for files that
 * live in the app's own data directory, and allow it only for the app's native
 * library directory — the files that came out of the APK. So an install whose
 * executables live under {@code files/} works only while the app targets
 * API 28 (what Termux does). To also be a Play-publishable app, every
 * executable in the prefix has to be a <em>pointer</em> to a file the APK
 * shipped, and everything left in {@code files/} has to be data.</p>
 *
 * <p>The audit reports exactly that split, so "can this be published?" is a
 * measured number instead of an opinion. It is deliberately dependency-free
 * (no Android APIs) so it runs identically in unit tests, in the bootstrap and
 * on device.</p>
 */
public final class ExecAudit {

    /** Upper bound on visited paths, so a huge prefix cannot stall start-up. */
    public static final int MAX_ENTRIES = 40_000;

    /** Directories whose contents are package data and never executed. */
    private static final String[] SKIP_DIRS = {
        "var/lib/apt/lists", "var/cache", "share/doc", "share/man", "share/info",
        "share/locale", "share/terminfo", "lib/python", "lib/ruby", "node_modules",
        "include", "var/lib/dpkg/info",
    };

    private ExecAudit() {}

    /** One executable entry found in the prefix. */
    public static final class Entry {
        /** Path relative to the prefix, e.g. {@code bin/git}. */
        public final String path;
        public final ExecutableKind kind;
        /** True when the entry resolves into the APK's native library directory. */
        public final boolean shippedInApk;
        /** Resolved target for symlinks, or null. */
        public final String target;

        Entry(String path, ExecutableKind kind, boolean shippedInApk, String target) {
            this.path = path;
            this.kind = kind;
            this.shippedInApk = shippedInApk;
            this.target = target;
        }
    }

    /** Result of one audit pass. */
    public static final class Report {
        public final List<Entry> entries;
        public final int scanned;
        public final boolean truncated;
        public final String nativeLibraryDirectory;

        Report(List<Entry> entries, int scanned, boolean truncated, String nativeLibraryDirectory) {
            this.entries = Collections.unmodifiableList(entries);
            this.scanned = scanned;
            this.truncated = truncated;
            this.nativeLibraryDirectory = nativeLibraryDirectory;
        }

        public int examined() { return entries.size(); }

        /** Entries that must be executed but are not backed by the APK. */
        public List<Entry> outsideApk() {
            List<Entry> out = new ArrayList<>();
            for (Entry entry : entries) {
                if (!entry.shippedInApk) out.add(entry);
            }
            return out;
        }

        public int shippedCount() {
            int shipped = 0;
            for (Entry entry : entries) if (entry.shippedInApk) shipped++;
            return shipped;
        }

        /**
         * True when every executable entry in the prefix resolves into the
         * APK, i.e. nothing left in app data needs {@code execve()}.
         */
        public boolean publishable() { return outsideApk().isEmpty(); }

        /** Compact, human-readable audit; also written to {@code exec-audit.txt}. */
        public String toText() {
            StringBuilder text = new StringBuilder();
            text.append("exec audit: ").append(examined())
                .append(" executable entries, ").append(shippedCount())
                .append(" shipped in the APK, ").append(outsideApk().size())
                .append(" outside it\n");
            text.append("native libraries: ").append(nativeLibraryDirectory).append('\n');
            text.append("scanned paths: ").append(scanned)
                .append(truncated ? " (truncated)" : "").append('\n');
            text.append("verdict: ")
                .append(publishable()
                    ? "publishable at any targetSdk (no executables outside the APK)"
                    : "targetSdk <= 28 only (" + outsideApk().size()
                        + " executables require the data-dir exec exemption)")
                .append('\n');
            List<Entry> outside = outsideApk();
            int shown = Math.min(outside.size(), 40);
            for (int i = 0; i < shown; i++) {
                Entry entry = outside.get(i);
                text.append("  outside: ").append(entry.path)
                    .append(" [").append(entry.kind).append(']');
                if (entry.target != null) text.append(" -> ").append(entry.target);
                text.append('\n');
            }
            if (outside.size() > shown) {
                text.append("  ... ").append(outside.size() - shown).append(" more\n");
            }
            return text.toString();
        }
    }

    /**
     * Walk {@code prefix} and classify every executable entry.
     *
     * <p>An "executable entry" is a symlink (the normal shape of
     * {@code bin/}) or a regular file with any execute bit set. Package data
     * (headers, man pages, apt lists, language module trees) is skipped: it is
     * read, not executed, so it does not constrain packaging.</p>
     */
    public static Report audit(File prefix, File nativeLibraryDirectory) {
        List<Entry> entries = new ArrayList<>();
        if (prefix == null || !prefix.isDirectory()) {
            return new Report(entries, 0, false, String.valueOf(nativeLibraryDirectory));
        }
        String nativeRoot = nativeLibraryDirectory == null
            ? null : nativeLibraryDirectory.getAbsolutePath();
        Path prefixPath = prefix.toPath();
        int scanned = 0;
        boolean truncated = false;

        Deque<File> pending = new ArrayDeque<>();
        pending.add(prefix);
        while (!pending.isEmpty()) {
            File current = pending.removeFirst();
            if (scanned >= MAX_ENTRIES) { truncated = true; break; }
            File[] children = current.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (++scanned > MAX_ENTRIES) { truncated = true; break; }
                String relative = relativePath(prefixPath, child);
                if (relative == null) continue;
                if (isSkipped(relative)) continue;

                if (Files.isSymbolicLink(child.toPath())) {
                    String target = readLink(child);
                    boolean shipped = target != null && nativeRoot != null
                        && target.startsWith(nativeRoot);
                    ExecutableKind kind = target == null ? ExecutableKind.UNREADABLE
                        : (target.contains("/lib/") || target.endsWith(".so"))
                            ? ExecutableKind.ELF
                            : ExecutableKind.DATA;
                    entries.add(new Entry(relative, kind, shipped, target));
                    continue;
                }
                if (child.isDirectory()) {
                    pending.add(child);
                    continue;
                }
                if (!child.isFile() || !child.canExecute()) continue;
                ExecutableKind kind = ExecutableProbe.kindOf(child);
                if (!ExecutableProbe.requiresExecAllowedPath(kind)) continue;
                entries.add(new Entry(relative, kind, false, null));
            }
        }
        return new Report(entries, scanned, truncated, String.valueOf(nativeLibraryDirectory));
    }

    static String readLink(File file) {
        try {
            return Files.readSymbolicLink(file.toPath()).toString();
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static boolean isSkipped(String relative) {
        for (String skip : SKIP_DIRS) {
            if (relative.equals(skip) || relative.startsWith(skip + "/")) return true;
        }
        return false;
    }

    private static String relativePath(Path root, File file) {
        try {
            return root.relativize(file.toPath()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Convenience for callers that only have an {@link java.io.File} tree to inspect. */
    public static boolean isRegularFile(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }
}
