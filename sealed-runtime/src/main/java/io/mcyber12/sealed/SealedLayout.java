package io.mcyber12.sealed;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the prefix into a <em>sealed</em> userland: every executable entry
 * becomes a symlink to a file the APK shipped, so nothing under the app's data
 * directory ever needs {@code execve()}.
 *
 * <p>Why symlinks and not copies: the kernel resolves a symlink before it
 * checks permissions, so {@code …/usr/bin/git} keeps its absolute path (which
 * apt, dpkg, configure scripts and {@code #!} lines all hard-code) while the
 * inode that actually runs lives in the app's native library directory — the
 * one location Android allows apps targeting API 29+ to execute. Copies would
 * be a second cost in storage and would drift from the APK.</p>
 *
 * <p>The mapping is produced at build time (one line per executable, because
 * only the build knows which ELF became which {@code lib*.so}) and applied
 * here at first run. It is idempotent: re-running rewrites only what is
 * missing or wrong, so an app upgrade that adds executables heals in place.</p>
 *
 * <p>Manifest format, tab-separated, {@code #} comments, prefix-relative
 * source path first:</p>
 * <pre>
 * bin/bash	libbash.so
 * bin/git	libgit.so
 * </pre>
 */
public final class SealedLayout {

    private SealedLayout() {}

    /** One manifest line. */
    public static final class Link {
        public final String path;
        public final String library;

        public Link(String path, String library) {
            this.path = path;
            this.library = library;
        }
    }

    /** Outcome of {@link #apply}. */
    public static final class Result {
        public final int linked;
        public final int alreadyCorrect;
        public final List<String> missingTargets;
        public final List<String> errors;

        Result(int linked, int alreadyCorrect, List<String> missingTargets, List<String> errors) {
            this.linked = linked;
            this.alreadyCorrect = alreadyCorrect;
            this.missingTargets = Collections.unmodifiableList(missingTargets);
            this.errors = Collections.unmodifiableList(errors);
        }

        public boolean ok() { return missingTargets.isEmpty() && errors.isEmpty(); }

        public String summary() {
            return "sealed layout: " + linked + " linked, " + alreadyCorrect
                + " already correct, " + missingTargets.size() + " missing targets, "
                + errors.size() + " errors";
        }
    }

    /** Parse a manifest; blank lines and {@code #} comments are ignored. */
    public static List<Link> parse(String manifest) {
        List<Link> links = new ArrayList<>();
        if (manifest == null) return links;
        for (String raw : manifest.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('\t');
            if (separator < 0) separator = line.lastIndexOf(' ');
            if (separator <= 0 || separator == line.length() - 1) continue;
            String path = line.substring(0, separator).trim();
            String library = line.substring(separator + 1).trim();
            if (path.isEmpty() || library.isEmpty()) continue;
            // Only relative paths: a manifest may not escape the prefix.
            while (path.startsWith("./")) path = path.substring(2);
            if (path.startsWith("/") || path.contains("..")) continue;
            links.add(new Link(path, library));
        }
        return links;
    }

    /**
     * Apply {@code links} to {@code prefix}.
     *
     * <p>A link whose library is absent from {@code nativeLibraryDirectory} is
     * reported, not invented: that is a packaging bug (the APK lost a file),
     * and silently leaving the old binary in place would keep the install
     * working only on pre-API-29 semantics.</p>
     */
    public static Result apply(File prefix, File nativeLibraryDirectory, List<Link> links) {
        List<String> missing = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int linked = 0;
        int correct = 0;
        if (prefix == null || nativeLibraryDirectory == null) {
            return new Result(0, 0, missing, Collections.singletonList("prefix or lib dir missing"));
        }
        for (Link link : links) {
            File library = new File(nativeLibraryDirectory, link.library);
            if (!library.isFile()) {
                missing.add(link.library + " (for " + link.path + ")");
                continue;
            }
            File destination = new File(prefix, link.path);
            String target = library.getAbsolutePath();
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                errors.add("cannot create " + parent);
                continue;
            }
            Path destinationPath = destination.toPath();
            if (Files.isSymbolicLink(destinationPath)) {
                String existing = ExecAudit.readLink(destination);
                if (target.equals(existing)) { correct++; continue; }
            } else if (destination.isDirectory()) {
                errors.add("refusing to replace directory " + link.path);
                continue;
            }
            try {
                Files.deleteIfExists(destinationPath);
                Files.createSymbolicLink(destinationPath, library.toPath());
                linked++;
            } catch (IOException | UnsupportedOperationException e) {
                errors.add(link.path + ": " + e.getMessage());
            }
        }
        return new Result(linked, correct, missing, errors);
    }

    /** Read a manifest from a file, or null when absent. */
    public static List<Link> readManifest(File manifest) {
        if (manifest == null || !manifest.isFile()) return null;
        try {
            byte[] bytes = Files.readAllBytes(manifest.toPath());
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** Map view, handy for the build-time generator and diagnostics. */
    public static Map<String, String> toMap(List<Link> links) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Link link : links) map.put(link.path, link.library);
        return map;
    }
}
