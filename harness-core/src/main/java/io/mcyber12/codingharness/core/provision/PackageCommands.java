package io.mcyber12.codingharness.core.provision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure builders for the package-manager command lines. No I/O, no state — the
 * service asks for a command and runs it through {@link PackageManager}.
 *
 * <p>Keeping these here (rather than inline in the service) means the exact
 * flags are unit-tested and a port can mirror them one-to-one.</p>
 */
public final class PackageCommands {

    private PackageCommands() {}

    /** Explicit install; tiers are the only source of package names. */
    public static List<String> install(List<String> packages) {
        List<String> command = new ArrayList<>();
        command.add("apt-get");
        command.add("-y");
        command.add("install");
        command.addAll(packages);
        return Collections.unmodifiableList(command);
    }

    /**
     * Purge a pack's own packages. {@code --purge} also drops shipped config
     * files; the packages are named explicitly, so nothing else is removed.
     */
    public static List<String> remove(List<String> packages) {
        List<String> command = new ArrayList<>();
        command.add("apt-get");
        command.add("-y");
        command.add("--purge");
        command.add("remove");
        command.addAll(packages);
        return Collections.unmodifiableList(command);
    }

    /**
     * Drop dependencies nothing needs any more — for the build tools this is
     * where the real win is (libllvm alone is ~200 MB installed). Only
     * packages dpkg marked as automatically installed are candidates, so the
     * core runtime and the bootstrap base system are never touched.
     */
    public static List<String> autoremove() {
        return Collections.unmodifiableList(
            java.util.Arrays.asList("apt-get", "-y", "--purge", "autoremove"));
    }

    /** Whether the removal of this tier would leave required commands behind. */
    public static String describe(List<String> command) {
        StringBuilder out = new StringBuilder();
        for (String part : command) {
            if (out.length() > 0) out.append(' ');
            out.append(part);
        }
        return out.toString();
    }
}
