package io.mcyber12.codingharness.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Installs the coding-language/tool packages into the Termux-style prefix. */
public final class HarnessPackageInstaller {
    private static final String MARKER = ".coding-toolchain-installed";

    /**
     * These are package names from the official Termux repository. The list is
     * intentionally a coding harness, not every package available in Termux.
     */
    public static final List<String> CODING_PACKAGES = Collections.unmodifiableList(Arrays.asList(
        "bash", "coreutils", "findutils", "grep", "sed", "tar", "gzip", "bzip2", "xz-utils",
        "zip", "unzip", "curl", "ca-certificates", "openssl", "git", "ripgrep",
        "python", "nodejs", "npm", "perl", "ruby", "make", "pkg-config", "clang", "cmake",
        "jq", "tree", "diffutils", "patch", "procps", "which", "file", "openssh"
    ));

    private HarnessPackageInstaller() {}

    public static boolean isCodingToolchainReady(HarnessConfig config) {
        String[] commands = {"bash", "git", "rg", "python", "node", "npm", "perl", "ruby", "curl"};
        for (String command : commands) {
            File executable = new File(config.toolDirectory, command);
            if (!executable.isFile() || !executable.canExecute()) return false;
        }
        return true;
    }

    public static boolean isMarkedInstalled(HarnessConfig config) {
        return isCodingToolchainReady(config) &&
            new File(config.prefix.getParentFile(), MARKER).isFile();
    }

    /**
     * Install or repair the coding toolchain. This performs network I/O and
     * must be called off the Android main thread.
     */
    public static InstallResult installCodingToolchain(HarnessConfig config) {
        if (isCodingToolchainReady(config)) {
            writeMarker(config);
            return new InstallResult(true, "coding toolchain already present");
        }

        try {
            HarnessCommandRunner runner = new HarnessCommandRunner(config);
            HarnessCommandRunner.CommandResult update = runner.run("apt-get", "update");
            if (!update.isSuccess()) {
                return new InstallResult(false, "apt-get update failed: " + compact(update.output));
            }

            String[] packageArgs = new String[CODING_PACKAGES.size() + 2];
            packageArgs[0] = "-y";
            packageArgs[1] = "install";
            for (int i = 0; i < CODING_PACKAGES.size(); i++) {
                packageArgs[i + 2] = CODING_PACKAGES.get(i);
            }
            HarnessCommandRunner.CommandResult install = runner.run("apt-get", packageArgs);
            if (!install.isSuccess()) {
                return new InstallResult(false, "package installation failed: " + compact(install.output));
            }

            if (!isCodingToolchainReady(config)) {
                return new InstallResult(false, "package command succeeded but a required executable is missing");
            }
            writeMarker(config);
            return new InstallResult(true, "Python, Node/npm, Perl, Ruby and coding tools are ready");
        } catch (IOException e) {
            return new InstallResult(false, "package setup I/O failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new InstallResult(false, "package setup was interrupted");
        }
    }

    private static void writeMarker(HarnessConfig config) {
        File marker = new File(config.prefix.getParentFile(), MARKER);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write("coding-toolchain-v1".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The executable check remains authoritative; the marker is only a fast hint.
        }
    }

    private static String compact(String output) {
        if (output == null) return "no output";
        String normalized = output.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > 240 ? normalized.substring(normalized.length() - 240) : normalized;
    }

    public static final class InstallResult {
        public final boolean success;
        public final String message;

        public InstallResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
