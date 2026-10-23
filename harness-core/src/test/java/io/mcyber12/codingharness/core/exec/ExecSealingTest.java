package io.mcyber12.codingharness.core.exec;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the sealed-execution pieces: classification, the sealed
 * manifest, and the audit that decides whether an install needs the
 * data-directory exec exemption.
 */
public class ExecSealingTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final byte[] ELF_HEAD =
        {0x7f, (byte) 'E', (byte) 'L', (byte) 'F', 2, 1, 1, 0};

    // ---------------------------------------------------------------- probe

    @Test
    public void classifiesElfAndScriptsFromTheFirstBytes() {
        assertEquals(ExecutableKind.ELF, ExecutableProbe.classify(ELF_HEAD, ELF_HEAD.length));
        byte[] script = "#!/data/data/com.termux/files/usr/bin/bash\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(ExecutableKind.SCRIPT, ExecutableProbe.classify(script, script.length));
        byte[] data = "PREFIX=/data/data/com.termux\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(ExecutableKind.DATA, ExecutableProbe.classify(data, data.length));
        assertEquals(ExecutableKind.DATA, ExecutableProbe.classify(new byte[]{'x'}, 1));
        assertTrue(ExecutableProbe.requiresExecAllowedPath(ExecutableKind.ELF));
        assertTrue(ExecutableProbe.requiresExecAllowedPath(ExecutableKind.SCRIPT));
        assertFalse(ExecutableProbe.requiresExecAllowedPath(ExecutableKind.DATA));
    }

    @Test
    public void readsFilesFromDisk() throws Exception {
        File binary = temporary.newFile("elf-thing");
        try (FileOutputStream output = new FileOutputStream(binary)) {
            output.write(ELF_HEAD);
        }
        assertEquals(ExecutableKind.ELF, ExecutableProbe.kindOf(binary));
        // An empty file is data, not an unreadable executable.
        assertEquals(ExecutableKind.DATA, ExecutableProbe.kindOf(temporary.newFile("empty")));
    }

    // ------------------------------------------------------------- manifest

    @Test
    public void parsesTabsSpacesAndComments() {
        String manifest = "# prefix path -> JNI library\n"
            + "bin/bash\tlibbash.so\n"
            + "\t\n"
            + "bin/git libgit.so\n"
            + "bin/node\tlibnode.so\n"
            + "../escape\tlibevil.so\n"
            + "/absolute\tlibabs.so\n";
        List<SealedLayout.Link> links = SealedLayout.parse(manifest);
        assertEquals(3, links.size());
        assertEquals("bin/bash", links.get(0).path);
        assertEquals("libbash.so", links.get(0).library);
        assertEquals("libgit.so", links.get(1).library);
        assertEquals("bin/node", links.get(2).path);
    }

    // ---------------------------------------------------------------- apply

    @Test
    public void linksExecutablesIntoTheApkAndIsIdempotent() throws Exception {
        File prefix = temporary.newFolder("prefix");
        File nativeDir = temporary.newFolder("lib");
        File library = new File(nativeDir, "libbash.so");
        try (FileOutputStream output = new FileOutputStream(library)) {
            output.write(ELF_HEAD);
        }

        SealedLayout.Result first = SealedLayout.apply(prefix, nativeDir,
            SealedLayout.parse("bin/bash\tlibbash.so\n"));
        assertTrue(first.summary(), first.ok());
        assertEquals(1, first.linked);
        File link = new File(prefix, "bin/bash");
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertEquals(library.getAbsolutePath(), ExecAudit.readLink(link));

        SealedLayout.Result second = SealedLayout.apply(prefix, nativeDir,
            SealedLayout.parse("bin/bash\tlibbash.so\n"));
        assertEquals(0, second.linked);
        assertEquals(1, second.alreadyCorrect);
        assertTrue(second.ok());
    }

    @Test
    public void reportsMissingLibrariesInsteadOfInventingThem() throws Exception {
        File prefix = temporary.newFolder("prefix");
        File nativeDir = temporary.newFolder("lib");
        SealedLayout.Result result = SealedLayout.apply(prefix, nativeDir,
            SealedLayout.parse("bin/python\tlibpython.so\n"));
        assertFalse(result.ok());
        assertEquals(1, result.missingTargets.size());
        assertFalse(new File(prefix, "bin/python").exists());
    }

    @Test
    public void refusesToReplaceADirectory() throws Exception {
        File prefix = temporary.newFolder("prefix");
        File nativeDir = temporary.newFolder("lib");
        File library = new File(nativeDir, "libx.so");
        assertTrue(library.createNewFile());
        assertTrue(new File(prefix, "bin").mkdirs());
        SealedLayout.Result result = SealedLayout.apply(prefix, nativeDir,
            SealedLayout.parse("bin\tlibx.so\n"));
        assertFalse(result.ok());
        assertTrue(result.errors.get(0).contains("directory"));
    }

    // ---------------------------------------------------------------- audit

    @Test
    public void auditSplitsShippedExecutablesFromDataDirOnes() throws Exception {
        File prefix = temporary.newFolder("prefix");
        File nativeDir = temporary.newFolder("lib");
        File library = new File(nativeDir, "libbash.so");
        try (FileOutputStream output = new FileOutputStream(library)) {
            output.write(ELF_HEAD);
        }

        File bin = new File(prefix, "bin");
        assertTrue(bin.mkdirs());
        // A real ELF binary installed under the prefix (the Termux shape).
        File apt = new File(bin, "apt-get");
        try (FileOutputStream output = new FileOutputStream(apt)) {
            output.write(ELF_HEAD);
        }
        assertTrue(apt.setExecutable(true, false));
        // A package script with the official prefix in its #! line.
        File script = new File(bin, "update-ca-certificates");
        try (FileOutputStream output = new FileOutputStream(script)) {
            output.write("#!/data/data/com.termux/files/usr/bin/bash\n".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(script.setExecutable(true, false));
        // Package data: readable, never executed, must not affect the verdict.
        File data = new File(prefix, "share/doc/README");
        assertTrue(data.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(data)) {
            output.write("docs".getBytes(StandardCharsets.UTF_8));
        }

        ExecAudit.Report before = ExecAudit.audit(prefix, nativeDir);
        assertEquals(2, before.examined());
        // The ELF binary is the problem; the script is not, because it is run
        // through the interpreter layer rather than exec'd.
        assertEquals(before.toText(), 1, before.outsideApk().size());
        assertEquals(before.toText(), 1, before.outsideScripts().size());
        assertEquals("bin/apt-get", before.outsideApk().get(0).path);
        assertFalse(before.publishable());
        assertTrue(before.toText().contains("targetSdk <= 28 only"));

        // Sealing replaces them with pointers into the APK.
        File gitLib = new File(nativeDir, "libgit.so");
        try (FileOutputStream output = new FileOutputStream(gitLib)) {
            output.write(ELF_HEAD);
        }
        SealedLayout.Result result = SealedLayout.apply(prefix, nativeDir, SealedLayout.parse(
            "bin/apt-get\tlibgit.so\n"
                + "bin/update-ca-certificates\tlibgit.so\n"));
        assertTrue(result.summary(), result.ok());

        ExecAudit.Report after = ExecAudit.audit(prefix, nativeDir);
        assertTrue(after.toText(), after.publishable());
        assertEquals(after.toText(), 2, after.shippedCount());
        assertTrue(after.toText().contains("publishable at any targetSdk"));
    }

    @Test
    public void auditSurvivesBrokenEntriesInsteadOfThrowing() throws Exception {
        File prefix = temporary.newFolder("prefix-broken");
        File bin = new File(prefix, "bin");
        assertTrue(bin.mkdirs());
        // Dangling links cannot be executed, so they must not be reported as
        // something that needs the exemption - and must not throw either.
        Files.createSymbolicLink(new File(bin, "dangling").toPath(),
            new File("/data/data/com.termux/files/usr/bin/absent").toPath());
        Files.createSymbolicLink(new File(bin, "weird").toPath(),
            new File("/nonexistent/with space/x").toPath());
        ExecAudit.Report report = ExecAudit.audit(prefix, null);
        assertEquals(report.toText(), 0, report.examined());
        assertTrue(report.publishable());
    }

    @Test
    public void auditJudgesALinkByWhatItResolvesTo() throws Exception {
        File prefix = temporary.newFolder("prefix-links");
        File bin = new File(prefix, "bin");
        File lib = new File(prefix, "lib");
        assertTrue(bin.mkdirs());
        assertTrue(lib.mkdirs());

        // A versioned library link: dlopen only, never execve'd.
        File versioned = new File(lib, "libssl.so.3");
        try (FileOutputStream output = new FileOutputStream(versioned)) {
            output.write(ELF_HEAD);
        }
        Files.createSymbolicLink(new File(lib, "libssl.so").toPath(),
            new File("libssl.so.3").toPath());
        // A link to an executable installed in app data: this one is the
        // problem the audit exists to count.
        File realBinary = new File(prefix, "libexec/git-core/git");
        assertTrue(realBinary.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(realBinary)) {
            output.write(ELF_HEAD);
        }
        assertTrue(realBinary.setExecutable(true, false));
        Files.createSymbolicLink(new File(bin, "git").toPath(),
            new File("../libexec/git-core/git").toPath());

        ExecAudit.Report report = ExecAudit.audit(prefix, temporary.newFolder("libdir"));
        // Two binaries live in app data: the binary itself and the link to it.
        // The versioned library must not be counted at all.
        assertEquals(report.toText(), 2, report.outsideApk().size());
        assertTrue(report.toText(), report.toText().contains("bin/git"));
        assertTrue(report.toText(), report.toText().contains("libexec/git-core/git"));
        assertFalse(report.toText(), report.toText().contains("libssl"));
    }

    @Test
    public void auditOfAMissingPrefixIsEmpty() {
        ExecAudit.Report report = ExecAudit.audit(new File(temporary.getRoot(), "absent"), null);
        assertEquals(0, report.examined());
        assertTrue(report.publishable());
    }
}
