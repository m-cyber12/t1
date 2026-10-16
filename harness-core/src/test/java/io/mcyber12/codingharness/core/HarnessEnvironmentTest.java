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
    public void codingPackagesIncludeTheLanguageRuntimes() {
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("python"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("nodejs"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("npm"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("perl"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("ruby"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("git"));
        assertTrue(HarnessPackageInstaller.CODING_PACKAGES.contains("ripgrep"));
    }
}
