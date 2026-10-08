package io.mcyber12.codingharness.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs one-shot coding tools with the same sandbox as the interactive shell. */
public final class HarnessCommandRunner {
    private final HarnessConfig config;

    public HarnessCommandRunner(HarnessConfig config) {
        this.config = config;
    }

    public CommandResult run(List<String> command, long timeoutMillis) throws IOException, InterruptedException {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("command is empty");
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");

        List<String> resolvedCommand = new ArrayList<>(command);
        String requestedExecutable = resolvedCommand.get(0);
        if (requestedExecutable.indexOf('/') < 0) {
            File bundledExecutable = new File(config.toolDirectory, requestedExecutable);
            if (bundledExecutable.isFile() && bundledExecutable.canExecute()) {
                resolvedCommand.set(0, bundledExecutable.getAbsolutePath());
            }
        }

        HarnessEnvironment.prepare(config);
        ProcessBuilder builder = new ProcessBuilder(resolvedCommand);
        builder.directory(config.workspace);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(HarnessEnvironment.create(config));
        builder.redirectErrorStream(true);

        // Android may close ProcessPipeInputStream as soon as a child exits,
        // which can hide the useful apt/dpkg error behind "Unable to read
        // command output". Redirect to a private file and read it after the
        // process has finished instead of racing the Android pipe cleanup.
        File outputFile = new File(config.prefix, "var/log/harness-command.log");
        File outputDirectory = outputFile.getParentFile();
        if (outputDirectory != null && !outputDirectory.isDirectory()
            && !outputDirectory.mkdirs() && !outputDirectory.isDirectory()) {
            throw new IOException("Unable to create command log directory: " + outputDirectory);
        }
        if (outputFile.exists() && !outputFile.delete()) {
            throw new IOException("Unable to clear command log: " + outputFile);
        }
        builder.redirectOutput(outputFile);

        Process process = builder.start();
        boolean completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroy();
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        }

        String output = readOutput(outputFile);
        return new CommandResult(completed ? process.exitValue() : -1, output, !completed);
    }

    private static String readOutput(File outputFile) {
        try {
            return new String(Files.readAllBytes(outputFile.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[command output read failed: " + e.getMessage() + "]\n";
        }
    }

    public CommandResult run(String executable, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        if (args != null) {
            for (String arg : args) command.add(arg);
        }
        return run(command, 10 * 60_000L);
    }

    public static final class CommandResult {
        public final int exitCode;
        public final String output;
        public final boolean timedOut;

        public CommandResult(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }

        public boolean isSuccess() { return !timedOut && exitCode == 0; }
    }
}
