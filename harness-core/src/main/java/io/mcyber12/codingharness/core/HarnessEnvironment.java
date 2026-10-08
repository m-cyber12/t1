package io.mcyber12.codingharness.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the Termux-compatible environment exposed to coding tools. */
public final class HarnessEnvironment {
    private HarnessEnvironment() {}

    public static Map<String, String> create(HarnessConfig config) {
        Map<String, String> environment = new LinkedHashMap<>();

        // Keep useful Android process variables, then replace the variables
        // that must point at the harness sandbox. No host PATH is trusted.
        try {
            environment.putAll(System.getenv());
        } catch (SecurityException ignored) {
            // Android normally permits this, but a minimal environment is valid.
        }

        String path = config.toolDirectory.getAbsolutePath()
            + File.pathSeparator + new File(config.workspace, "node_modules/.bin").getAbsolutePath()
            + File.pathSeparator + "/system/bin"
            + File.pathSeparator + "/system/xbin";
        File root = config.prefix.getParentFile();

        environment.put("HOME", config.home.getAbsolutePath());
        environment.put("PWD", config.workspace.getAbsolutePath());
        environment.put("OLDPWD", config.workspace.getAbsolutePath());
        environment.put("TMPDIR", new File(config.prefix, "tmp").getAbsolutePath());
        environment.put("PREFIX", config.prefix.getAbsolutePath());
        environment.put("PATH", path);
        environment.put("SHELL", config.shellPath);
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        environment.put("HARNESS_ROOT", root.getAbsolutePath());
        environment.put("HARNESS_PREFIX", config.prefix.getAbsolutePath());
        environment.put("HARNESS_WORKSPACE", config.workspace.getAbsolutePath());
        environment.put("HARNESS_TOOLS", config.toolDirectory.getAbsolutePath());
        environment.put("XDG_DATA_HOME", new File(root, "xdg/data").getAbsolutePath());
        environment.put("XDG_CONFIG_HOME", new File(root, "xdg/config").getAbsolutePath());
        environment.put("XDG_STATE_HOME", new File(root, "xdg/state").getAbsolutePath());
        environment.put("XDG_CACHE_HOME", new File(root, "xdg/cache").getAbsolutePath());

        String existingLibraries = environment.get("LD_LIBRARY_PATH");
        environment.put("LD_LIBRARY_PATH", config.libraryDirectory.getAbsolutePath()
            + (existingLibraries == null || existingLibraries.isEmpty()
                ? "" : File.pathSeparator + existingLibraries));

        // Android's shell prompt can be platform-dependent. A stable prompt
        // makes the embedded terminal useful for agents and tests.
        environment.put("PS1", "\\u@coding-harness:\\w$ ");
        return environment;
    }

    public static String[] toArray(Map<String, String> environment) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) continue;
            values.add(entry.getKey() + "=" + entry.getValue());
        }
        return values.toArray(new String[0]);
    }
}
