package io.mcyber12.codingharness.core;

import android.content.Context;

import java.io.File;

/** App-private Termux-style paths used by the harness runtime. */
public final class HarnessPaths {
    private final File root;
    private final File prefix;
    private final File workspace;
    private final File home;
    private final File tmp;
    private final File bin;
    private final File lib;
    private final File xdgData;
    private final File xdgConfig;
    private final File xdgState;
    private final File xdgCache;

    private HarnessPaths(File root) {
        this.root = root;
        // Termux packages expect PREFIX/bin, PREFIX/lib, PREFIX/etc and
        // PREFIX/var. Keeping this layout means package scripts work without
        // rewriting paths when this module is integrated into the main app.
        prefix = new File(root, "usr");
        workspace = new File(root, "workspace");
        home = new File(root, "home");
        tmp = new File(prefix, "tmp");
        bin = new File(prefix, "bin");
        lib = new File(prefix, "lib");
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
        mkdir(prefix);
        mkdir(workspace);
        mkdir(home);
        mkdir(tmp);
        mkdir(bin);
        mkdir(lib);
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
    public File prefix() { return prefix; }
    public File workspace() { return workspace; }
    public File home() { return home; }
    public File tmp() { return tmp; }
    public File bin() { return bin; }
    public File lib() { return lib; }
    public File xdgData() { return xdgData; }
    public File xdgConfig() { return xdgConfig; }
    public File xdgState() { return xdgState; }
    public File xdgCache() { return xdgCache; }

    public String defaultShell() {
        File bash = new File(bin, "bash");
        if (bash.isFile() && bash.canExecute()) return bash.getAbsolutePath();
        File shell = new File("/system/bin/sh");
        return shell.exists() ? shell.getAbsolutePath() : "sh";
    }
}
