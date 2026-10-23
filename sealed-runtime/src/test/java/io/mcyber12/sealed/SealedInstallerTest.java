package io.mcyber12.sealed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;

/**
 * The contract the main harness depends on: a payload installs, is verified
 * while it is written, and ends with every executable pointing into the APK.
 *
 * <p>These run on the JVM with no Android SDK, which is the whole reason the
 * kernel is a separate module — a port that edits this logic gets a red test in
 * two seconds instead of a device run in thirty minutes.</p>
 */
public class SealedInstallerTest {

    private static File directory() throws IOException {
        File root = Files.createTempDirectory("sealed-runtime").toFile();
        root.deleteOnExit();
        return root;
    }

    private static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A prefix tree with two data files, a library and one executable. */
    private static File payloadZip(File root) throws IOException {
        File zip = new File(root, "payload.zip");
        try (java.util.zip.ZipOutputStream out =
                 new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("share/data.txt"));
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("lib/libz.so.1"));
            out.write("library".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    private static RuntimeManifest manifestFor(File zip) throws IOException {
        StringBuilder text = new StringBuilder();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
            Files.newInputStream(zip.toPath()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] content = in.readAllBytes();
                text.append(RuntimeManifest.digest(content)).append('\t')
                    .append(content.length).append('\t').append(entry.getName()).append('\n');
            }
        }
        return RuntimeManifest.parse(text.toString());
    }

    private static SealedInstaller.Request request(File prefix, File nativeDir, File zip,
                                                   RuntimeManifest manifest) throws IOException {
        return new SealedInstaller.Request()
            .prefix(prefix)
            .nativeLibraryDirectory(nativeDir)
            .payload(Files.newInputStream(zip.toPath()))
            .manifest(new java.io.ByteArrayInputStream(
                manifestText(manifest).getBytes(StandardCharsets.UTF_8)))
            .execManifest(Collections.singletonList("bin/bash\tlibbash.so"))
            .linkManifest(Collections.singletonList("lib/libz.so\tlibz.so.1"));
    }

    private static String manifestText(RuntimeManifest manifest) {
        StringBuilder text = new StringBuilder();
        for (RuntimeManifest.Entry entry : manifest.entries().values()) {
            text.append(entry.sha256).append('\t').append(entry.size).append('\t')
                .append(entry.path).append('\n');
        }
        return text.toString();
    }

    @Test
    public void installsDataLinksAndExecutables() throws Exception {
        File root = directory();
        File prefix = new File(root, "prefix");
        File nativeDir = new File(root, "lib");
        nativeDir.mkdirs();
        write(new File(nativeDir, "libbash.so"), "#!/bin/sh\n");

        File zip = payloadZip(root);
        SealedInstaller.Result result =
            SealedInstaller.install(request(prefix, nativeDir, zip, manifestFor(zip)));

        assertTrue(result.problems.toString(), result.ok());
        assertEquals(2, result.files);
        assertEquals(2, result.verified);
        assertEquals(1, result.links);
        assertEquals(1, result.executables);
        assertEquals("hello", new String(Files.readAllBytes(
            new File(prefix, "share/data.txt").toPath()), StandardCharsets.UTF_8));
        assertTrue(Files.isSymbolicLink(new File(prefix, "bin/bash").toPath()));
        assertEquals(new File(nativeDir, "libbash.so").getAbsolutePath(),
            ExecAudit.readLink(new File(prefix, "bin/bash")));
        assertEquals(new File(prefix, "lib/libz.so.1").getAbsolutePath(),
            ExecAudit.readLink(new File(prefix, "lib/libz.so")));
    }

    @Test
    public void rejectsPayloadThatDoesNotMatchItsManifest() throws Exception {
        File root = directory();
        File prefix = new File(root, "prefix");
        File nativeDir = new File(root, "lib");
        nativeDir.mkdirs();
        write(new File(nativeDir, "libbash.so"), "x");

        File zip = payloadZip(root);
        RuntimeManifest manifest = manifestFor(zip);
        // Corrupt one expectation: the payload is not what the build promised.
        StringBuilder text = new StringBuilder();
        for (RuntimeManifest.Entry entry : manifest.entries().values()) {
            String sha = entry.path.endsWith("data.txt")
                ? "0".repeat(64) : entry.sha256;
            text.append(sha).append('\t').append(entry.size).append('\t')
                .append(entry.path).append('\n');
        }
        SealedInstaller.Result result = SealedInstaller.install(
            request(prefix, nativeDir, zip, RuntimeManifest.parse(text.toString())));

        assertFalse(result.ok());
        assertTrue(result.problems.toString().contains("digest differs: share/data.txt"));
    }

    @Test
    public void reportsPayloadTruncatedAgainstTheManifest() throws Exception {
        File root = directory();
        File zip = payloadZip(root);
        RuntimeManifest manifest = manifestFor(zip);
        StringBuilder text = new StringBuilder(manifestText(manifest))
            .append("a".repeat(64)).append("\t1\tmissing.txt\n");

        File prefix = new File(root, "prefix");
        File nativeDir = new File(root, "lib");
        nativeDir.mkdirs();
        write(new File(nativeDir, "libbash.so"), "x");
        SealedInstaller.Result result = SealedInstaller.install(
            request(prefix, nativeDir, zip, RuntimeManifest.parse(text.toString())));

        assertFalse(result.ok());
        assertTrue(result.problems.toString(),
            result.problems.toString().contains("manifest lists 3"));
    }

    @Test
    public void isIdempotentAcrossUpgrades() throws Exception {
        File root = directory();
        File prefix = new File(root, "prefix");
        File nativeDir = new File(root, "lib");
        nativeDir.mkdirs();
        write(new File(nativeDir, "libbash.so"), "x");
        File zip = payloadZip(root);
        RuntimeManifest manifest = manifestFor(zip);

        SealedInstaller.install(request(prefix, nativeDir, zip, manifest));
        SealedInstaller.Result second =
            SealedInstaller.install(request(prefix, nativeDir, zip, manifest));

        assertTrue(second.problems.toString(), second.ok());
        assertEquals(0, second.executables);
        assertEquals(1, second.alreadySealed);
    }

    @Test
    public void missingLibraryIsReportedNotInvented() throws Exception {
        File root = directory();
        File prefix = new File(root, "prefix");
        File nativeDir = new File(root, "lib");
        nativeDir.mkdirs();
        File zip = payloadZip(root);
        RuntimeManifest manifest = manifestFor(zip);
        // No libbash.so in the native directory: the APK lost a file.
        SealedInstaller.Result result =
            SealedInstaller.install(request(prefix, nativeDir, zip, manifest));

        assertFalse(result.ok());
        assertTrue(result.problems.toString().contains("libbash.so"));
        assertFalse(Files.isSymbolicLink(new File(prefix, "bin/bash").toPath()));
    }

    @Test
    public void refusesToWriteOutsideThePrefix() throws Exception {
        File root = directory();
        File prefix = new File(root, "prefix");
        File nativeDir = new File(root, "lib");
        nativeDir.mkdirs();
        write(new File(nativeDir, "libbash.so"), "x");

        File zip = new File(root, "evil.zip");
        try (java.util.zip.ZipOutputStream out =
                 new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("../escaped.txt"));
            out.write("nope".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("/absolute.txt"));
            out.write("nope".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        SealedInstaller.Result result = SealedInstaller.install(
            request(prefix, nativeDir, zip, RuntimeManifest.empty()));

        assertFalse(result.ok());
        assertFalse(new File(root, "escaped.txt").exists());
        assertFalse(new File("/absolute.txt").exists());
        assertTrue(result.problems.toString().contains("unsafe payload path"));
    }

    @Test
    public void unsafeLinkLinesAreRejected() {
        ArrayList<String> problems = new ArrayList<>();
        assertTrue(SealedInstaller.parseLinks(
            Collections.singletonList("../etc/passwd\tinside"), problems).isEmpty());
        assertEquals(1, problems.size());
        assertNull(RuntimeManifest.normalize("/absolute"));
        assertNull(RuntimeManifest.normalize("a/../../b"));
        assertNotNull(RuntimeManifest.normalize("./bin/sh"));
        assertEquals("bin/sh", RuntimeManifest.normalize("./bin/sh"));
    }

    @Test
    public void manifestRoundTripsAndIgnoresNoise() {
        String text = "# comment\n"
            + "b".repeat(64) + "\t12\tshare/a\n"
            + "\n"
            + "not-a-digest\t1\tx\n"
            + "c".repeat(64) + "\t3\t./share/b\n";
        RuntimeManifest manifest = RuntimeManifest.parse(text);
        assertEquals(2, manifest.size());
        assertEquals(12, manifest.get("share/a").size);
        assertEquals("share/b", manifest.get("share/b").path);
        assertTrue(RuntimeManifest.empty().verify(null).contains("no prefix"));
    }

    @Test
    public void verifyFindsChangedFiles() throws Exception {
        File root = directory();
        File prefix = new File(root, "prefix");
        write(new File(prefix, "share/a"), "abc");
        String sha = RuntimeManifest.digest("abc".getBytes(StandardCharsets.UTF_8));
        RuntimeManifest manifest = RuntimeManifest.parse(sha + "\t3\tshare/a\n");
        assertTrue(manifest.verify(prefix).isEmpty());

        write(new File(prefix, "share/a"), "abcd");
        assertEquals(1, manifest.verify(prefix).size());
        assertTrue(manifest.verify(prefix).get(0).contains("size changed"));
    }
}
