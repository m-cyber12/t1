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

    /**
     * Many Termux tools are interpreter scripts (npm -> npm-cli.js, pip,
     * gem, ...). Inside a shell their official-prefix shebang
     * (#!/data/data/com.termux/files/usr/bin/...) is rewritten by the
     * libharness-exec preload, but the JVM's ProcessBuilder exec is not
     * covered by that preload and the kernel rejects the missing
     * interpreter with ENOENT ("error=2"). Mirror the preload here: when
     * the resolved executable is a script, exec its translated
     * interpreter with the script path as the first argument, exactly
     * like the kernel would have after relocation.
     */
    private List<String> resolveInterpreterIfScript(List<String> resolvedCommand) {
        List<String> command = resolvedCommand;
        for (int depth = 0; depth < 4; depth++) {
            File executable = new File(command.get(0));
            String shebang = readShebang(executable);
            if (shebang == null) return command;
            String[] parts = shebang.trim().split("\\s+", 2);
            if (parts.length == 0 || parts[0].isEmpty()) return command;
            String interpreter = translateInterpreter(parts[0]);
            List<String> next = new ArrayList<>();
            next.add(interpreter);
            if (parts.length > 1 && !parts[1].trim().isEmpty()) next.add(parts[1].trim());
            next.add(executable.getAbsolutePath());
            next.addAll(command.subList(1, command.size()));
            command = next;
        }
        return command;
    }

    private static String readShebang(File file) {
        try {
            if (!file.isFile()) return null;
            byte[] head = new byte[256];
            int length;
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                length = input.read(head);
            }
            if (length < 3 || head[0] != '#' || head[1] != '!') return null;
            int end = 2;
            while (end < length && head[end] != '\n' && head[end] != '\r') end++;
            return new String(head, 2, end - 2, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Translate an interpreter path out of the official Termux layout. */
    private String translateInterpreter(String interpreter) {
        String prefix = config.prefix.getAbsolutePath();
        String[] officialPrefixes = {
            "/data/data/com.termux/files/usr",
            "/data/user/0/com.termux/files/usr"
        };
        for (String official : officialPrefixes) {
            if (interpreter.equals(official)) return prefix;
            if (interpreter.startsWith(official + "/")) {
                return prefix + interpreter.substring(official.length());
            }
        }
        String[] officialFiles = {
            "/data/data/com.termux/files",
            "/data/user/0/com.termux/files"
        };
        File filesDirectory = config.prefix.getParentFile();
        if (filesDirectory != null) {
            for (String official : officialFiles) {
                if (interpreter.startsWith(official + "/")) {
                    return new File(filesDirectory,
                        interpreter.substring(official.length() + 1)).getAbsolutePath();
                }
            }
        }
        // Android has no /usr/bin or /bin; the harness prefix provides env/sh.
        if (interpreter.equals("/usr/bin/env") || interpreter.equals("/bin/env")) {
            return new File(config.toolDirectory, "env").getAbsolutePath();
        }
        if (interpreter.equals("/bin/sh")) {
            return new File(config.toolDirectory, "sh").getAbsolutePath();
        }
        return interpreter;
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
        resolvedCommand = resolveInterpreterIfScript(resolvedCommand);

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
