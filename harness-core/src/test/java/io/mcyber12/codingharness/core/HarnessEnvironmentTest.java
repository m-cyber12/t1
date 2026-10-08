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
        assertEquals("xterm-256color", environment.get("TERM"));
        assertEquals("/system/bin/sh", environment.get("SHELL"));
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
