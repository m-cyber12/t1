package io.mcyber12.codingharness.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(config.workspace);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(HarnessEnvironment.create(config));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "coding-harness-command-reader");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> outputFuture = readerExecutor.submit(() -> readOutput(process));

        boolean completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroy();
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        }

        String output;
        try {
            output = outputFuture.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IOException("Unable to read command output", e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            output = "[command output unavailable after process timeout]\n";
            outputFuture.cancel(true);
        } finally {
            readerExecutor.shutdownNow();
        }

        return new CommandResult(completed ? process.exitValue() : -1, output, !completed);
    }

    private static String readOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
            return text.toString();
        }
    }

    public CommandResult run(String executable, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        if (args != null) {
            for (String arg : args) command.add(arg);
        }
        return run(command, 60_000L);
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
