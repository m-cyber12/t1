package io.mcyber12.codingharness.core.exec;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Classifies a file by its first bytes: ELF magic, {@code #!} or neither.
 *
 * <p>Pure Java on purpose — this runs in JVM unit tests, in the bootstrap and
 * in the on-device audit, so it must not touch Android APIs. Only the first
 * few bytes are read, so it is cheap enough to run over a whole prefix.</p>
 */
public final class ExecutableProbe {

    /** {@code \x7fELF}. */
    static final byte[] ELF_MAGIC = {0x7f, (byte) 'E', (byte) 'L', (byte) 'F'};

    private ExecutableProbe() {}

    /** Classify {@code file}; never throws. */
    public static ExecutableKind kindOf(File file) {
        if (file == null || !file.isFile()) return ExecutableKind.UNREADABLE;
        byte[] head = new byte[4];
        int read;
        try (FileInputStream input = new FileInputStream(file)) {
            read = readFully(input, head);
        } catch (IOException e) {
            return ExecutableKind.UNREADABLE;
        }
        return classify(head, read);
    }

    /** Pure classification of an already-read head buffer. */
    public static ExecutableKind classify(byte[] head, int length) {
        if (head == null || length < 2) return ExecutableKind.DATA;
        if (length >= 4
            && head[0] == ELF_MAGIC[0] && head[1] == ELF_MAGIC[1]
            && head[2] == ELF_MAGIC[2] && head[3] == ELF_MAGIC[3]) {
            return ExecutableKind.ELF;
        }
        if (head[0] == '#' && head[1] == '!') return ExecutableKind.SCRIPT;
        return ExecutableKind.DATA;
    }

    /**
     * True when the kind can only run from an exec-allowed location.
     *
     * <p>{@link ExecutableKind#DATA} is readable from anywhere, so it never
     * constrains the packaging.</p>
     */
    public static boolean requiresExecAllowedPath(ExecutableKind kind) {
        return kind == ExecutableKind.ELF || kind == ExecutableKind.SCRIPT;
    }

    private static int readFully(FileInputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int count = input.read(buffer, total, buffer.length - total);
            if (count < 0) break;
            total += count;
        }
        return total;
    }
}
