package io.mcyber12.codingharness.core.packs;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An optional pack of tools, delivered as an APK rather than downloaded.
 *
 * <p>On Google Play an app may not fetch executable code at runtime, so an
 * on-demand pack cannot be an {@code apt install}: it has to arrive over the
 * store's own delivery channel. Each pack therefore has two possible sources,
 * and the descriptor names both:</p>
 *
 * <ul>
 *   <li>{@link #moduleName} — the Play Feature Delivery module that carries it
 *       (see {@code pack-buildtools/});</li>
 *   <li>{@link #tierId} — the package tier the apt-based build would install
 *       instead ({@link io.mcyber12.codingharness.core.provision.PackageTier}),
 *       so the installable build keeps working unchanged;</li>
 *   <li>{@link #assetPrefix} — how the module's sealed payload is named inside
 *       the app's assets ({@code pack-buildtools-userland-<abi>.zip}), which is
 *       what lets the base payload and the pack share one prefix.</li>
 * </ul>
 *
 * <p>Mirrors OpenCode's {@code Info} registries, like {@code PackageTier}.</p>
 */
public final class Pack {

    public final String id;
    public final String label;
    /** Play Feature Delivery module name, or null when the pack has none. */
    public final String moduleName;
    /** Package tier installed instead by the apt build, or null. */
    public final String tierId;
    /** Asset name prefix of the pack's sealed payload. */
    public final String assetPrefix;
    /** Commands that must run before the pack counts as installed. */
    public final List<String> requiredCommands;

    public Pack(String id, String label, String moduleName, String tierId,
                String assetPrefix, List<String> requiredCommands) {
        this.id = id;
        this.label = label;
        this.moduleName = moduleName;
        this.tierId = tierId;
        this.assetPrefix = assetPrefix;
        this.requiredCommands = Collections.unmodifiableList(requiredCommands);
    }

    /**
     * The native build toolchain. `clang` pulls `libllvm`, the largest package in
     * the repository, so it is never part of a default install.
     */
    public static final Pack BUILD_TOOLS = new Pack(
        "build-tools",
        "Build tools (clang, cmake, make)",
        "pack_buildtools",
        io.mcyber12.codingharness.core.provision.PackageTier.BUILD_TOOLS.id,
        "pack-buildtools-",
        Arrays.asList("make", "pkg-config", "clang", "cmake"));

    private static final List<Pack> ALL =
        Collections.unmodifiableList(Arrays.asList(BUILD_TOOLS));

    public static List<Pack> all() { return ALL; }

    public static Pack byId(String id) {
        for (Pack pack : ALL) {
            if (pack.id.equals(id)) return pack;
        }
        throw new IllegalArgumentException("unknown pack: " + id);
    }
}
