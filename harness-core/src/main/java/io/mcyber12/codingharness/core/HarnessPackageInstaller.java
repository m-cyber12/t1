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
     * Tiered package sets from the official Termux repository.
     *
     * <p>An agentic coding harness (Claude Code / OpenCode class) drives a
     * shell, git and ripgrep, and runs project code — overwhelmingly Python
     * and JavaScript/TypeScript. Only that set is installed by default, so
     * first setup is fast and small. Capabilities that are large but only
     * needed for specific projects are explicit on-demand packs, installable
     * later from the TOOLS dialog (see docs/size-management.md):</p>
     *
     * <ul>
     *   <li>CORE — shell-adjacent VCS/search tools, Python (+pip: only a
     *       Recommends of python, so named explicitly now that recommends
     *       are off), Node/npm, and tiny utilities.</li>
     *   <li>BUILD_TOOLS — clang/cmake/make/pkg-config. libllvm+clang are
     *       the largest single block (~300 MB installed) yet are only
     *       exercised when compiling native code (C/C++ projects, node-gyp,
     *       pip source builds).</li>
     *   <li>EXTRA_LANGUAGES — ruby and perl. No mainstream agent runtime
     *       needs them preinstalled; they serve Ruby/Perl projects.</li>
     * </ul>
     *
     * <p>Base tools (bash, coreutils, curl, ca-certificates, ...) already
     * ship in the bootstrap, so requesting them again would only slow
     * setup down.</p>
     */
    public static final List<String> CORE_PACKAGES = Collections.unmodifiableList(Arrays.asList(
        "git", "ripgrep", "python", "python-pip", "nodejs", "npm",
        "jq", "tree", "which", "file", "openssh"
    ));

    public static final List<String> BUILD_TOOL_PACKAGES = Collections.unmodifiableList(Arrays.asList(
        "make", "pkg-config", "clang", "cmake"
    ));

    public static final List<String> EXTRA_LANGUAGE_PACKAGES = Collections.unmodifiableList(Arrays.asList(
        "perl", "ruby"
    ));

    /** Backwards-compatible alias: the default (core) install set. */
    public static final List<String> CODING_PACKAGES = CORE_PACKAGES;

    /** Commands that prove each tier actually works once installed. */
    private static final String[] CORE_COMMANDS =
        {"bash", "git", "rg", "python", "pip", "node", "npm", "jq", "curl"};
    private static final String[] BUILD_COMMANDS = {"clang", "cmake", "make", "pkg-config"};
    private static final String[] EXTRA_LANGUAGE_COMMANDS = {"perl", "ruby"};

    private static final long FRESH_LIST_AGE_MILLIS = 6 * 60 * 60 * 1000L;

    private HarnessPackageInstaller() {}

    public static boolean isCodingToolchainReady(HarnessConfig config) {
        return commandsReady(config, CORE_COMMANDS);
    }

    public static boolean isBuildToolchainReady(HarnessConfig config) {
        return commandsReady(config, BUILD_COMMANDS);
    }

    public static boolean isExtraLanguagesReady(HarnessConfig config) {
        return commandsReady(config, EXTRA_LANGUAGE_COMMANDS);
    }

    private static boolean commandsReady(HarnessConfig config, String[] commands) {
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
            // A previous attempt may have died mid-transaction (e.g. the
            // device-only symlink EACCES fixed in 0.30.0), leaving packages
            // unpacked-but-unconfigured; dpkg then refuses further work
            // until they are configured. Configure pending packages first —
            // a quick no-op on a clean system. Same dpkg force options the
            // apt config carries, passed explicitly for this direct call.
            try {
                runner.run(Arrays.asList("dpkg", "--force-script-chrootless",
                    "--force-confold", "--configure", "-a"), 30 * 60_000L);
            } catch (Exception ignored) {
                // Best effort only; the upgrade below surfaces real errors.
            }

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
            installCommand.addAll(CORE_PACKAGES);
            HarnessCommandRunner.CommandResult install = runner.run(installCommand, 45 * 60_000L);
            if (!install.isSuccess()) {
                return new InstallResult(false,
                    "install failed (update " + updateSummary + "): " + compact(install.output));
            }

            if (!isCodingToolchainReady(config)) {
                return new InstallResult(false, "package command succeeded but a required executable is missing");
            }
            String hygiene = applySizeHygiene(runner, config);
            writeMarker(config);
            return new InstallResult(true, "base upgraded (" + upgradeSummary
                + "); core tools ready: Python/pip, Node/npm, git, ripgrep (" + hygiene + ")");
        } catch (IOException e) {
            return new InstallResult(false, "package setup I/O failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new InstallResult(false, "package setup was interrupted");
        }
    }

    /**
     * Installs an optional package pack on demand (Build tools / Extra
     * languages from the TOOLS dialog). Same pipeline as core setup —
     * update if the index is stale, install, verify the tier's commands
     * really execute, then reclaim cache/payload bytes. Calling it for an
     * already-installed pack is a fast no-op.
     */
    public static InstallResult installPackages(HarnessConfig config, List<String> packages,
                                                String[] requiredCommands, String label) {
        if (commandsReady(config, requiredCommands)) {
            return new InstallResult(true, label + " already installed");
        }
        try {
            HarnessCommandRunner runner = new HarnessCommandRunner(config);
            if (!packageListsAreFresh(config)) {
                HarnessCommandRunner.CommandResult update = runner.run(
                    Arrays.asList("apt-get", "update"), 30 * 60_000L);
                if (!update.isSuccess()) {
                    return new InstallResult(false, label + ": apt-get update failed: "
                        + compact(update.output));
                }
            }
            List<String> installCommand = new ArrayList<>();
            installCommand.add("apt-get");
            installCommand.add("-y");
            installCommand.add("install");
            installCommand.addAll(packages);
            HarnessCommandRunner.CommandResult install = runner.run(installCommand, 45 * 60_000L);
            if (!install.isSuccess()) {
                return new InstallResult(false, label + " install failed: " + compact(install.output));
            }
            if (!commandsReady(config, requiredCommands)) {
                return new InstallResult(false,
                    label + ": install finished but a required executable is missing");
            }
            String hygiene = applySizeHygiene(runner, config);
            return new InstallResult(true, label + " installed (" + hygiene + ")");
        } catch (IOException e) {
            return new InstallResult(false, label + " I/O failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new InstallResult(false, label + " install was interrupted");
        }
    }

    public static InstallResult installBuildTools(HarnessConfig config) {
        return installPackages(config, BUILD_TOOL_PACKAGES, BUILD_COMMANDS, "Build tools (clang/cmake)");
    }

    public static InstallResult installExtraLanguages(HarnessConfig config) {
        return installPackages(config, EXTRA_LANGUAGE_PACKAGES, EXTRA_LANGUAGE_COMMANDS,
            "Extra languages (perl/ruby)");
    }

    /**
     * Reclaims the two kinds of bytes that are not tools at all:
     * <ul>
     *   <li>apt's .deb download cache ({@code var/cache/apt/archives}) —
     *       after a full setup it duplicates the entire download;</li>
     *   <li>non-runtime package payload (manuals, docs, translations,
     *       bytecode caches) removed by {@link #pruneNonRuntimePayload}.</li>
     * </ul>
     * Returns a short human summary for the setup result / dialogs.
     */
    private static String applySizeHygiene(HarnessCommandRunner runner, HarnessConfig config) {
        long cacheBefore = HarnessSizeReport.directoryBytes(
            new File(config.prefix, "var/cache/apt/archives"));
        try {
            runner.run(Arrays.asList("apt-get", "clean"), 5 * 60_000L);
        } catch (Exception ignored) {
            // The Java-side prune below still reclaims the payload bytes.
        }
        long pruned = pruneNonRuntimePayload(config.prefix);
        long cacheAfter = HarnessSizeReport.directoryBytes(
            new File(config.prefix, "var/cache/apt/archives"));
        long reclaimed = Math.max(0L, cacheBefore - cacheAfter) + pruned;
        return "storage hygiene reclaimed " + HarnessSizeReport.formatBytes(reclaimed)
            + "; " + HarnessSizeReport.totalInstalledSize(config);
    }

    /**
     * Deletes package payload an agent runtime never reads: man pages,
     * documentation, gtk-doc, texinfo and locale catalogs under share/,
     * plus __pycache__ bytecode caches inside the Python stdlib (they are
     * regenerated on demand). Deliberately conservative — binaries,
     * libraries, headers, stdlib code and user site-packages are kept, so
     * nothing installed stops working; a package reinstall restores the
     * pruned files. Returns the number of bytes deleted. This is the same
     * technique Debian's slim images use via dpkg path-exclude, applied
     * post-install where it can be measured and reported.
     */
    static long pruneNonRuntimePayload(File prefix) {
        long freed = 0L;
        File share = new File(prefix, "share");
        for (String name : new String[] {"man", "doc", "gtk-doc", "info", "locale"}) {
            freed += deleteRecursively(new File(share, name));
        }
        File lib = new File(prefix, "lib");
        File[] pythonDirs = lib.listFiles((dir, name) -> name.startsWith("python3."));
        if (pythonDirs != null) {
            for (File pythonDir : pythonDirs) {
                freed += deletePythonCaches(pythonDir);
            }
        }
        return freed;
    }

    private static long deletePythonCaches(File directory) {
        long freed = 0L;
        if (directory == null || !directory.isDirectory()) return 0L;
        if ("site-packages".equals(directory.getName())) return 0L; // user space
        File[] children = directory.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            if (child.isDirectory() && "__pycache__".equals(child.getName())) {
                freed += deleteRecursively(child);
            } else if (child.isDirectory()) {
                freed += deletePythonCaches(child);
            }
        }
        return freed;
    }

    private static long deleteRecursively(File file) {
        if (file == null || !file.exists()
            && !java.nio.file.Files.isSymbolicLink(file.toPath())) return 0L;
        long bytes = 0L;
        if (!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) bytes += deleteRecursively(child);
            }
        } else {
            bytes = file.length();
        }
        if (file.delete()) return bytes;
        return 0L;
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
