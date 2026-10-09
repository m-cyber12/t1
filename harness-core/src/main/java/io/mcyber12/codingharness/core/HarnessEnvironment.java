package io.mcyber12.codingharness.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the Termux-compatible environment exposed to coding tools. */
public final class HarnessEnvironment {
    private static final String APT_CONFIG_NAME = "harness-apt.conf";

    private HarnessEnvironment() {}

    /**
     * Creates the small relocation config needed by apt and dpkg. The
     * official Termux binaries use a fixed build-time prefix, so package
     * management must be pointed at this app's private prefix explicitly.
     */
    public static void prepare(HarnessConfig config) throws IOException {
        File aptDirectory = new File(config.prefix, "etc/apt");
        if (!aptDirectory.isDirectory() && !aptDirectory.mkdirs() && !aptDirectory.isDirectory()) {
            throw new IOException("Unable to create apt configuration directory: " + aptDirectory);
        }
        ensureDirectory(new File(aptDirectory, "apt.conf.d"));
        ensureDirectory(new File(aptDirectory, "sources.list.d"));
        ensureDirectory(new File(aptDirectory, "trusted.gpg.d"));
        ensureDirectory(new File(config.prefix, "var/lib/apt/lists/partial"));
        ensureDirectory(new File(config.prefix, "var/cache/apt/archives/partial"));
        ensureDirectory(new File(config.prefix, "var/log/apt"));

        // The deb packaging system is auto-detected by apt only when a dpkg
        // status database and the dpkg binary exist. Never set
        // "Apt::System" here: selecting a system explicitly can pick the EDSP
        // planner interface, which forces APT::Get::Simulate and redirects the
        // status/list databases to /dev/null, so nothing would be installed.
        File dpkgDirectory = new File(config.prefix, "var/lib/dpkg");
        ensureDirectory(dpkgDirectory);
        File dpkgStatus = new File(dpkgDirectory, "status");
        if (!dpkgStatus.isFile()) {
            try (FileOutputStream status = new FileOutputStream(dpkgStatus)) {
                // An empty dpkg database is valid; package installs fill it.
            }
        }

        File aptConfig = new File(aptDirectory, APT_CONFIG_NAME);
        String prefix = config.prefix.getAbsolutePath();
        String architecture = System.getProperty("os.arch", "");
        if (architecture.contains("aarch64") || architecture.contains("arm64")) {
            architecture = "aarch64";
        } else if (architecture.contains("86_64") || architecture.contains("amd64")) {
            architecture = "x86_64";
        }
        String content =
            (architecture.isEmpty() ? "" : "Apt::Architecture \"" + architecture + "\";\n") +
            "Acquire::Retries \"2\";\n" +
            "Dir \"" + prefix + "\";\n" +
            // FindFile() joins every ancestor value, so sub-keys must be
            // relative to their parent: "var/lib/apt" + "lists/". Setting
            // Dir::State::status/lists to full prefix-relative paths would
            // resolve to .../var/lib/apt/var/lib/... and make debSystem::Score
            // fail with "Unable to determine a suitable packaging system
            // type". Left unset, apt derives the status file as
            // <Dir>/var/lib/dpkg/status exactly like Termux.
            "Dir::State \"var/lib/apt\";\n" +
            "Dir::Cache \"var/cache/apt\";\n" +
            "Dir::Cache::archives \"archives\";\n" +
            "Dir::Etc \"etc/apt\";\n" +
            "Dir::Etc::sourcelist \"sources.list\";\n" +
            "Dir::Etc::sourceparts \"sources.list.d\";\n" +
            "Dir::Etc::main \"apt.conf\";\n" +
            "Dir::Etc::parts \"apt.conf.d\";\n" +
            "Dir::Log \"var/log/apt\";\n" +
            "Dir::Bin::dpkg \"bin/dpkg\";\n" +
            "Dir::Bin::methods \"lib/apt/methods\";\n";
        try (FileOutputStream output = new FileOutputStream(aptConfig)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create runtime directory: " + directory);
        }
    }

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

        // The official bootstrap is built for Termux's default package path.
        // termux-exec supports relocating that path when these variables are
        // exported by the host app. Without them, scripts retain
        // /data/data/com.termux/files/usr in their shebangs and fail with
        // "bad interpreter" in this standalone package.
        File appData = root.getParentFile() == null ? root : root.getParentFile().getParentFile();
        String appDataPath = appData == null ? root.getAbsolutePath() : appData.getAbsolutePath();
        String legacyAppDataPath = appDataPath.replace("/data/user/0/", "/data/data/");
        environment.put("TERMUX_APP__DATA_DIR", appDataPath);
        environment.put("TERMUX_APP__LEGACY_DATA_DIR", legacyAppDataPath);
        environment.put("TERMUX__ROOTFS", root.getAbsolutePath());
        environment.put("TERMUX__PREFIX", config.prefix.getAbsolutePath());
        environment.put("TERMUX__PROJECT_DIR", root.getAbsolutePath());
        environment.put("DPKG_ADMINDIR", new File(config.prefix, "var/lib/dpkg").getAbsolutePath());
        File aptConfig = new File(config.prefix, "etc/apt/" + APT_CONFIG_NAME);
        if (aptConfig.isFile()) environment.put("APT_CONFIG", aptConfig.getAbsolutePath());
        environment.put("XDG_DATA_HOME", new File(root, "xdg/data").getAbsolutePath());
        environment.put("XDG_CONFIG_HOME", new File(root, "xdg/config").getAbsolutePath());
        environment.put("XDG_STATE_HOME", new File(root, "xdg/state").getAbsolutePath());
        environment.put("XDG_CACHE_HOME", new File(root, "xdg/cache").getAbsolutePath());

        String existingLibraries = environment.get("LD_LIBRARY_PATH");
        environment.put("LD_LIBRARY_PATH", config.libraryDirectory.getAbsolutePath()
            + (existingLibraries == null || existingLibraries.isEmpty()
                ? "" : File.pathSeparator + existingLibraries));

        // Termux packages use this hook to translate Linux-style executable
        // paths such as /bin/sh and /usr/bin/env to the private PREFIX. Newer
        // bootstraps use the linker-aware variant; older ones use the original
        // library name. Only export a path that is actually present, otherwise
        // Android's linker rejects every child process at startup.
        StringBuilder preload = new StringBuilder();
        if (config.nativeLibraryDirectory != null) {
            File harnessExec = new File(config.nativeLibraryDirectory, "libharness-exec.so");
            if (harnessExec.isFile()) preload.append(harnessExec.getAbsolutePath());
        }
        File termuxExec = new File(config.libraryDirectory, "libtermux-exec-ld-preload.so");
        if (!termuxExec.isFile()) {
            termuxExec = new File(config.libraryDirectory, "libtermux-exec.so");
        }
        if (termuxExec.isFile()) {
            if (preload.length() > 0) preload.append(File.pathSeparator);
            preload.append(termuxExec.getAbsolutePath());
        }
        String existingPreload = environment.get("LD_PRELOAD");
        if (existingPreload != null && !existingPreload.isEmpty()) {
            if (preload.length() > 0) preload.append(File.pathSeparator);
            preload.append(existingPreload);
        }
        if (preload.length() > 0) environment.put("LD_PRELOAD", preload.toString());

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
