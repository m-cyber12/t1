package io.mcyber12.codingharness.core;

import android.content.Context;

import java.io.File;

/** App-private paths used by the harness runtime. */
public final class HarnessPaths {
    private final File root;
    private final File workspace;
    private final File home;
    private final File bin;
    private final File lib;
    private final File tmp;
    private final File xdgData;
    private final File xdgConfig;
    private final File xdgState;
    private final File xdgCache;

    private HarnessPaths(File root) {
        this.root = root;
        workspace = new File(root, "workspace");
        home = new File(root, "home");
        bin = new File(root, "bin");
        lib = new File(root, "lib");
        tmp = new File(root, "tmp");
        xdgData = new File(root, "xdg/data");
        xdgConfig = new File(root, "xdg/config");
        xdgState = new File(root, "xdg/state");
        xdgCache = new File(root, "xdg/cache");
    }

    public static HarnessPaths forApp(Context context) {
        HarnessPaths paths = new HarnessPaths(new File(context.getFilesDir(), "coding-harness"));
        paths.ensure();
        return paths;
    }

    public void ensure() {
        mkdir(root);
        mkdir(workspace);
        mkdir(home);
        mkdir(bin);
        mkdir(lib);
        mkdir(tmp);
        mkdir(xdgData);
        mkdir(xdgConfig);
        mkdir(xdgState);
        mkdir(xdgCache);
    }

    private static void mkdir(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create harness directory: " + directory);
        }
        directory.setReadable(true, true);
        directory.setWritable(true, true);
        directory.setExecutable(true, true);
    }

    public File root() { return root; }
    public File workspace() { return workspace; }
    public File home() { return home; }
    public File bin() { return bin; }
    public File lib() { return lib; }
    public File tmp() { return tmp; }
    public File xdgData() { return xdgData; }
    public File xdgConfig() { return xdgConfig; }
    public File xdgState() { return xdgState; }
    public File xdgCache() { return xdgCache; }

    public String defaultShell() {
        File shell = new File("/system/bin/sh");
        return shell.exists() ? shell.getAbsolutePath() : "sh";
    }
}
