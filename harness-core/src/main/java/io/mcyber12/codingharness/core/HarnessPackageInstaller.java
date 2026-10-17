package io.mcyber12.codingharness.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Installs the coding-language/tool packages into the Termux-style prefix. */
public final class HarnessPackageInstaller {
    private static final String MARKER = ".coding-toolchain-installed";

    /**
     * These are package names from the official Termux repository. The list is
     * intentionally a coding harness, not every package available in Termux.
     * Base tools (bash, coreutils, curl, ca-certificates, ...) already ship in
     * the bootstrap, so requesting them again would only slow setup down.
     */
    public static final List<String> CODING_PACKAGES = Collections.unmodifiableList(Arrays.asList(
        "git", "ripgrep", "python", "nodejs", "npm", "perl", "ruby",
        "make", "pkg-config", "clang", "cmake",
        "jq", "tree", "which", "file", "openssh"
    ));

    private static final long FRESH_LIST_AGE_MILLIS = 6 * 60 * 60 * 1000L;

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
            String updateSummary = "skipped (lists fresh)";
            if (!packageListsAreFresh(config)) {
                // Probe how apt-config itself resolves the trusted keyring
                // directory on-device; shown verbatim in the failure dialog.
                String probe = "";
                try {
                    HarnessCommandRunner.CommandResult tp = runner.run(
                        "apt-config", "shell", "TP", "Dir::Etc::TrustedParts/d");
                    probe = ", tp=" + tail(tp.output, 110);
                } catch (Exception ignored) {
                    probe = ", tp=probe-failed";
                }
                HarnessCommandRunner.CommandResult update = runner.run(
                    Arrays.asList("apt-get", "update"), 30 * 60_000L);
                if (!update.isSuccess()) {
                    return new InstallResult(false, "apt-get update failed [" + diagnostics(config)
                        + probe + "]: " + compact(update.output));
                }
                updateSummary = "ok, " + countPackageLists(config) + " lists, " + diagnostics(config)
                    + probe + ": " + tail(update.output, 160);
            }

            // Bring the bootstrap-era base system to the repository state
            // BEFORE installing on top of it. The bootstrap is a snapshot,
            // but the package index is rolling: new packages are built
            // against the CURRENT shared libraries (e.g. cmake 4.x needs a
            // newer libc++_shared than the bundled one exports:
            // __hash_memory), and most Depends: are unversioned, so apt
            // would happily leave the stale runtime in place and produce
            // binaries that cannot link. This mirrors Termux's own
            // post-bootstrap guidance ("pkg upgrade"), and marking it
            // distinctly keeps upgrade failures diagnosable.
            HarnessCommandRunner.CommandResult upgrade = runner.run(
                Arrays.asList("apt-get", "-y", "upgrade"), 45 * 60_000L);
            if (!upgrade.isSuccess()) {
                return new InstallResult(false,
                    "base system upgrade failed (update " + updateSummary + "): "
                        + compact(upgrade.output));
            }
            String upgradeSummary = tail(upgrade.output, 120);

            List<String> installCommand = new ArrayList<>();
            installCommand.add("apt-get");
            installCommand.add("-y");
            installCommand.add("install");
            installCommand.addAll(CODING_PACKAGES);
            HarnessCommandRunner.CommandResult install = runner.run(installCommand, 45 * 60_000L);
            if (!install.isSuccess()) {
                return new InstallResult(false,
                    "install failed (update " + updateSummary + "): " + compact(install.output));
            }

            if (!isCodingToolchainReady(config)) {
                return new InstallResult(false, "package command succeeded but a required executable is missing");
            }
            writeMarker(config);
            return new InstallResult(true, "base upgraded (" + upgradeSummary
                + "); Python, Node/npm, Perl, Ruby and coding tools are ready");
        } catch (IOException e) {
            return new InstallResult(false, "package setup I/O failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new InstallResult(false, "package setup was interrupted");
        }
    }

    /** One-line on-device state check shown in the setup dialog. */
    private static String diagnostics(HarnessConfig config) {
        File aptKey = new File(config.prefix, "bin/apt-key");
        File[] entries = new File(config.prefix, "etc/apt/trusted.gpg.d")
            .listFiles((dir, name) -> name.endsWith(".gpg"));
        int usableKeys = 0;
        if (entries != null) {
            for (File entry : entries) {
                // isFile() follows symlinks: dangling bootstrap links to the
                // official prefix do not count (they caused NO_PUBKEY while
                // a naive name count misleadingly reported all keys present).
                if (entry.isFile() && entry.length() > 0) usableKeys++;
            }
        }
        return "cert " + new File(config.prefix, "etc/tls/cert.pem").length() + "B"
            + ", apt-key " + (aptKey.isFile() ? aptKey.length() + "B" : "MISSING")
            + ", keys " + usableKeys + "/" + (entries == null ? 0 : entries.length);
    }

    private static int countPackageLists(HarnessConfig config) {
        File[] entries = new File(config.prefix, "var/lib/apt/lists").listFiles();
        if (entries == null) return 0;
        int count = 0;
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().contains("_Packages")) count++;
        }
        return count;
    }

    private static String tail(String output, int length) {
        if (output == null) return "";
        String normalized = output.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= length ? normalized : normalized.substring(normalized.length() - length);
    }

    /**
     * A repository index downloaded less than six hours ago does not need to be
     * fetched again, so retries and repairs go straight to the installer.
     */
    private static boolean packageListsAreFresh(HarnessConfig config) {
        File lists = new File(config.prefix, "var/lib/apt/lists");
        File[] entries = lists.listFiles();
        if (entries == null) return false;
        long now = System.currentTimeMillis();
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().contains("_Packages")
                && now - entry.lastModified() < FRESH_LIST_AGE_MILLIS) {
                return true;
            }
        }
        return false;
    }

    private static void writeMarker(HarnessConfig config) {
        File marker = new File(config.prefix.getParentFile(), MARKER);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write("coding-toolchain-v1".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The executable check remains authoritative; the marker is only a fast hint.
        }
    }

    /**
     * Keeps the head, any error-bearing lines (the actual cause usually
     * sits in the middle of a long apt/dpkg log), and the tail (where apt
     * gave up) within a small budget, so failure dialogs stay actionable
     * instead of truncating away the one line that matters.
     */
    private static String compact(String output) {
        if (output == null) return "no output";
        String normalized = output.replace('\r', '\n');
        if (normalized.length() <= 900) return normalized.replace('\n', ' ').trim();
        StringBuilder errors = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String candidate = line.trim();
            String lower = candidate.toLowerCase(java.util.Locale.US);
            if (lower.contains("error") || lower.contains("denied")
                || lower.contains("cannot") || lower.contains("overwrite")
                || lower.contains("no space") || lower.contains("failed")
                || lower.startsWith("e:") || lower.startsWith("dpkg:")) {
                if (errors.length() > 0) errors.append(" | ");
                errors.append(candidate);
                if (errors.length() >= 1100) break;
            }
        }
        String flat = normalized.replace('\n', ' ').trim();
        String head = flat.substring(0, 300);
        String tailText = flat.substring(flat.length() - 300);
        return head + " … " + errors + " … " + tailText;
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
