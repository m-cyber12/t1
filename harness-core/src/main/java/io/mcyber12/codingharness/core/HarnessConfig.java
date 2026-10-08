package io.mcyber12.codingharness.core;

import android.content.Context;

import java.io.File;

/**
 * Configuration for one coding-harness process.
 *
 * <p>The configuration intentionally accepts paths from the host application.
 * The standalone app uses app-private paths, while the future OpenCode app can
 * pass its existing workspace, home and tool directories without copying this
 * module's process code.</p>
 */
public final class HarnessConfig {
    public final File workspace;
    public final File home;
    public final File toolDirectory;
    public final File libraryDirectory;
    public final String shellPath;
    public final int transcriptRows;

    private HarnessConfig(Builder builder) {
        this.workspace = builder.workspace;
        this.home = builder.home;
        this.toolDirectory = builder.toolDirectory;
        this.libraryDirectory = builder.libraryDirectory;
        this.shellPath = builder.shellPath;
        this.transcriptRows = builder.transcriptRows;
    }

    public static Builder builder(Context context) {
        HarnessPaths paths = HarnessPaths.forApp(context);
        return new Builder()
            .workspace(paths.workspace())
            .home(paths.home())
            .toolDirectory(paths.bin())
            .libraryDirectory(paths.lib())
            .shellPath(paths.defaultShell());
    }

    public static final class Builder {
        private File workspace;
        private File home;
        private File toolDirectory;
        private File libraryDirectory;
        private String shellPath = "/system/bin/sh";
        private int transcriptRows = 2_000;

        public Builder workspace(File value) { workspace = value; return this; }
        public Builder home(File value) { home = value; return this; }
        public Builder toolDirectory(File value) { toolDirectory = value; return this; }
        public Builder libraryDirectory(File value) { libraryDirectory = value; return this; }
        public Builder shellPath(String value) { shellPath = value; return this; }
        public Builder transcriptRows(int value) { transcriptRows = value; return this; }

        public HarnessConfig build() {
            if (workspace == null) throw new IllegalStateException("workspace is required");
            if (home == null) throw new IllegalStateException("home is required");
            if (toolDirectory == null) throw new IllegalStateException("toolDirectory is required");
            if (libraryDirectory == null) throw new IllegalStateException("libraryDirectory is required");
            if (shellPath == null || shellPath.isEmpty()) throw new IllegalStateException("shellPath is required");
            if (transcriptRows < 0) throw new IllegalArgumentException("transcriptRows must be >= 0");
            return new HarnessConfig(this);
        }
    }
}
