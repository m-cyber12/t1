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
            .toolDirectory(new File("/tmp/harness/bin"))
            .libraryDirectory(new File("/tmp/harness/lib"))
            .shellPath("/system/bin/sh")
            .build();

        Map<String, String> environment = HarnessEnvironment.create(config);

        assertEquals("/tmp/workspace", environment.get("PWD"));
        assertTrue(environment.get("PATH").startsWith("/tmp/harness/bin"));
        assertEquals("xterm-256color", environment.get("TERM"));
        assertEquals("/system/bin/sh", environment.get("SHELL"));
    }

    @Test
    public void omittedRuntimesAreExplicit() {
        assertTrue(Toolchain.OMITTED_RUNTIMES.contains("python"));
        assertTrue(Toolchain.OMITTED_RUNTIMES.contains("npm"));
        assertTrue(Toolchain.OMITTED_RUNTIMES.contains("perl"));
        assertTrue(Toolchain.OMITTED_RUNTIMES.contains("ruby"));
    }
}
