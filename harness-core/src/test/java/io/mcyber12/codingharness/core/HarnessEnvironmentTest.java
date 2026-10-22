package io.mcyber12.codingharness.core;

import org.junit.Test;

import java.io.File;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HarnessEnvironmentTest {
    @Test
    public void codingEnvironmentIsDeterministic() {
        HarnessConfig config = new HarnessConfig.Builder()
            .workspace(new File("/tmp/workspace"))
            .home(new File("/tmp/harness/home"))
            .prefix(new File("/tmp/harness/usr"))
            .toolDirectory(new File("/tmp/harness/usr/bin"))
            .libraryDirectory(new File("/tmp/harness/lib"))
            .shellPath("/system/bin/sh")
            .build();

        Map<String, String> environment = HarnessEnvironment.create(config);

        assertEquals("/tmp/workspace", environment.get("PWD"));
        assertTrue(environment.get("PATH").startsWith("/tmp/harness/usr/bin"));
        assertEquals("/tmp/harness/usr", environment.get("PREFIX"));
        assertEquals("/tmp/harness", environment.get("TERMUX__ROOTFS"));
        assertEquals("/tmp/harness/usr", environment.get("TERMUX__PREFIX"));
        assertEquals("xterm-256color", environment.get("TERM"));
        assertEquals("/system/bin/sh", environment.get("SHELL"));
    }

    @Test
    public void aptConfigRelocatesAptKeyAndPrepareInstallsKeyring() throws Exception {
        File prefix = new File("/tmp/harness-test/usr");
        HarnessConfig config = new HarnessConfig.Builder()
            .workspace(new File("/tmp/harness-test/workspace"))
            .home(new File("/tmp/harness-test/home"))
            .prefix(prefix)
            .toolDirectory(new File(prefix, "bin"))
            .libraryDirectory(new File(prefix, "lib"))
            .shellPath("/system/bin/sh")
            .build();

        HarnessEnvironment.prepare(config);

        String aptConf = new String(java.nio.file.Files.readAllBytes(
            new File(prefix, "etc/apt/harness-apt.conf").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(aptConf.contains("Dir::Bin::apt-key \"/tmp/harness-test/usr/bin/apt-key\""));
        assertTrue(aptConf.contains("Dir::Bin::dpkg \"/tmp/harness-test/usr/bin/dpkg\""));
        assertTrue(aptConf.contains("Dir::Etc::trustedparts \"trusted.gpg.d\""));
        // apt would otherwise overwrite PATH for dpkg with its compiled-in
        // "/usr/sbin:/usr/bin:/sbin:/bin" default and dpkg would refuse to
        // run (its sh/rm/tar/... check fails in the relocated prefix).
        assertTrue(aptConf.contains("DPkg::Path \"/tmp/harness-test/usr/bin\""));
        // Relocation: Termux .debs unpack the literal official-prefix tree
        // under dpkg's fsys root (DPKG_ROOT); the synthetic root links it
        // onto this prefix and maintainer scripts must not chroot.
        assertTrue(aptConf.contains("DPkg::Options:: \"--force-script-chrootless\""));
        assertTrue(aptConf.contains("DPkg::Options:: \"--force-confold\""));
        // The chain down to the (here legacy-layout) prefix tail must exist
        // as REAL directories: dpkg refuses to create package-owned
        // directories over symlinks ("trying to overwrite .../usr").
        assertTrue(new File(
            "/tmp/harness-test/rootfs/data/data/com.termux/files").isDirectory());
        java.util.Map<String, String> environment = HarnessEnvironment.create(config);
        assertTrue("/tmp/harness-test/rootfs".equals(environment.get("DPKG_ROOT")));
        String sources = new String(java.nio.file.Files.readAllBytes(
            new File(prefix, "etc/apt/sources.list").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(sources.contains("signed-by=/tmp/harness-test/usr/etc/apt/trusted.gpg.d/termux-autobuilds.gpg"));
        assertTrue(sources.contains("https://packages-cf.termux.dev/apt/termux-main/ stable main"));
        File[] keys = new File(prefix, "etc/apt/trusted.gpg.d")
            .listFiles((dir, name) -> name.endsWith(".gpg"));
        assertEquals(8, keys == null ? 0 : keys.length);
    }

    @Test
    public void prepareReplacesDanglingBootstrapKeySymlinks() throws Exception {
        // The Termux bootstrap's SYMLINKS.txt leaves dangling symlinks in
        // trusted.gpg.d (targets under the official prefix). prepare() must
        // replace them with regular key files, otherwise writes fail with
        // ENOENT and apt reports NO_PUBKEY. Reproduces the emulator E2E
        // failure of a01_aptChainUpdatesAndInstallsSmallPackage.
        File prefix = new File("/tmp/harness-test-links/usr");
        File trusted = new File(prefix, "etc/apt/trusted.gpg.d");
        trusted.mkdirs();
        // deleteIfExists: debug and release unit-test variants share /tmp,
        // and a previous variant may already have replaced the link.
        java.nio.file.Files.deleteIfExists(new File(trusted, "termux-autobuilds.gpg").toPath());
        java.nio.file.Files.createSymbolicLink(
            new File(trusted, "termux-autobuilds.gpg").toPath(),
            new File("/data/data/com.termux/files/usr/share/termux-keyring/termux-autobuilds.gpg").toPath());
        HarnessConfig config = new HarnessConfig.Builder()
            .workspace(new File("/tmp/harness-test-links/workspace"))
            .home(new File("/tmp/harness-test-links/home"))
            .prefix(prefix)
            .toolDirectory(new File(prefix, "bin"))
            .libraryDirectory(new File(prefix, "lib"))
            .shellPath("/system/bin/sh")
            .build();

        HarnessEnvironment.prepare(config);

        File key = new File(trusted, "termux-autobuilds.gpg");
        assertTrue(!java.nio.file.Files.isSymbolicLink(key.toPath()));
        assertTrue(key.isFile() && key.length() > 0);
    }

    @Test
    public void relocateSymlinksRewritesOfficialPrefixTargets() throws Exception {
        // Reproduces the real-device termux-keyring upgrade failure: the
        // bootstrap ships share/pacman/keyrings/*.gpg as an ABSOLUTE symlink
        // into /data/data/com.termux/...; dpkg's stat (dereference) of that
        // link while unpacking gets EACCES on a stock phone and aborts.
        File base = new File("/tmp/harness-test-relocate");
        File prefix = new File(base, "rootfs/data/data/com.termux/files/usr");
        File keyringDir = new File(prefix, "share/pacman/keyrings");
        keyringDir.mkdirs();
        File realKey = new File(prefix, "share/termux-keyring/2096779623.gpg");
        realKey.getParentFile().mkdirs();
        java.nio.file.Files.write(realKey.toPath(), new byte[] {1, 2, 3});
        File link = new File(keyringDir, "2096779623.gpg");
        java.nio.file.Files.deleteIfExists(link.toPath());
        java.nio.file.Files.createSymbolicLink(link.toPath(),
            new File("/data/data/com.termux/files/usr/share/termux-keyring/2096779623.gpg").toPath());
        // A relative link and a non-Termux absolute link must be untouched.
        File relativeLink = new File(keyringDir, "relative.gpg");
        java.nio.file.Files.deleteIfExists(relativeLink.toPath());
        java.nio.file.Files.createSymbolicLink(relativeLink.toPath(),
            new File("../../termux-keyring/2096779623.gpg").toPath());
        File systemLink = new File(keyringDir, "system.gpg");
        java.nio.file.Files.deleteIfExists(systemLink.toPath());
        java.nio.file.Files.createSymbolicLink(systemLink.toPath(),
            new File("/system/etc/security/cacerts").toPath());

        int rewritten = HarnessBootstrap.relocateSymlinks(
            prefix, "/data/data/com.termux", new File(base, "rootfs/data/data/com.termux"));

        assertEquals(1, rewritten);
        String relocated = java.nio.file.Files.readSymbolicLink(link.toPath()).toString();
        assertTrue(relocated.endsWith(
            "rootfs/data/data/com.termux/files/usr/share/termux-keyring/2096779623.gpg"));
        // And the relocated link now actually resolves to the key file.
        assertTrue(link.isFile() && link.length() == 3);
        assertTrue(java.nio.file.Files.readSymbolicLink(relativeLink.toPath())
            .toString().contains(".."));
        assertEquals("/system/etc/security/cacerts",
            java.nio.file.Files.readSymbolicLink(systemLink.toPath()).toString());
    }

    @Test
    public void extractsHarnessExecLibraryForPreload() throws Exception {
        File directory = new File("/tmp/harness-lib-test");
        File apk = new File(directory, "fake.apk");
        File destination = new File(directory, "libharness-exec.so");
        java.nio.file.Files.deleteIfExists(destination.toPath());
        byte[] fakeLibrary = new byte[] {0x7f, 'E', 'L', 'F', 1, 2, 3, 4};
        directory.mkdirs();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(apk))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("lib/x86_64/libharness-exec.so"));
            zip.write(fakeLibrary);
            zip.closeEntry();
        }
        HarnessBootstrap.extractHarnessExecLibrary(apk, "x86_64", destination);
        assertTrue(destination.isFile());
        org.junit.Assert.assertArrayEquals(fakeLibrary,
            java.nio.file.Files.readAllBytes(destination.toPath()));
        // Idempotent second call keeps the file.
        HarnessBootstrap.extractHarnessExecLibrary(apk, "x86_64", destination);
        org.junit.Assert.assertArrayEquals(fakeLibrary,
            java.nio.file.Files.readAllBytes(destination.toPath()));
    }

    @Test
    public void codingPackagesIncludeTheLanguageRuntimes() {
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("python"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("nodejs"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("npm"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("git"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("ripgrep"));
    }

    @Test
    public void packageTiersKeepHeavyweightToolsOnDemand() {
        // The default install is the small agentic core; the ~300 MB
        // compiler block and the extra language runtimes are explicit
        // on-demand packs (docs/size-management.md).
        assertTrue(HarnessPackageInstaller.CORE_PACKAGES.contains("python-pip"));
        assertTrue(!HarnessPackageInstaller.CORE_PACKAGES.contains("clang"));
        assertTrue(!HarnessPackageInstaller.CORE_PACKAGES.contains("cmake"));
        assertTrue(!HarnessPackageInstaller.CORE_PACKAGES.contains("ruby"));
        assertTrue(!HarnessPackageInstaller.CORE_PACKAGES.contains("perl"));
        assertTrue(HarnessPackageInstaller.BUILD_TOOL_PACKAGES.contains("clang"));
        assertTrue(HarnessPackageInstaller.BUILD_TOOL_PACKAGES.contains("cmake"));
        assertTrue(HarnessPackageInstaller.EXTRA_LANGUAGE_PACKAGES.contains("ruby"));
        assertTrue(HarnessPackageInstaller.EXTRA_LANGUAGE_PACKAGES.contains("perl"));
        // No package is declared in two tiers.
        for (String name : HarnessPackageInstaller.CORE_PACKAGES) {
            assertTrue(!HarnessPackageInstaller.BUILD_TOOL_PACKAGES.contains(name));
            assertTrue(!HarnessPackageInstaller.EXTRA_LANGUAGE_PACKAGES.contains(name));
        }
    }

    @Test
    public void pruneRemovesOnlyNonRuntimePayload() throws Exception {
        File prefix = new File("/tmp/harness-test-prune/usr");
        File man = new File(prefix, "share/man/man1");
        man.mkdirs();
        java.nio.file.Files.write(new File(man, "git.1").toPath(), new byte[100]);
        File locale = new File(prefix, "share/locale/de/LC_MESSAGES");
        locale.mkdirs();
        java.nio.file.Files.write(new File(locale, "git.mo").toPath(), new byte[50]);
        File stdlib = new File(prefix, "lib/python3.14/json");
        stdlib.mkdirs();
        File decoder = new File(stdlib, "decoder.py");
        java.nio.file.Files.write(decoder.toPath(), new byte[10]);
        File pycache = new File(stdlib, "__pycache__");
        pycache.mkdirs();
        java.nio.file.Files.write(new File(pycache, "decoder.pyc").toPath(), new byte[40]);
        File userCache = new File(prefix, "lib/python3.14/site-packages/requests/__pycache__");
        userCache.mkdirs();
        File userPyc = new File(userCache, "models.pyc");
        java.nio.file.Files.write(userPyc.toPath(), new byte[30]);
        File binary = new File(prefix, "bin/git");
        binary.getParentFile().mkdirs();
        java.nio.file.Files.write(binary.toPath(), new byte[20]);

        long freed = HarnessPackageInstaller.pruneNonRuntimePayload(prefix);

        assertEquals(100 + 50 + 40, freed);
        assertTrue(!new File(prefix, "share/man").exists());
        assertTrue(!new File(prefix, "share/locale").exists());
        assertTrue(decoder.isFile());       // stdlib code kept
        assertTrue(userPyc.isFile());       // user site-packages kept
        assertTrue(binary.isFile());        // binaries kept
    }

    @Test
    public void sizeReportParsesDpkgStatus() {
        String status = "Package: bash\nStatus: install ok installed\nInstalled-Size: 2048\n\n"
            + "Package: libllvm\nInstalled-Size: 204800\n\n"
            + "Package: clang\nInstalled-Size: 102400\nDescription: no size above\n\n";
        java.util.Map<String, Long> sizes = HarnessSizeReport.parseInstalledSizes(status);
        assertEquals(3, sizes.size());
        assertEquals(Long.valueOf(2048L * 1024L), sizes.get("bash"));
        assertEquals(Long.valueOf(204800L * 1024L), sizes.get("libllvm"));
        assertTrue(HarnessSizeReport.formatBytes(204800L * 1024L).contains("MiB"));
        assertTrue(HarnessSizeReport.formatBytes(3L * 1024L * 1024L * 1024L).contains("GiB"));
    }
}
