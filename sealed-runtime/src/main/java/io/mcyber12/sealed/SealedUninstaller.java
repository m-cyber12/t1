package io.mcyber12.sealed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Removes a sealed payload from a prefix — the half everybody forgets.
 *
 * <p>An optional pack that can be installed but not removed is not a feature,
 * it is a leak. Because every payload ships the list of what it put down (a
 * manifest of files with sizes, and a manifest of the executables it linked),
 * removal is exact: those paths and no others. That property is what makes it
 * safe to offer in a UI next to a core runtime that must survive.</p>
 *
 * <p>The opposite of {@link SealedInstaller}: it never guesses, it never deletes
 * a directory, and it reports the bytes it actually reclaimed rather than the
 * bytes it hoped to.</p>
 */
public final class SealedUninstaller {

    /** What removal produced. */
    public static final class Result {
        public final int files;
        public final long bytes;
        public final List<String> problems;

        Result(int files, long bytes, List<String> problems) {
            this.files = files;
            this.bytes = bytes;
            this.problems = Collections.unmodifiableList(problems);
        }

        public boolean ok() { return problems.isEmpty(); }

        public String summary() {
            return "removed " + files + " files, reclaimed " + bytes + " bytes"
                + (problems.isEmpty() ? "" : ", problems: " + problems);
        }
    }

    private SealedUninstaller() {}

    /**
     * Delete exactly what a payload installed.
     *
     * @param execLines {@code path ⇥ library} records, as written by the build
     * @param manifestLines {@code sha256 ⇥ size ⇥ path} records
     */
    public static Result remove(File prefix, List<String> execLines, List<String> manifestLines) {
        List<String> problems = new ArrayList<>();
        if (prefix == null) {
            return new Result(0, 0, Collections.singletonList("no prefix"));
        }
        int files = 0;
        long bytes = 0;

        // Executables are symlinks the installer created; the targets live in the
        // APK and must never be touched.
        for (String raw : execLines == null ? Collections.<String>emptyList() : execLines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String path = RuntimeManifest.normalize(line.split("\\s+")[0]);
            if (path == null) continue;
            File link = new File(prefix, path);
            if (!link.exists() && !Files.isSymbolicLink(link.toPath())) continue;
            try {
                Files.deleteIfExists(link.toPath());
                files++;
            } catch (IOException refused) {
                problems.add("could not delete " + path + ": " + refused.getMessage());
            }
        }

        for (String raw : manifestLines == null ? Collections.<String>emptyList() : manifestLines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] pieces = line.split("\t");
            if (pieces.length != 3) continue;
            long size;
            try {
                size = Long.parseLong(pieces[1].trim());
            } catch (NumberFormatException notANumber) {
                continue;
            }
            String path = RuntimeManifest.normalize(pieces[2]);
            if (path == null) continue;
            File file = new File(prefix, path);
            if (!file.isFile()) continue;
            // A file whose contents are not the ones this payload wrote belongs
            // to something else (another payload, or the user): leave it.
            if (file.length() != size) continue;
            try {
                Files.delete(file.toPath());
                files++;
                bytes += size;
            } catch (IOException refused) {
                problems.add("could not delete " + path + ": " + refused.getMessage());
            }
        }
        return new Result(files, bytes, problems);
    }
}
