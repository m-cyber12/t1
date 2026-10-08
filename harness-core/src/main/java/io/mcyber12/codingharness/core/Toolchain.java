package io.mcyber12.codingharness.core;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool discovery for the coding harness.
 *
 * <p>The harness deliberately does not pretend to be a general Linux distro.
 * Bun/JavaScript, Git, ripgrep and the Android shell are the first-class
 * tools. Python, npm, Perl and Ruby are not silently assumed to exist.</p>
 */
public final class Toolchain {
    public static final List<String> REQUIRED_TOOLS = Collections.unmodifiableList(
        Arrays.asList("sh", "git", "rg", "bun")
    );
    public static final List<String> OMITTED_RUNTIMES = Collections.unmodifiableList(
        Arrays.asList("python", "python3", "npm", "perl", "ruby")
    );

    private Toolchain() {}

    public static Map<String, ToolStatus> inspect(HarnessConfig config) {
        Map<String, ToolStatus> result = new LinkedHashMap<>();
        result.put("sh", new ToolStatus("sh", config.shellPath, true, "Android system shell"));
        for (String name : Arrays.asList("git", "rg", "bun")) {
            File candidate = new File(config.toolDirectory, name);
            boolean executable = candidate.isFile() && candidate.canExecute();
            result.put(name, new ToolStatus(name, candidate.getAbsolutePath(), executable,
                executable ? "harness tool" : "not bundled yet"));
        }
        for (String name : OMITTED_RUNTIMES) {
            result.put(name, new ToolStatus(name, null, false, "intentionally not part of the harness"));
        }
        return result;
    }

    public static final class ToolStatus {
        public final String name;
        public final String path;
        public final boolean available;
        public final String note;

        public ToolStatus(String name, String path, boolean available, String note) {
            this.name = name;
            this.path = path;
            this.available = available;
            this.note = note;
        }
    }
}
