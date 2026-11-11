package io.mcyber12.sealed;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-file integrity data for a sealed payload, in the shape the reference
 * harness uses ({@code runtime-manifest.json}: one digest and size per file).
 *
 * <p>The container is deliberately line-oriented instead of JSON: the payload
 * is produced by a Python build script, applied by Java on device, and will be
 * read by a TypeScript harness after the port, so the format has to be trivial
 * in all three. One record per line, tab-separated:</p>
 *
 * <pre>
 * sha256&lt;TAB&gt;size&lt;TAB&gt;path
 * </pre>
 *
 * <p>{@code path} is prefix-relative and may not be absolute or contain
 * {@code ..}. Blank lines and {@code #} comments are ignored, so the build can
 * explain the file it is writing.</p>
 *
 * <p>The manifest is what turns "the payload extracted" into "the payload is
 * the payload": every extracted byte is hashed while it is written (the data is
 * already in memory at that point, so this costs no extra I/O), and a mismatch
 * is reported instead of silently installed.</p>
 */
public final class RuntimeManifest {

    /** One verified file. */
    public static final class Entry {
        public final String path;
        public final long size;
        public final String sha256;

        Entry(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private final Map<String, Entry> entries;

    private RuntimeManifest(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    public static RuntimeManifest empty() {
        return new RuntimeManifest(new LinkedHashMap<>());
    }

    public int size() { return entries.size(); }

    public boolean isEmpty() { return entries.isEmpty(); }

    public Entry get(String path) { return entries.get(path); }

    public Map<String, Entry> entries() { return entries; }

    /**
     * Parse manifest text, keeping the last record for a duplicated path.
     *
     * <p>Malformed lines are skipped rather than thrown: a manifest is build
     * output, and an install that refuses to start because of one bad line
     * would be worse than an install that verifies everything it understands
     * and reports the count it did not.</p>
     */
    public static RuntimeManifest parse(String text) {
        Map<String, Entry> parsed = new LinkedHashMap<>();
        if (text != null) {
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] pieces = line.split("\t");
                if (pieces.length != 3) continue;
                String sha = pieces[0].trim().toLowerCase();
                if (sha.length() != 64) continue;
                long size;
                try {
                    size = Long.parseLong(pieces[1].trim());
                } catch (NumberFormatException notANumber) {
                    continue;
                }
                String path = normalize(pieces[2]);
                if (path == null) continue;
                parsed.put(path, new Entry(path, size, sha));
            }
        }
        return new RuntimeManifest(parsed);
    }

    public static RuntimeManifest parse(List<String> lines) {
        if (lines == null) return empty();
        StringBuilder joined = new StringBuilder();
        for (String line : lines) joined.append(line).append('\n');
        return parse(joined.toString());
    }

    /** Read a manifest file; an absent or unreadable file is an empty manifest. */
    public static RuntimeManifest read(File manifest) {
        if (manifest == null || !manifest.isFile()) return empty();
        try {
            return parse(new String(Files.readAllBytes(manifest.toPath()),
                StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return empty();
        }
    }

    public static RuntimeManifest read(InputStream input) throws IOException {
        if (input == null) return empty();
        try (InputStream stream = input) {
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Normalise a payload path, or null when it is not safe.
     *
     * <p>Shared with {@link PayloadExtractor} and {@link SealedLayout}, because
     * a path that one of them accepts and another rejects is exactly how a
     * payload ends up escaping its prefix.</p>
     */
    public static String normalize(String path) {
        if (path == null) return null;
        String trimmed = path.trim();
        while (trimmed.startsWith("./")) trimmed = trimmed.substring(2);
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("/") || trimmed.contains("..")) return null;
        return trimmed;
    }

    /** SHA-256 of an already-loaded buffer, lowercase hex. */
    public static String digest(byte[] bytes) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return hex(sha.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }

    /** A streaming digest, for callers that hash while they read. */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }

    public static String hex(byte[] digest) {
        StringBuilder text = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            text.append(Character.forDigit((value >> 4) & 0xf, 16));
            text.append(Character.forDigit(value & 0xf, 16));
        }
        return text.toString();
    }

    /**
     * Check an extracted prefix against this manifest.
     *
     * <p>Used by diagnostics and by the upgrade path; the install itself
     * verifies while writing, which is strictly cheaper.</p>
     */
    public List<String> verify(File prefix) {
        List<String> problems = new ArrayList<>();
        if (prefix == null) return Collections.singletonList("no prefix");
        for (Entry entry : entries.values()) {
            File file = new File(prefix, entry.path);
            if (!file.isFile()) {
                problems.add("missing: " + entry.path);
                continue;
            }
            if (file.length() != entry.size) {
                problems.add("size changed: " + entry.path);
                continue;
            }
            try {
                String actual = digest(Files.readAllBytes(file.toPath()));
                if (!actual.equals(entry.sha256)) problems.add("digest differs: " + entry.path);
            } catch (IOException unreadable) {
                problems.add("unreadable: " + entry.path);
            }
        }
        return problems;
    }
}
