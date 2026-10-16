package io.mcyber12.codingharness.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the Termux-compatible environment exposed to coding tools. */
public final class HarnessEnvironment {
    private static final String APT_CONFIG_NAME = "harness-apt.conf";

    /** Public keys from termux-packages' termux-keyring (all but the pacman key). */
    private static final String[] TERMUX_KEYRING_KEYS = {
        "2096779623.gpg", "agnostic-apollo.gpg", "grimler.gpg", "kcubeterm.gpg",
        "landfillbaby.gpg", "mradityaalok.gpg", "termux-autobuilds.gpg",
        "thunder-coding.gpg"
    };

    private HarnessEnvironment() {}

    /**
     * Creates the small relocation config needed by apt and dpkg. The
     * official Termux binaries use a fixed build-time prefix, so package
     * management must be pointed at this app's private prefix explicitly.
     */
    public static void prepare(HarnessConfig config) throws IOException {
        File aptDirectory = new File(config.prefix, "etc/apt");
        if (!aptDirectory.isDirectory() && !aptDirectory.mkdirs() && !aptDirectory.isDirectory()) {
            throw new IOException("Unable to create apt configuration directory: " + aptDirectory);
        }
        ensureDirectory(new File(aptDirectory, "apt.conf.d"));
        ensureDirectory(new File(aptDirectory, "sources.list.d"));
        ensureDirectory(new File(aptDirectory, "trusted.gpg.d"));

        // Without at least one deb line, apt-get update succeeds while
        // fetching nothing and every install then reports "Unable to locate
        // package". The official bootstrap ships this file; recreate it if a
        // partial extraction ever left it missing or empty.
        // The managed sources line pins the repository signing key with
        // Signed-By. Without it, apt's gpgv method asks apt-key to merge
        // etc/apt/trusted.gpg.d, which first resolves Dir::Etc::TrustedParts
        // through a nested `apt-config shell` call inside a dumped temp
        // config; on-device that resolution fell back to the compiled-in
        // Termux path, merged zero keys and failed with
        // "NO_PUBKEY 5A897D96E57CF20C" even though the key was installed.
        // With an absolute Signed-By path the gpgv method passes the key
        // file to apt-key as an explicit --keyring instead. Rewrite any
        // earlier sources.list that lacks it so existing installs upgrade.
        String signingKey = new File(aptDirectory, "trusted.gpg.d/termux-autobuilds.gpg")
            .getAbsolutePath();
        String managedSources = "deb [signed-by=" + signingKey + "] "
            + "https://packages-cf.termux.dev/apt/termux-main/ stable main\n";
        File sourcesList = new File(aptDirectory, "sources.list");
        boolean needsSources = true;
        if (sourcesList.isFile() && sourcesList.length() > 0) {
            try {
                String current = new String(java.nio.file.Files.readAllBytes(sourcesList.toPath()),
                    StandardCharsets.UTF_8);
                needsSources = !current.contains("signed-by=");
            } catch (IOException ignored) {
                needsSources = true;
            }
        }
        if (needsSources) {
            try (FileOutputStream sources = new FileOutputStream(sourcesList)) {
                sources.write(managedSources.getBytes(StandardCharsets.UTF_8));
            }
        }

        // The bootstrap does not ship ca-certificates, and Termux's OpenSSL
        // has the official cert store path compiled in. Without a readable
        // bundle every TLS handshake fails certificate verification and apt
        // cannot download any index, so install a standard Mozilla bundle
        // (bundled as a Java resource) and let create() export SSL_CERT_FILE.
        File certBundle = new File(config.prefix, "etc/tls/cert.pem");
        try {
            if (java.nio.file.Files.isSymbolicLink(certBundle.toPath())) {
                java.nio.file.Files.delete(certBundle.toPath());
            }
        } catch (IOException ignored) {
            // Fall through to the write attempt below.
        }
        if (!certBundle.isFile()) {
            ensureDirectory(certBundle.getParentFile());
            try (InputStream bundle = HarnessEnvironment.class.getResourceAsStream("cacert.pem")) {
                if (bundle != null) {
                    try (FileOutputStream out = new FileOutputStream(certBundle)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = bundle.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                }
            }
        }
        // The bootstrap's etc/apt/trusted.gpg.d is EMPTY: termux-keyring only
        // creates its symlinks when the .deb is built/installed, so a raw
        // bootstrap extraction has no keys and apt rejects the signed
        // InRelease ("repository is not signed"). Install the bundled
        // termux-keyring public keys as regular files, mirroring
        // packages/termux-keyring/build.sh (which links every key except
        // termux-pacman.gpg into trusted.gpg.d).
        File trustedParts = new File(aptDirectory, "trusted.gpg.d");
        ensureDirectory(trustedParts);
        File sharedKeyring = new File(config.prefix, "share/termux-keyring");
        ensureDirectory(sharedKeyring);
        for (String key : TERMUX_KEYRING_KEYS) {
            copyResourceIfMissing("termux-keyring/" + key, new File(trustedParts, key));
            copyResourceIfMissing("termux-keyring/" + key, new File(sharedKeyring, key));
        }

        // apt 2.8.1's ExecGPGV executes Dir::Bin::apt-key, whose compiled-in
        // default is the official prefix path that does not exist here
        // ("Couldn't execute /data/data/com.termux/files/usr/bin/apt-key").
        // The config below points it at the relocated binary; also rewrite
        // the script's own shebang (which names the official /bin/sh) so
        // execution does not depend on termux-exec shebang interception.
        rewriteAptKeyShebang(config);

        ensureDirectory(new File(config.prefix, "var/lib/apt/lists/partial"));
        ensureDirectory(new File(config.prefix, "var/cache/apt/archives/partial"));
        ensureDirectory(new File(config.prefix, "var/log/apt"));
        ensureDpkgRootfsLink(config);

        // The deb packaging system is auto-detected by apt only when a dpkg
        // status database and the dpkg binary exist. Never set
        // "Apt::System" here: selecting a system explicitly can pick the EDSP
        // planner interface, which forces APT::Get::Simulate and redirects the
        // status/list databases to /dev/null, so nothing would be installed.
        File dpkgDirectory = new File(config.prefix, "var/lib/dpkg");
        ensureDirectory(dpkgDirectory);
        File dpkgStatus = new File(dpkgDirectory, "status");
        if (!dpkgStatus.isFile()) {
            try (FileOutputStream status = new FileOutputStream(dpkgStatus)) {
                // An empty dpkg database is valid; package installs fill it.
            }
        }

        File aptConfig = new File(aptDirectory, APT_CONFIG_NAME);
        String prefix = config.prefix.getAbsolutePath();
        String architecture = System.getProperty("os.arch", "");
        if (architecture.contains("aarch64") || architecture.contains("arm64")) {
            architecture = "aarch64";
        } else if (architecture.contains("86_64") || architecture.contains("amd64")) {
            architecture = "x86_64";
        }
        String content =
            (architecture.isEmpty() ? "" : "Apt::Architecture \"" + architecture + "\";\n") +
            "Acquire::Retries \"2\";\n" +
            // apt's https method loads CA certificates from this option (or a
            // compiled-in default pointing at the official prefix). Give it
            // the relocated bundle explicitly; see HarnessBootstrap
            // .ensureCertificates for the file itself.
            "Acquire::https::CaInfo \"" + prefix + "/etc/tls/cert.pem\";\n" +
            "Dir \"" + prefix + "\";\n" +
            // FindFile() joins every ancestor value, so sub-keys must be
            // relative to their parent: "var/lib/apt" + "lists/". Setting
            // Dir::State::status/lists to full prefix-relative paths would
            // resolve to .../var/lib/apt/var/lib/... and make debSystem::Score
            // fail with "Unable to determine a suitable packaging system
            // type". Left unset, apt derives the status file as
            // <Dir>/var/lib/dpkg/status exactly like Termux.
            "Dir::State \"var/lib/apt\";\n" +
            "Dir::Cache \"var/cache/apt\";\n" +
            "Dir::Cache::archives \"archives\";\n" +
            "Dir::Etc \"etc/apt\";\n" +
            "Dir::Etc::sourcelist \"sources.list\";\n" +
            "Dir::Etc::sourceparts \"sources.list.d\";\n" +
            "Dir::Etc::main \"apt.conf\";\n" +
            "Dir::Etc::parts \"apt.conf.d\";\n" +
            "Dir::Etc::trusted \"trusted.gpg\";\n" +
            "Dir::Etc::trustedparts \"trusted.gpg.d\";\n" +
            "Dir::Log \"var/log/apt\";\n" +
            // Dir::Bin::* values below are read with plain Find() (not
            // FindFile) by ExecGPGV (apt-key), debSystem::Score (dpkg) and
            // the compressor table (gzip/...), so they must be ABSOLUTE
            // relocated paths; the compiled-in defaults point at the
            // official Termux prefix, which does not exist in this app.
            // FindFile() treats a leading '/' as absolute, so this is also
            // safe for the callers that resolve through Dir.
            "Dir::Bin::dpkg \"" + prefix + "/bin/dpkg\";\n" +
            "Dir::Bin::apt-key \"" + prefix + "/bin/apt-key\";\n" +
            "Dir::Bin::gzip \"" + prefix + "/bin/gzip\";\n" +
            "Dir::Bin::bzip2 \"" + prefix + "/bin/bzip2\";\n" +
            "Dir::Bin::xz \"" + prefix + "/bin/xz\";\n" +
            "Dir::Bin::lz4 \"" + prefix + "/bin/lz4\";\n" +
            "Dir::Bin::zstd \"" + prefix + "/bin/zstd\";\n" +
            "Dir::Bin::lzma \"" + prefix + "/bin/xz\";\n" +
            "Dir::Bin::methods \"lib/apt/methods\";\n" +
            // apt defaults DPkg::Path to "/usr/sbin:/usr/bin:/sbin:/bin"
            // (pkgInitConfig, CndSet) and pkgDPkgPM overwrites PATH with it
            // for the dpkg child; dpkg then refuses to run because sh/rm/
            // tar/diff/dpkg-deb/start-stop-daemon are not in those (nonexistent
            // here) directories. Upstream Termux patches that default to
            // "@TERMUX_PREFIX@/bin" (0004-no-hardcoded-paths.patch); the
            // config-level equivalent is to set it explicitly, which the
            // CndSet default yields to.
            "DPkg::Path \"" + prefix + "/bin\";\n" +
            // dpkg would chroot() into the DPKG_ROOT fsys dir before running
            // maintainer scripts; an app process has no such privilege.
            // --force-script-chrootless is dpkg's supported mode for this:
            // scripts run outside the chroot with DPKG_ROOT in their
            // environment and the fsys root as working directory.
            "DPkg::Options:: \"--force-script-chrootless\";\n";
        try (FileOutputStream output = new FileOutputStream(aptConfig)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyResourceIfMissing(String resource, File target) {
        // Best-effort only: Android library builds do not reliably package
        // src/main/resources for getResourceAsStream (it works on the JVM
        // unit-test classpath but can return null on-device). The
        // authoritative copy is installed from APK assets by
        // HarnessBootstrap.ensureKeyring/ensureCertificates, so a missing
        // classpath resource must never abort prepare() — an earlier build
        // threw here and broke session startup ("bootstrap failed").
        // Also replace any bootstrap SYMLINKS.txt symlink at the target:
        // those links point at the official prefix, dangle here, and make
        // FileOutputStream fail with ENOENT while apt reports NO_PUBKEY.
        try {
            if (java.nio.file.Files.isSymbolicLink(target.toPath())
                || (target.exists() && !target.isFile())) {
                java.nio.file.Files.delete(target.toPath());
            }
        } catch (IOException ignored) {
            // Fall through to the write attempt below.
        }
        if (target.isFile() && target.length() > 0) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (InputStream input = HarnessEnvironment.class.getResourceAsStream(resource)) {
            if (input == null) return;
            try (FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            // Keyrings must be readable by the gpgv/apt-key child processes.
            target.setReadable(true, false);
        } catch (IOException ignored) {
            // Asset-based installation remains authoritative.
        }
    }

    private static void rewriteAptKeyShebang(HarnessConfig config) {
        File aptKey = new File(config.prefix, "bin/apt-key");
        if (!aptKey.isFile()) return;
        try {
            byte[] all = java.nio.file.Files.readAllBytes(aptKey.toPath());
            int newline = -1;
            for (int i = 0; i < all.length && i < 256; i++) {
                if (all[i] == '\n') { newline = i; break; }
            }
            if (newline < 0) return;
            String firstLine = new String(all, 0, newline, StandardCharsets.UTF_8);
            if (!firstLine.startsWith("#!") || !firstLine.contains("com.termux")) return;
            String shebang = "#!" + config.prefix.getAbsolutePath() + "/bin/sh\n";
            try (FileOutputStream out = new FileOutputStream(aptKey)) {
                out.write(shebang.getBytes(StandardCharsets.UTF_8));
                out.write(all, newline + 1, all.length - newline - 1);
            }
            aptKey.setExecutable(true, false);
        } catch (IOException ignored) {
            // termux-exec shebang interception remains as fallback.
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create runtime directory: " + directory);
        }
    }

    /**
     * Termux packages hard-code the official prefix
     * {@code /data/data/com.termux/files/usr} inside every .deb archive.
     * Official Termux never notices because that absolute path IS its own
     * app data directory, so dpkg can unpack the literal tree at the
     * filesystem root. This harness lives elsewhere, so give dpkg a
     * synthetic fsys root (exported as DPKG_ROOT) containing
     * {@code data/data/com.termux/files/usr} as a symlink to the real
     * relocated prefix: dpkg then creates the official-looking tree, and
     * every file physically lands in the harness prefix.
     */
    private static void ensureDpkgRootfsLink(HarnessConfig config) {
        try {
            File root = config.prefix.getParentFile();
            if (root == null) return;
            File filesDirectory = new File(root, "rootfs/data/data/com.termux/files");
            ensureDirectory(filesDirectory);
            File link = new File(filesDirectory, "usr");
            String expectedTarget = "../../../../usr";
            if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
                if (expectedTarget.equals(String.valueOf(
                        java.nio.file.Files.readSymbolicLink(link.toPath())))) {
                    return; // Already points at the live prefix.
                }
                java.nio.file.Files.delete(link.toPath());
            } else if (link.exists()) {
                return; // A real directory is in the way; never destroy data.
            }
            try {
                android.system.Os.symlink(expectedTarget, link.getAbsolutePath());
            } catch (Exception osFailure) {
                java.nio.file.Files.createSymbolicLink(
                    link.toPath(), java.nio.file.Paths.get(expectedTarget));
            }
        } catch (Exception ignored) {
            // Best effort: dpkg installs will fail loudly with diagnostics
            // if the link cannot be created, and prepare() must not break
            // unrelated command execution paths.
        }
    }

    public static Map<String, String> create(HarnessConfig config) {
        Map<String, String> environment = new LinkedHashMap<>();

        // Keep useful Android process variables, then replace the variables
        // that must point at the harness sandbox. No host PATH is trusted.
        try {
            environment.putAll(System.getenv());
        } catch (SecurityException ignored) {
            // Android normally permits this, but a minimal environment is valid.
        }

        String path = config.toolDirectory.getAbsolutePath()
            + File.pathSeparator + new File(config.workspace, "node_modules/.bin").getAbsolutePath()
            + File.pathSeparator + "/system/bin"
            + File.pathSeparator + "/system/xbin";
        File root = config.prefix.getParentFile();

        environment.put("HOME", config.home.getAbsolutePath());
        environment.put("PWD", config.workspace.getAbsolutePath());
        environment.put("OLDPWD", config.workspace.getAbsolutePath());
        environment.put("TMPDIR", new File(config.prefix, "tmp").getAbsolutePath());
        environment.put("PREFIX", config.prefix.getAbsolutePath());
        environment.put("PATH", path);
        environment.put("SHELL", config.shellPath);
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        environment.put("HARNESS_ROOT", root.getAbsolutePath());
        environment.put("HARNESS_PREFIX", config.prefix.getAbsolutePath());
        environment.put("HARNESS_WORKSPACE", config.workspace.getAbsolutePath());
        environment.put("HARNESS_TOOLS", config.toolDirectory.getAbsolutePath());

        // The official bootstrap is built for Termux's default package path.
        // termux-exec supports relocating that path when these variables are
        // exported by the host app. Without them, scripts retain
        // /data/data/com.termux/files/usr in their shebangs and fail with
        // "bad interpreter" in this standalone package.
        File appData = root.getParentFile() == null ? root : root.getParentFile().getParentFile();
        String appDataPath = appData == null ? root.getAbsolutePath() : appData.getAbsolutePath();
        String legacyAppDataPath = appDataPath.replace("/data/user/0/", "/data/data/");
        environment.put("TERMUX_APP__DATA_DIR", appDataPath);
        environment.put("TERMUX_APP__LEGACY_DATA_DIR", legacyAppDataPath);
        environment.put("TERMUX__ROOTFS", root.getAbsolutePath());
        environment.put("TERMUX__PREFIX", config.prefix.getAbsolutePath());
        environment.put("TERMUX__PROJECT_DIR", root.getAbsolutePath());
        environment.put("DPKG_ADMINDIR", new File(config.prefix, "var/lib/dpkg").getAbsolutePath());
        // Termux .debs contain literal ./data/data/com.termux/files/usr/...
        // paths and dpkg unpacks them under its fsys dir (DPKG_ROOT, dpkg's
        // own relocation interface). Point it at a small synthetic root whose
        // data/data/com.termux/files/usr symlink lands on this harness's
        // prefix (see ensureDpkgRootfsLink in prepare), so unpacked files
        // physically arrive here while dpkg bookkeeping stays consistent.
        environment.put("DPKG_ROOT", new File(root, "rootfs").getAbsolutePath());
        File aptConfig = new File(config.prefix, "etc/apt/" + APT_CONFIG_NAME);
        if (aptConfig.isFile()) environment.put("APT_CONFIG", aptConfig.getAbsolutePath());
        environment.put("XDG_DATA_HOME", new File(root, "xdg/data").getAbsolutePath());
        environment.put("XDG_CONFIG_HOME", new File(root, "xdg/config").getAbsolutePath());
        environment.put("XDG_STATE_HOME", new File(root, "xdg/state").getAbsolutePath());
        environment.put("XDG_CACHE_HOME", new File(root, "xdg/cache").getAbsolutePath());

        String existingLibraries = environment.get("LD_LIBRARY_PATH");
        environment.put("LD_LIBRARY_PATH", config.libraryDirectory.getAbsolutePath()
            + (existingLibraries == null || existingLibraries.isEmpty()
                ? "" : File.pathSeparator + existingLibraries));

        // Termux's OpenSSL/curl/git have the CA store compiled in for the
        // official prefix, which does not exist here. prepare() installs the
        // bundled Mozilla bundle at <prefix>/etc/tls/cert.pem; exporting it
        // makes apt's https method, curl and git verify TLS successfully.
        File certBundle = new File(config.prefix, "etc/tls/cert.pem");
        if (certBundle.isFile()) environment.put("SSL_CERT_FILE", certBundle.getAbsolutePath());
        for (String candidate : new String[] {"etc/tls/certs", "etc/ssl/certs"}) {
            File certDirectory = new File(config.prefix, candidate);
            if (certDirectory.isDirectory()) {
                environment.put("SSL_CERT_DIR", certDirectory.getAbsolutePath());
                break;
            }
        }

        // Termux packages use this hook to translate Linux-style executable
        // paths such as /bin/sh and /usr/bin/env to the private PREFIX. Newer
        // bootstraps use the linker-aware variant; older ones use the original
        // library name. Only export a path that is actually present, otherwise
        // Android's linker rejects every child process at startup.
        StringBuilder preload = new StringBuilder();
        if (config.nativeLibraryDirectory != null) {
            File harnessExec = new File(config.nativeLibraryDirectory, "libharness-exec.so");
            if (harnessExec.isFile()) preload.append(harnessExec.getAbsolutePath());
        }
        File termuxExec = new File(config.libraryDirectory, "libtermux-exec-ld-preload.so");
        if (!termuxExec.isFile()) {
            termuxExec = new File(config.libraryDirectory, "libtermux-exec.so");
        }
        if (termuxExec.isFile()) {
            if (preload.length() > 0) preload.append(File.pathSeparator);
            preload.append(termuxExec.getAbsolutePath());
        }
        String existingPreload = environment.get("LD_PRELOAD");
        if (existingPreload != null && !existingPreload.isEmpty()) {
            if (preload.length() > 0) preload.append(File.pathSeparator);
            preload.append(existingPreload);
        }
        if (preload.length() > 0) environment.put("LD_PRELOAD", preload.toString());

        // Android's shell prompt can be platform-dependent. A stable prompt
        // makes the embedded terminal useful for agents and tests.
        environment.put("PS1", "\\u@coding-harness:\\w$ ");
        return environment;
    }

    public static String[] toArray(Map<String, String> environment) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) continue;
            values.add(entry.getKey() + "=" + entry.getValue());
        }
        return values.toArray(new String[0]);
    }
}
