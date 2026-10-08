package io.mcyber12.codingharness.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the small, deterministic environment exposed to coding tools. */
public final class HarnessEnvironment {
    private HarnessEnvironment() {}

    public static Map<String, String> create(HarnessConfig config) {
        Map<String, String> environment = new LinkedHashMap<>();

        // Keep useful Android process variables, then replace the variables that
        // must point at the harness sandbox. No PATH from the host is trusted.
        try {
            environment.putAll(System.getenv());
        } catch (SecurityException ignored) {
            // Android normally permits this, but a minimal environment is valid.
        }

        String path = config.toolDirectory.getAbsolutePath()
            + File.pathSeparator + new File(config.workspace, "node_modules/.bin").getAbsolutePath()
            + File.pathSeparator + "/system/bin"
            + File.pathSeparator + "/system/xbin";

        environment.put("HOME", config.home.getAbsolutePath());
        environment.put("PWD", config.workspace.getAbsolutePath());
        environment.put("OLDPWD", config.workspace.getAbsolutePath());
        File root = config.home.getParentFile();
        File tmp = new File(root, "tmp");
        File xdg = new File(root, "xdg");
        environment.put("TMPDIR", tmp.getAbsolutePath());
        environment.put("PATH", path);
        environment.put("SHELL", config.shellPath);
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        environment.put("PREFIX", root.getAbsolutePath());
        environment.put("HARNESS_ROOT", root.getAbsolutePath());
        environment.put("HARNESS_WORKSPACE", config.workspace.getAbsolutePath());
        environment.put("HARNESS_TOOLS", config.toolDirectory.getAbsolutePath());
        environment.put("XDG_DATA_HOME", new File(xdg, "data").getAbsolutePath());
        environment.put("XDG_CONFIG_HOME", new File(xdg, "config").getAbsolutePath());
        environment.put("XDG_STATE_HOME", new File(xdg, "state").getAbsolutePath());
        environment.put("XDG_CACHE_HOME", new File(xdg, "cache").getAbsolutePath());

        if (config.libraryDirectory.isDirectory()) {
            String existing = environment.get("LD_LIBRARY_PATH");
            environment.put("LD_LIBRARY_PATH", config.libraryDirectory.getAbsolutePath()
                + (existing == null || existing.isEmpty() ? "" : File.pathSeparator + existing));
        }

        // Android's shell prompt can be quite noisy and platform-dependent.
        // A stable prompt makes the embedded terminal useful for agents and tests.
        environment.put("PS1", "\u@coding-harness:\\w$ ");
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
