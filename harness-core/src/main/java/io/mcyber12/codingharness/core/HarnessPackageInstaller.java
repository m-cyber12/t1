package io.mcyber12.codingharness.core;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.mcyber12.codingharness.core.provision.PackageTier;
import io.mcyber12.codingharness.core.provision.Provisioning;
import io.mcyber12.codingharness.core.provision.ProvisioningState;
import io.mcyber12.codingharness.core.provision.StorageHygiene;

/**
 * Stable entry point for package provisioning.
 *
 * <p>The logic lives in {@code core.provision}: a pure planner
 * ({@code ProvisioningPlan}), declarative tiers ({@code PackageTier}), a
 * {@code PackageManager} port and an observable service
 * ({@code Provisioning}). This class only maps that module onto the API the
 * app and tests already use.</p>
 *
 * <p>Services are cached per prefix, mirroring OpenCode's
 * {@code Instance.state()} pattern: one provisioning service per location,
 * never a mutable global.</p>
 */
public final class HarnessPackageInstaller {

    /** Default (core) tier packages — see {@link PackageTier#CORE}. */
    public static final List<String> CORE_PACKAGES = PackageTier.CORE.packages;
    /** On-demand native build toolchain — see {@link PackageTier#BUILD_TOOLS}. */
    public static final List<String> BUILD_TOOL_PACKAGES = PackageTier.BUILD_TOOLS.packages;
    /** Backwards-compatible alias for the default install set. */
    public static final List<String> CODING_PACKAGES = CORE_PACKAGES;

    private static final Map<String, Provisioning.Service> SERVICES = new ConcurrentHashMap<>();

    private HarnessPackageInstaller() {}

    /**
     * The provisioning service for this prefix (created once, then reused)
     * so callers can subscribe to progress instead of polling.
     */
    public static Provisioning.Service service(HarnessConfig config) {
        String key = config.prefix.getAbsolutePath();
        Provisioning.Service existing = SERVICES.get(key);
        if (existing != null) return existing;
        Provisioning.Service created = Provisioning.create(config);
        Provisioning.Service raced = SERVICES.putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    public static boolean isCodingToolchainReady(HarnessConfig config) {
        return service(config).isReady();
    }

    public static boolean isBuildToolchainReady(HarnessConfig config) {
        return commandsPresent(config, PackageTier.BUILD_TOOLS.requiredCommands);
    }

    public static boolean isMarkedInstalled(HarnessConfig config) {
        return isCodingToolchainReady(config)
            && new File(config.prefix.getParentFile(), ".coding-toolchain-installed").isFile();
    }

    /**
     * Installs the default tiers (core). Blocking: call off the main thread.
     */
    public static InstallResult installCodingToolchain(HarnessConfig config) {
        ProvisioningState state = service(config).provision(null);
        return result(state);
    }

    /** Installs the on-demand build toolchain. Blocking. */
    public static InstallResult installBuildTools(HarnessConfig config) {
        ProvisioningState state = service(config).installTier(PackageTier.BUILD_TOOLS.id);
        return result(state);
    }

    private static InstallResult result(ProvisioningState state) {
        String detail = state.detail.isEmpty() ? state.message : state.message + " — " + state.detail;
        return new InstallResult(state.isReady(), detail);
    }

    private static boolean commandsPresent(HarnessConfig config, List<String> commands) {
        for (String command : commands) {
            File executable = new File(config.toolDirectory, command);
            if (!executable.isFile() || !executable.canExecute()) return false;
        }
        return true;
    }

    /** Kept for the size tests; implementation lives in the provision module. */
    static long pruneNonRuntimePayload(File prefix) {
        return StorageHygiene.prune(prefix);
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
