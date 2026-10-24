package io.mcyber12.sealed;

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

    /** Wall-clock budget: a full audit must never be able to hang a caller. */
    public static final long MAX_MILLIS = 120_000L;

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
        /** Non-null when the walk could not complete at all. */
        public String failure;

        Report(List<Entry> entries, int scanned, boolean truncated, String nativeLibraryDirectory) {
            this.entries = Collections.unmodifiableList(entries);
            this.scanned = scanned;
            this.truncated = truncated;
            this.nativeLibraryDirectory = nativeLibraryDirectory;
        }

        public int examined() { return entries.size(); }

        /**
         * Binary (ELF) executables that are not backed by the APK — the only
         * thing that genuinely requires the data-directory exec exemption.
         *
         * <p>Scripts are deliberately excluded: they are never {@code execve}-d
         * in this harness. Either the preload translates their interpreter and
         * starts it itself (proven by {@code ExecModelTest}) or
         * {@code HarnessCommandRunner} does the same for JVM callers. They are
         * still counted, via {@link #outsideScripts()}, because a user script
         * run directly (not through the harness) would be denied at API 29+.</p>
         */
        public List<Entry> outsideApk() {
            List<Entry> out = new ArrayList<>();
            for (Entry entry : entries) {
                if (!entry.shippedInApk && entry.kind != ExecutableKind.SCRIPT) out.add(entry);
            }
            return out;
        }

        /** Scripts outside the APK: run through the interpreter layer, never exec'd. */
        public List<Entry> outsideScripts() {
            List<Entry> out = new ArrayList<>();
            for (Entry entry : entries) {
                if (!entry.shippedInApk && entry.kind == ExecutableKind.SCRIPT) out.add(entry);
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
                .append(" binary executables outside it, ").append(outsideScripts().size())
                .append(" scripts outside it (run through the preload)\n");
            text.append("native libraries: ").append(nativeLibraryDirectory).append('\n');
            text.append("scanned paths: ").append(scanned)
                .append(truncated ? " (truncated)" : "").append('\n');
            if (failure != null) text.append("walk failed: ").append(failure).append('\n');
            text.append("verdict: ")
                .append(publishable()
                    ? "publishable at any targetSdk (no binaries outside the APK)"
                    : "targetSdk <= 28 only (" + outsideApk().size()
                        + " binaries require the data-dir exec exemption)")
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
        try {
            return auditBounded(prefix, nativeLibraryDirectory);
        } catch (Throwable problem) {
            // An audit is diagnostics: a hostile or half-installed prefix must
            // never turn it into a crash for the caller (it runs during setup).
            List<Entry> none = new ArrayList<>();
            Report failed = new Report(none, 0, true,
                String.valueOf(nativeLibraryDirectory));
            failed.failure = problem.getClass().getSimpleName() + ": " + problem.getMessage();
            return failed;
        }
    }

    private static Report auditBounded(File prefix, File nativeLibraryDirectory) {
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
        long deadline = System.currentTimeMillis() + MAX_MILLIS;
        while (!pending.isEmpty()) {
            File current = pending.removeFirst();
            if (scanned >= MAX_ENTRIES) { truncated = true; break; }
            if (System.currentTimeMillis() > deadline) { truncated = true; break; }

            File[] children = current.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (++scanned > MAX_ENTRIES) { truncated = true; break; }
                String relative = relativePath(prefixPath, child);
                if (relative == null) continue;
                if (isSkipped(relative)) continue;

                if (Files.isSymbolicLink(child.toPath())) {
                    // Judge a link by what it finally resolves to, not by its
                    // name: a versioned library link (libssl.so ->
                    // libssl.so.3) is dlopen'd and never execve'd, while
                    // bin/bash -> <nativeLibraryDir>/libbash.so is exactly the
                    // sealed case. Dangling links cannot be executed at all.
                    File resolved = resolveLink(child, 0);
                    if (resolved == null) continue;
                    String resolvedPath = resolved.getAbsolutePath();
                    boolean shipped = nativeRoot != null && resolvedPath.startsWith(nativeRoot);
                    if (shipped) {
                        entries.add(new Entry(relative, ExecutableKind.ELF, true, resolvedPath));
                        continue;
                    }
                    if (isLibraryPath(relative)) continue;
                    ExecutableKind kind = ExecutableProbe.kindOf(resolved);
                    if (!ExecutableProbe.requiresExecAllowedPath(kind)) continue;
                    entries.add(new Entry(relative, kind, false, resolvedPath));
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

    /** Follow a chain of symlinks to the regular file it ends at. */
    static File resolveLink(File link, int depth) {
        if (depth > 12 || link == null || !Files.isSymbolicLink(link.toPath())) return link;
        String target = readLink(link);
        if (target == null) return null;
        File next = new File(target);
        if (!next.isAbsolute()) {
            File parent = link.getParentFile();
            next = parent == null ? next : new File(parent, target);
        }
        if (!next.exists() && !Files.isSymbolicLink(next.toPath())) return null;
        return resolveLink(next, depth + 1);
    }

    /** Directories whose contents are loaded with dlopen(), never executed. */
    private static boolean isLibraryPath(String relative) {
        return relative.startsWith("lib/") || relative.startsWith("lib64/")
            || relative.contains("/lib/") || relative.endsWith(".so");
    }

    /** The literal target text of a symlink, or null. Public: diagnostics use it. */
    public static String readLink(File file) {
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
