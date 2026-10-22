package io.mcyber12.codingharness.core.provision;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.mcyber12.codingharness.core.HarnessCommandRunner;
import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.codingharness.core.HarnessSizeReport;

/**
 * Android implementation of {@link PackageManager}: apt/dpkg inside the
 * harness prefix, dpkg's own database as the source of truth for installed
 * packages and sizes.
 *
 * <p>This is the only class in the provisioning module that knows about
 * processes or the filesystem layout. A port supplies its own implementation
 * and every other class here works unchanged.</p>
 */
public final class PrefixPackageManager implements PackageManager {

    private static final long FRESH_LIST_AGE_MILLIS = 6 * 60 * 60 * 1000L;

    private final HarnessConfig config;
    private final HarnessCommandRunner runner;

    public PrefixPackageManager(HarnessConfig config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        this.config = config;
        this.runner = new HarnessCommandRunner(config);
    }

    @Override
    public CommandResult run(List<String> command, long timeoutMillis)
            throws IOException, InterruptedException {
        HarnessCommandRunner.CommandResult result = runner.run(command, timeoutMillis);
        return new CommandResult(command, result.exitCode, result.output);
    }

    @Override
    public Set<String> installedPackages() {
        String status = readStatus();
        return HarnessSizeReport.parseInstalledPackages(status);
    }

    @Override
    public Map<String, Long> installedSizes() {
        Map<String, Long> sizes = HarnessSizeReport.parseInstalledSizes(readStatus());
        return sizes == null ? new LinkedHashMap<String, Long>() : sizes;
    }

    @Override
    public boolean listsAreFresh() {
        File[] entries = new File(config.prefix, "var/lib/apt/lists").listFiles();
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

    /** Number of *_Packages index files apt actually downloaded. */
    public int packageListCount() {
        File[] entries = new File(config.prefix, "var/lib/apt/lists").listFiles();
        if (entries == null) return 0;
        int count = 0;
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().contains("_Packages")) count++;
        }
        return count;
    }

    /** One-line certificate/keyring state for failure dialogs. */
    public String repositoryDiagnostics() {
        File aptKey = new File(config.prefix, "bin/apt-key");
        File[] entries = new File(config.prefix, "etc/apt/trusted.gpg.d")
            .listFiles((dir, name) -> name.endsWith(".gpg"));
        int usableKeys = 0;
        if (entries != null) {
            for (File entry : entries) {
                // isFile() follows symlinks: dangling links do not count.
                if (entry.isFile() && entry.length() > 0) usableKeys++;
            }
        }
        return "cert " + new File(config.prefix, "etc/tls/cert.pem").length() + "B"
            + ", apt-key " + (aptKey.isFile() ? aptKey.length() + "B" : "MISSING")
            + ", keys " + usableKeys + "/" + (entries == null ? 0 : entries.length);
    }

    private String readStatus() {
        try {
            File status = new File(config.prefix, "var/lib/dpkg/status");
            return new String(java.nio.file.Files.readAllBytes(status.toPath()),
                StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Command list helper used by the service for readable messages. */
    static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(' ');
            out.append(part);
        }
        return out.toString();
    }

    static List<String> list(String... parts) {
        List<String> result = new ArrayList<>();
        for (String part : parts) result.add(part);
        return result;
    }
}
