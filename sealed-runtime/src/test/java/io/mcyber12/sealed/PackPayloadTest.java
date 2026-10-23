package io.mcyber12.sealed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A pack is an overlay on an installed prefix, and it has to be removable
 * without touching the runtime underneath it.
 *
 * <p>This is the property the UI offers ("install build tools", "remove build
 * tools") and the one a Play Feature Delivery module depends on, so it is
 * tested here — on the JVM, in seconds — rather than only on a device.</p>
 */
public class PackPayloadTest {

    private static File directory() throws IOException {
        File root = Files.createTempDirectory("pack-payload").toFile();
        root.deleteOnExit();
        return root;
    }

    private static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A pack payload: a tool, a data file and a helper command. */
    private static byte[] packZip() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("bin/make"));
            zip.write("make-binary".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("share/make/rules.txt"));
            zip.write("rules".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static List<String> manifestLines(byte[] zip) throws IOException {
        List<String> lines = new ArrayList<>();
        try (java.util.zip.ZipInputStream in =
                 new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] content = in.readAllBytes();
                lines.add(RuntimeManifest.digest(content) + "\t" + content.length + "\t"
                    + entry.getName());
            }
        }
        return lines;
    }

    @Test
    public void installsOverARuntimeAndRemovesOnlyItsOwnFiles() throws Exception {
        File root = directory();
        File prefix = new File(root, "prefix");
        File nativeDir = new File(root, "lib");
        nativeDir.mkdirs();
        write(new File(nativeDir, "libmake.so"), "make-binary");
        write(new File(nativeDir, "libbash.so"), "bash-binary");

        // A runtime that already exists: this is what a pack lands on top of.
        write(new File(prefix, "bin/bash"), "bash-binary");
        write(new File(prefix, "share/core/notes.txt"), "core-data");

        byte[] zip = packZip();
        List<String> manifest = manifestLines(zip);
        List<String> exec = Arrays.asList("bin/make\tlibmake.so");

        SealedInstaller.Result installed = SealedInstaller.install(new SealedInstaller.Request()
            .prefix(prefix)
            .nativeLibraryDirectory(nativeDir)
            .payload(new ByteArrayInputStream(zip))
            .manifest(new ByteArrayInputStream(join(manifest).getBytes(StandardCharsets.UTF_8)))
            .execManifest(exec)
            .linkManifest(new ArrayList<String>()));

        assertTrue(installed.problems.toString(), installed.ok());
        assertEquals(2, installed.files);
        assertEquals(2, installed.verified);
        assertEquals(1, installed.executables);
        assertTrue(Files.isSymbolicLink(new File(prefix, "bin/make").toPath()));
        assertEquals("rules", new String(Files.readAllBytes(
            new File(prefix, "share/make/rules.txt").toPath()), StandardCharsets.UTF_8));

        SealedUninstaller.Result removed =
            SealedUninstaller.remove(prefix, exec, manifest);

        assertTrue(removed.problems.toString(), removed.ok());
        // The executables the pack linked (one) and the data it wrote that is
        // still there (one): the tool's own data file, because the link was
        // already removed through the exec manifest.
        assertEquals(2, removed.files);
        assertTrue("bytes were not accounted for: " + removed.bytes, removed.bytes > 0);
        assertFalse(new File(prefix, "bin/make").exists());
        assertFalse(new File(prefix, "share/make/rules.txt").exists());
        // ...and the runtime underneath is untouched.
        assertTrue(new File(prefix, "bin/bash").isFile());
        assertTrue(new File(prefix, "share/core/notes.txt").isFile());
        // The libraries belong to the APK, not the prefix: they stay.
        assertTrue(new File(nativeDir, "libmake.so").isFile());
    }

    @Test
    public void removalLeavesFilesItDidNotWrite() throws Exception {
        File root = directory();
        File prefix = new File(root, "prefix");
        byte[] zip = packZip();
        List<String> manifest = manifestLines(zip);

        // A file at the same path, with different contents and size: somebody
        // else's. Removal must not take it.
        write(new File(prefix, "share/make/rules.txt"), "user-owned, longer than the pack's");

        SealedUninstaller.Result removed = SealedUninstaller.remove(prefix, null, manifest);
        assertTrue(removed.ok());
        assertEquals(0, removed.files);
        assertTrue("removal deleted a file it did not write",
            new File(prefix, "share/make/rules.txt").isFile());
    }

    @Test
    public void removalIgnoresUnsafeManifestLines() throws Exception {
        File root = directory();
        File prefix = new File(root, "prefix");
        write(new File(prefix, "keep.txt"), "keep");

        List<String> manifest = Arrays.asList(
            RuntimeManifest.digest("keep".getBytes(StandardCharsets.UTF_8)) + "\t4\tkeep.txt",
            "0".repeat(64) + "\t1\t../../etc/passwd");
        SealedUninstaller.Result removed = SealedUninstaller.remove(prefix, null, manifest);

        assertTrue(removed.ok());
        assertEquals(1, removed.files);
        // The honest file went, the escaping one was never a candidate.
        assertFalse(new File(prefix, "keep.txt").exists());
        assertFalse("removal escaped the prefix", new File(root, "etc/passwd").exists());
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }
}
