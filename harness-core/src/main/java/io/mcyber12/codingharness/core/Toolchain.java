package io.mcyber12.codingharness.core;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tool discovery for the coding harness. */
public final class Toolchain {
    public static final List<String> REQUIRED_TOOLS = Collections.unmodifiableList(
        Arrays.asList("sh", "bash", "git", "rg", "python", "node", "npm", "perl", "ruby")
    );
    public static final List<String> OPTIONAL_TOOLS = Collections.unmodifiableList(
        Arrays.asList("bun", "curl", "make", "clang", "cmake", "jq", "tree", "tar", "zip", "unzip", "ssh")
    );

    private Toolchain() {}

    public static Map<String, ToolStatus> inspect(HarnessConfig config) {
        Map<String, ToolStatus> result = new LinkedHashMap<>();
        result.put("sh", new ToolStatus("sh", config.shellPath, true, "Android system shell"));
        for (String name : REQUIRED_TOOLS) {
            if ("sh".equals(name)) continue;
            result.put(name, inspectPrefixTool(config, name));
        }
        for (String name : OPTIONAL_TOOLS) {
            result.put(name, inspectPrefixTool(config, name));
        }
        return result;
    }

    private static ToolStatus inspectPrefixTool(HarnessConfig config, String name) {
        File candidate = new File(config.toolDirectory, name);
        boolean executable = candidate.isFile() && candidate.canExecute();
        return new ToolStatus(name, candidate.getAbsolutePath(), executable,
            executable ? "Termux package or host-provided tool" : "not installed yet");
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
