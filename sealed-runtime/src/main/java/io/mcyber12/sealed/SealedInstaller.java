package io.mcyber12.sealed;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Applies a sealed payload to a prefix: extract the data, recreate the links,
 * then point every executable at the library the APK shipped.
 *
 * <p>This is the whole mechanism of a publishable harness, in one class, with no
 * Android API in it:</p>
 *
 * <pre>
 *   assets/userland-&lt;abi&gt;.zip        → data files under the prefix
 *   assets/sealed-manifest-&lt;abi&gt;.txt → sha256 + size per data file (verified while written)
 *   assets/sealed-symlinks-&lt;abi&gt;.txt → links whose target is data (versioned .so, scripts)
 *   assets/sealed-exec-&lt;abi&gt;.txt     → prefix path → lib&lt;name&gt;.so in the APK
 * </pre>
 *
 * <p>The result is a normal Termux-shaped prefix in which nothing that the
 * kernel executes came from app storage: every executable is a symlink into the
 * native library directory, which is the one place an app targeting API 29+ may
 * {@code execve()} from. Everything else is data, which is unrestricted.</p>
 *
 * <p>It lives in its own Gradle module on purpose. The main harness
 * ({@code OpenCode-app}) needs this logic with no Android dependencies so it can
 * be lifted, tested on the JVM and translated to TypeScript; the Android-only
 * half (picking the ABI, opening assets, where the prefix lives) stays in the
 * app as a thin adapter.</p>
 */
public final class SealedInstaller {

    /** Everything an install needs, as plain values. */
    public static final class Request {
        public File prefix;
        public File nativeLibraryDirectory;
        /** Zip of read-only files; consumed and closed. */
        public InputStream payload;
        /** {@code sha256<TAB>size<TAB>path} lines; may be null. */
        public InputStream manifest;
        /** {@code path<TAB>library} lines. */
        public List<String> execManifest;
        /** {@code path<TAB>target} lines. */
        public List<String> linkManifest;
        public PayloadExtractor.Listener progress;

        public Request prefix(File value) { this.prefix = value; return this; }
        public Request nativeLibraryDirectory(File value) {
            this.nativeLibraryDirectory = value; return this;
        }
        public Request payload(InputStream value) { this.payload = value; return this; }
        public Request manifest(InputStream value) { this.manifest = value; return this; }
        public Request execManifest(List<String> value) { this.execManifest = value; return this; }
        public Request linkManifest(List<String> value) { this.linkManifest = value; return this; }
        public Request progress(PayloadExtractor.Listener value) { this.progress = value; return this; }
    }

    /** What an install produced, for the log, the audit and the tests. */
    public static final class Result {
        public final int files;
        public final int verified;
        public final long bytes;
        public final int links;
        public final int executables;
        public final int alreadySealed;
        public final List<String> problems;

        Result(int files, int verified, long bytes, int links, int executables,
               int alreadySealed, List<String> problems) {
            this.files = files;
            this.verified = verified;
            this.bytes = bytes;
            this.links = links;
            this.executables = executables;
            this.alreadySealed = alreadySealed;
            this.problems = Collections.unmodifiableList(problems);
        }

        /** True when the prefix is usable: sealed, verified and problem-free. */
        public boolean ok() {
            return problems.isEmpty() && executables + alreadySealed > 0;
        }

        public String summary() {
            return "sealed payload: " + files + " data files (" + bytes + " bytes, "
                + verified + " verified), " + links + " data links, "
                + (executables + alreadySealed) + " executables from the APK"
                + (problems.isEmpty() ? "" : ", problems: " + problems);
        }
    }

    private SealedInstaller() {}

    /**
     * Install a payload.
     *
     * @throws IOException when the prefix or the archives cannot be read at all;
     *         per-file failures are collected in {@link Result#problems} instead,
     *         because a single unreadable file should not discard a good install.
     */
    public static Result install(Request request) throws IOException {
        if (request == null || request.prefix == null) throw new IOException("no request");
        List<String> problems = new ArrayList<>();

        RuntimeManifest manifest = RuntimeManifest.read(request.manifest);
        PayloadExtractor.Result extracted = PayloadExtractor.extract(
            request.payload, request.prefix, manifest, request.progress);
        problems.addAll(extracted.problems);

        int links = 0;
        for (String[] link : parseLinks(request.linkManifest, problems)) {
            try {
                createLink(request.prefix, link[0], link[1]);
                links++;
            } catch (Exception failed) {
                problems.add("link " + link[0] + ": " + failed.getMessage());
            }
        }

        SealedLayout.Result sealed = SealedLayout.apply(request.prefix,
            request.nativeLibraryDirectory, SealedLayout.parse(join(request.execManifest)));
        problems.addAll(sealed.errors);
        problems.addAll(sealed.missingTargets);

        return new Result(extracted.files, extracted.verified, extracted.bytes, links,
            sealed.linked, sealed.alreadyCorrect, problems);
    }

    /** {@code path<TAB>target} lines, both prefix-relative and both safe. */
    static List<String[]> parseLinks(List<String> lines, List<String> problems) {
        List<String[]> links = new ArrayList<>();
        if (lines == null) return links;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] pieces = line.split("\\s+");
            if (pieces.length != 2) {
                problems.add("bad link line: " + line);
                continue;
            }
            String path = RuntimeManifest.normalize(pieces[0]);
            String target = RuntimeManifest.normalize(pieces[1]);
            if (path == null || target == null) {
                problems.add("unsafe link line: " + line);
                continue;
            }
            links.add(new String[] {path, target});
        }
        return links;
    }

    private static String join(List<String> lines) {
        if (lines == null) return "";
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }

    /** Create a data link; targets are prefix-relative by construction. */
    static void createLink(File prefix, String path, String target) throws IOException {
        File link = new File(prefix, path);
        File parent = link.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        java.nio.file.Files.deleteIfExists(link.toPath());
        java.nio.file.Files.createSymbolicLink(link.toPath(), new File(prefix, target).toPath());
    }
}
