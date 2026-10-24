package io.mcyber12.codingharness.core.provision;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port for everything effectful provisioning needs from the machine.
 *
 * <p>This is the seam that makes the service testable and portable: the
 * Android implementation wraps the harness prefix ({@link PrefixPackageManager}),
 * while a port to OpenCode implements the same methods over its own shell and
 * filesystem services. No caller ever shells out directly.</p>
 */
public interface PackageManager {

    /** Result of one command, mirroring OpenCode's process-runner shape. */
    final class CommandResult {
        public final List<String> command;
        public final int exitCode;
        public final String output;

        public CommandResult(List<String> command, int exitCode, String output) {
            this.command = command;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public boolean isSuccess() { return exitCode == 0; }
    }

    CommandResult run(List<String> command, long timeoutMillis)
        throws IOException, InterruptedException;

    /** Package names dpkg reports as installed. */
    Set<String> installedPackages();

    /** Installed bytes per package, from dpkg's own database. */
    Map<String, Long> installedSizes();

    /** True when the apt index was refreshed recently enough to reuse. */
    boolean listsAreFresh();
}
