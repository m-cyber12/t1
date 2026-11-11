package io.mcyber12.sealed;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the data half of a sealed payload into a prefix.
 *
 * <p>This is the piece the reference harness calls {@code PayloadExtractor}:
 * a zip of everything that is only <em>read</em> (shell scripts, shared
 * libraries, {@code /etc}, {@code /share}, certificates), extracted into the
 * app's own storage. Nothing extracted here is ever {@code execve}-d — the
 * executables travel as JNI libraries and are linked in by {@link SealedInstaller}.</p>
 *
 * <p>Three properties matter, and each is a test:</p>
 * <ol>
 *   <li><b>No escape.</b> Absolute names and {@code ..} are rejected, and the
 *       resolved path is checked against the prefix root as well, so a hostile
 *       zip cannot write outside it.</li>
 *   <li><b>Verified while written.</b> When a {@link RuntimeManifest} entry
 *       exists for an entry, its SHA-256 and size are checked against the bytes
 *       as they stream past. No second read, no separate verification pass.</li>
 *   <li><b>Idempotent.</b> Files are overwritten; directories are created as
 *       needed. Re-running an install after an upgrade heals in place.</li>
 * </ol>
 */
public final class PayloadExtractor {

    /** Receives progress; implementations must not throw. */
    public interface Listener {
        void onFile(String path, int index);
    }

    /** What extraction produced. */
    public static final class Result {
        public final int files;
        public final int verified;
        public final long bytes;
        public final List<String> problems;

        Result(int files, int verified, long bytes, List<String> problems) {
            this.files = files;
            this.verified = verified;
            this.bytes = bytes;
            this.problems = Collections.unmodifiableList(problems);
        }

        public boolean ok() { return problems.isEmpty(); }

        public String summary() {
            return "extracted " + files + " files, " + bytes + " bytes, "
                + verified + " verified" + (problems.isEmpty() ? ""
                    : ", problems: " + problems.size());
        }
    }

    private PayloadExtractor() {}

    public static Result extract(InputStream archive, File prefix,
                                 RuntimeManifest manifest, Listener listener) throws IOException {
        List<String> problems = new ArrayList<>();
        if (prefix == null) throw new IOException("no prefix to extract into");
        if (!prefix.isDirectory() && !prefix.mkdirs() && !prefix.isDirectory()) {
            throw new IOException("cannot create prefix: " + prefix);
        }
        RuntimeManifest expected = manifest == null ? RuntimeManifest.empty() : manifest;
        String root = prefix.getAbsolutePath();
        MessageDigest digest = RuntimeManifest.newDigest();
        byte[] buffer = new byte[64 * 1024];
        int files = 0;
        int verified = 0;
        long bytes = 0;

        try (ZipInputStream zip = new ZipInputStream(archive)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = RuntimeManifest.normalize(entry.getName());
                if (name == null) {
                    if (!entry.getName().isEmpty()) {
                        problems.add("unsafe payload path: " + entry.getName());
                    }
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
                digest.reset();
                long written = 0;
                try (FileOutputStream file = new FileOutputStream(output)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        file.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        written += read;
                    }
                }
                files++;
                bytes += written;
                RuntimeManifest.Entry expectedEntry = expected.get(name);
                if (expectedEntry != null) {
                    String actual = RuntimeManifest.hex(digest.digest());
                    if (written != expectedEntry.size) {
                        problems.add("size differs: " + name + " (" + written + " != "
                            + expectedEntry.size + ")");
                    } else if (!actual.equals(expectedEntry.sha256)) {
                        problems.add("digest differs: " + name);
                    } else {
                        verified++;
                    }
                }
                if (listener != null) listener.onFile(name, files);
            }
        }
        if (!expected.isEmpty() && verified != expected.size() && problems.isEmpty()) {
            // Everything present verified, but the payload did not carry every
            // file the manifest promised: that is a truncated build, not a
            // tolerable difference, because a missing stdlib breaks Python long
            // after the install reports success.
            problems.add("manifest lists " + expected.size() + " files, payload carried "
                + verified);
        }
        return new Result(files, verified, bytes, problems);
    }
}
