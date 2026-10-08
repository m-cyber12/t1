package io.mcyber12.codingharness.core;

import android.content.Context;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.util.Map;

/**
 * Creates one PTY-backed session using the Termux terminal emulator.
 *
 * <p>This class owns no Android Activity or View. A host app can attach the
 * returned {@link TerminalSession} to any terminal UI, or use it as the
 * process side of a different client.</p>
 */
public final class HarnessSession {
    private final HarnessConfig config;
    private final TerminalSession session;

    private HarnessSession(HarnessConfig config, TerminalSession session) {
        this.config = config;
        this.session = session;
    }

    public static HarnessSession start(
        Context context,
        HarnessConfig config,
        TerminalSessionClient client
    ) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (config == null) throw new IllegalArgumentException("config is required");
        if (client == null) throw new IllegalArgumentException("client is required");

        ensureDirectory(config.workspace);
        ensureDirectory(config.home);
        ensureDirectory(config.toolDirectory);
        ensureDirectory(config.libraryDirectory);

        String shell = config.shellPath;
        if (shell.indexOf('/') >= 0 && !new File(shell).canExecute()) {
            shell = "/system/bin/sh";
        }
        Map<String, String> environment = HarnessEnvironment.create(
            new HarnessConfig.Builder()
                .workspace(config.workspace)
                .home(config.home)
                .toolDirectory(config.toolDirectory)
                .libraryDirectory(config.libraryDirectory)
                .shellPath(shell)
                .transcriptRows(config.transcriptRows)
                .build()
        );

        // TerminalSession uses argv[0] as the process name and creates the
        // PTY through Termux's small native helper. Keep the shell interactive
        // but do not use a login shell that could source an unknown profile.
        String[] args = new String[] { new File(shell).getName(), "-i" };
        TerminalSession terminal = new TerminalSession(
            shell,
            config.workspace.getAbsolutePath(),
            args,
            HarnessEnvironment.toArray(environment),
            config.transcriptRows,
            client
        );
        return new HarnessSession(config, terminal);
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create directory: " + directory);
        }
    }

    public TerminalSession terminalSession() { return session; }
    public HarnessConfig config() { return config; }
    public boolean isRunning() { return session.getPid() > 0 && session.isRunning(); }
    public void stop() {
        // TerminalSession uses pid 0 before its first measured attach. Do not
        // pass that sentinel to kill(2), which would target the process group.
        if (session.getPid() > 0) session.finishIfRunning();
    }
}
