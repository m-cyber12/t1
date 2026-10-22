package io.mcyber12.codingharness.core.provision;

import java.io.File;

/**
 * Reclaims storage that is not part of any tool: apt's .deb download cache,
 * package manuals/docs/translations, and Python bytecode caches.
 *
 * <p>Conservative by construction — binaries, libraries, headers, stdlib
 * modules and user site-packages are never touched, so nothing installed
 * stops working; a package reinstall restores anything pruned.</p>
 */
public final class StorageHygiene {

    private StorageHygiene() {}

    /**
     * Deletes non-runtime payload below {@code prefix} and returns the bytes
     * freed.
     */
    public static long prune(File prefix) {
        long freed = 0L;
        File share = new File(prefix, "share");
        for (String name : new String[] {"man", "doc", "gtk-doc", "info", "locale"}) {
            freed += deleteRecursively(new File(share, name));
        }
        File lib = new File(prefix, "lib");
        File[] pythonDirs = lib.listFiles((dir, name) -> name.startsWith("python3."));
        if (pythonDirs != null) {
            for (File pythonDir : pythonDirs) {
                freed += deletePythonCaches(pythonDir);
            }
        }
        return freed;
    }

    private static long deletePythonCaches(File directory) {
        if (directory == null || !directory.isDirectory()) return 0L;
        if ("site-packages".equals(directory.getName())) return 0L; // user space
        File[] children = directory.listFiles();
        if (children == null) return 0L;
        long freed = 0L;
        for (File child : children) {
            if (child.isDirectory() && "__pycache__".equals(child.getName())) {
                freed += deleteRecursively(child);
            } else if (child.isDirectory()) {
                freed += deletePythonCaches(child);
            }
        }
        return freed;
    }

    static long deleteRecursively(File file) {
        if (file == null) return 0L;
        boolean link = java.nio.file.Files.isSymbolicLink(file.toPath());
        if (!file.exists() && !link) return 0L;
        long bytes = 0L;
        if (!link && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) bytes += deleteRecursively(child);
            }
        } else {
            bytes = file.length();
        }
        return file.delete() ? bytes : 0L;
    }
}
