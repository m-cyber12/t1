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
        String sources = new String(java.nio.file.Files.readAllBytes(
            new File(prefix, "etc/apt/sources.list").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(sources.contains("signed-by=/tmp/harness-test/usr/etc/apt/trusted.gpg.d/termux-autobuilds.gpg"));
        assertTrue(sources.contains("https://packages-cf.termux.dev/apt/termux-main/ stable main"));
        File[] keys = new File(prefix, "etc/apt/trusted.gpg.d")
            .listFiles((dir, name) -> name.endsWith(".gpg"));
        assertEquals(8, keys == null ? 0 : keys.length);
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
