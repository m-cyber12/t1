package io.mcyber12.codingharness.core.provision;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A named, independently installable set of packages — the unit of
 * provisioning policy.
 *
 * <p>Mirrors OpenCode's declarative registries (e.g. {@code LSPServer.Info},
 * {@code Agent.Info}): immutable descriptors listed in one place, consumed by
 * a planner, never hard-coded into install code. Porting note: this becomes
 * {@code ProvisioningTier.Info} in TypeScript with the same fields.</p>
 */
public final class PackageTier {

    /** Descriptor fields named to match OpenCode's {@code Info} convention. */
    public static final class Info {
        public final String id;
        public final String label;
        public final List<String> packages;
        /** Commands that must exist and run before the tier counts as ready. */
        public final List<String> requiredCommands;
        public final boolean enabledByDefault;

        public Info(String id, String label, List<String> packages,
                    List<String> requiredCommands, boolean enabledByDefault) {
            this.id = id;
            this.label = label;
            this.packages = Collections.unmodifiableList(packages);
            this.requiredCommands = Collections.unmodifiableList(requiredCommands);
            this.enabledByDefault = enabledByDefault;
        }
    }

    /**
     * The default agentic toolchain: shell, VCS, code search and the
     * language runtimes a coding agent actually drives (Python with pip,
     * Node with npm, Perl and Ruby), plus small utilities. This is the set
     * installed on first run.
     */
    public static final Info CORE = new Info(
        "core",
        "Core tools",
        Arrays.asList("git", "ripgrep", "python", "python-pip", "nodejs", "npm",
            "perl", "ruby", "jq", "tree", "which", "file", "openssh"),
        Arrays.asList("bash", "git", "rg", "python", "pip", "node", "npm",
            "perl", "ruby", "jq", "curl"),
        true);

    /**
     * Native build toolchain, installed on demand. libllvm (pulled by
     * clang) is the largest single package, so it is never part of the
     * default setup — only projects compiling native code need it.
     */
    public static final Info BUILD_TOOLS = new Info(
        "build-tools",
        "Build tools (clang/cmake)",
        Arrays.asList("make", "pkg-config", "clang", "cmake"),
        Arrays.asList("clang", "cmake", "make", "pkg-config"),
        false);

    private static final List<Info> ALL =
        Collections.unmodifiableList(Arrays.asList(CORE, BUILD_TOOLS));

    private PackageTier() {}

    public static List<Info> all() { return ALL; }

    public static List<Info> defaults() {
        return Collections.singletonList(CORE);
    }

    public static Info byId(String id) {
        for (Info tier : ALL) {
            if (tier.id.equals(id)) return tier;
        }
        throw new IllegalArgumentException("unknown package tier: " + id);
    }
}
