package io.mcyber12.codingharness.core.packs;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "Is this pack actually here?" — asked of the prefix, not of a database.
 *
 * <p>Two channels install a pack (a store module unpacks it, apt scripts it),
 * and both end with executables in the prefix. Checking the commands is
 * therefore the one question whose answer does not depend on which channel ran,
 * and it is also the answer a user cares about: a pack whose entry exists but
 * whose binary does not is not installed.</p>
 */
public final class PackCommands {

    private PackCommands() {}

    /** The pack's commands that are present and executable in {@code bin}. */
    public static List<String> presentIn(File bin, Pack pack) {
        return presentIn(bin, pack.requiredCommands);
    }

    /** The pack's commands that are missing or not executable in {@code bin}. */
    public static List<String> missingFrom(File bin, Pack pack) {
        return missingFrom(bin, pack.requiredCommands);
    }

    /**
     * Which of {@code commands} are present and executable.
     *
     * <p>The overload that takes a list exists because a pack's contents are not
     * always fixed: a build may ship a smaller payload than the pack advertises
     * (CI ships make and pkg-config, a release ships those plus clang and cmake).
     * What must be verified is what actually arrived, not what was promised.</p>
     */
    public static List<String> presentIn(File bin, List<String> commands) {
        List<String> present = new ArrayList<>();
        if (bin == null || commands == null) return present;
        for (String command : commands) {
            File executable = new File(bin, command);
            if (executable.isFile() && executable.canExecute()) present.add(command);
        }
        return present;
    }

    /** Which of {@code commands} are missing or not executable. */
    public static List<String> missingFrom(File bin, List<String> commands) {
        List<String> missing = new ArrayList<>();
        if (commands == null) return missing;
        List<String> present = presentIn(bin, commands);
        for (String command : commands) {
            if (!present.contains(command)) missing.add(command);
        }
        return missing;
    }

    /**
     * The executables a payload carries, from its {@code path<TAB>library} lines.
     *
     * <p>Only {@code bin/…} entries become command names: those are the things a
     * user types, and they are what "installed" has to mean. Duplicates collapse,
     * because a payload may link the same tool twice for different names.</p>
     */
    public static List<String> commandsIn(List<String> execLines) {
        List<String> commands = new ArrayList<>();
        for (String raw : execLines == null ? Collections.<String>emptyList() : execLines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String path = line.split("\\s+")[0];
            if (!path.startsWith("bin/")) continue;
            String name = path.substring("bin/".length());
            if (name.isEmpty() || name.contains("/")) continue;
            if (!commands.contains(name)) commands.add(name);
        }
        return commands;
    }

    public static boolean present(File bin, Pack pack) {
        return missingFrom(bin, pack).isEmpty();
    }
}
