package io.mcyber12.codingharness;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.mcyber12.codingharness.core.HarnessCommandRunner;
import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.codingharness.core.HarnessPackageInstaller;
import io.mcyber12.codingharness.core.HarnessPaths;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Full on-device (emulator) end-to-end test: launches the real MainActivity,
 * which extracts the Termux bootstrap and automatically runs apt-get update +
 * toolchain installation, then asserts every required tool actually executes.
 *
 * Progress and diagnostics are appended to setup-e2e-report.txt in the app's
 * external files directory so the CI workflow can print/pull it even when the
 * test fails. This is the test that would have caught the relocated-prefix
 * TLS, apt-key and NO_PUBKEY failures without a physical device.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CodingHarnessE2eTest {
    private static final long SETUP_DEADLINE_MS = 150 * 60_000L;
    private static final long POLL_MS = 15_000L;

    private static final String[][] VERSION_COMMANDS = {
        {"python", "--version"},
        {"python", "-c", "import ssl, json, sqlite3; print('py-ssl', ssl.OPENSSL_VERSION)"},
        {"node", "--version"},
        {"node", "-e", "console.log('node-ok', process.version)"},
        {"npm", "--version"},
        {"perl", "-e", "print $^V"},
        {"ruby", "--version"},
        {"git", "--version"},
        {"rg", "--version"},
        {"clang", "--version"},
        {"cmake", "--version"},
        {"make", "--version"},
    };

    private File reportFile;
    private HarnessConfig config;

    private HarnessConfig buildConfig(Context context) {
        HarnessPaths paths = HarnessPaths.forApp(context);
        return HarnessConfig.builder(context)
            .workspace(paths.workspace())
            .home(paths.home())
            .prefix(paths.prefix())
            .toolDirectory(paths.bin())
            .libraryDirectory(paths.lib())
            .nativeLibraryDirectory(new File(context.getApplicationInfo().nativeLibraryDir))
            .shellPath(paths.defaultShell())
            .build();
    }

    private void initReport(Context context) {
        if (reportFile != null) return;
        File external = context.getExternalFilesDir(null);
        reportFile = new File(external != null ? external : context.getFilesDir(),
            "setup-e2e-report.txt");
    }

    /**
     * Fast gating stage (no UI): bootstrap extraction, apt-get update against
     * the real repository (exercises TLS, apt-key and signature verification)
     * and one small package install. If the apt chain is broken this fails in
     * minutes with diagnostics instead of burning the full toolchain budget.
     */
    @Test
    public void a01_aptChainUpdatesAndInstallsSmallPackage() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        initReport(context);
        config = buildConfig(context);
        report("a01 start; prefix=" + config.prefix.getAbsolutePath());

        io.mcyber12.codingharness.core.HarnessBootstrap.ensureInstalled(context, config);
        io.mcyber12.codingharness.core.HarnessBootstrap.ensureCertificates(context, config);
        io.mcyber12.codingharness.core.HarnessBootstrap.ensureKeyring(context, config);
        report("a01 bootstrap + cert + keyring installed");
        // dpkg refuses to run unless sh/rm/tar/diff/dpkg-deb/start-stop-daemon
        // are executable via PATH; probe exactly what dpkg would find so a
        // failure names the broken entry instead of guessing afterwards.
        try {
            StringBuilder probe = new StringBuilder("a01 bin probe:");
            for (String name : new String[] {"sh", "rm", "tar", "diff", "dpkg-deb",
                    "start-stop-daemon", "bash", "coreutils", "apt-get", "dpkg"}) {
                File entry = new File(config.prefix, "bin/" + name);
                probe.append(' ').append(name).append('=');
                try {
                    if (Files.isSymbolicLink(entry.toPath())) {
                        probe.append("link->").append(Files.readSymbolicLink(entry.toPath()));
                    }
                } catch (Exception ignored) { }
                probe.append("[exists=").append(entry.exists())
                    .append(",exec=").append(entry.canExecute())
                    .append(",len=").append(entry.length()).append(']');
            }
            report(probe.toString());
            HarnessCommandRunner probeRunner = new HarnessCommandRunner(config);
            HarnessCommandRunner.CommandResult pathProbe = probeRunner.run(
                Arrays.asList("bash", "-c",
                    "echo PATH=$PATH; command -v sh rm tar diff dpkg-deb start-stop-daemon; "
                        + "ls -la $PREFIX/bin/sh $PREFIX/bin/rm $PREFIX/bin/tar "
                        + "$PREFIX/bin/dpkg-deb $PREFIX/bin/start-stop-daemon 2>&1 | head -8"),
                60_000L);
            report("a01 PATH probe exit=" + pathProbe.exitCode + " : "
                + oneLine(pathProbe.output));
        } catch (Exception probeFailure) {
            report("a01 bin probe FAILED: " + probeFailure);
        }

        // Network probes first: distinguish emulator DNS problems (CI-only)
        // from harness problems. Java-side resolution, then the Termux curl
        // binary through the same environment apt will use.
        try {
            java.net.InetAddress[] addresses =
                java.net.InetAddress.getAllByName("packages-cf.termux.dev");
            StringBuilder ips = new StringBuilder();
            for (java.net.InetAddress address : addresses) ips.append(address.getHostAddress()).append(' ');
            report("a01 java DNS: " + ips.toString().trim());
        } catch (Exception e) {
            report("a01 java DNS FAILED: " + e);
        }
        HarnessCommandRunner runner = new HarnessCommandRunner(config);
        try {
            HarnessCommandRunner.CommandResult curlProbe = runner.run(Arrays.asList(
                "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "--max-time", "90",
                "https://packages-cf.termux.dev/apt/termux-main/dists/stable/InRelease"),
                120_000L);
            report("a01 termux curl probe exit=" + curlProbe.exitCode
                + " http=" + oneLine(curlProbe.output));
        } catch (Exception e) {
            report("a01 termux curl probe FAILED: " + e);
        }

        HarnessCommandRunner.CommandResult update =
            runner.run(Arrays.asList("apt-get", "update"), 30 * 60_000L);
        report("a01 apt-get update exit=" + update.exitCode + " : " + oneLine(update.output));
        if (!update.isSuccess()) {
            String diagnostics = collectDiagnostics(config);
            report("A01 FAILED\n" + diagnostics);
            fail("apt-get update failed (exit " + update.exitCode + "):\n"
                + update.output + "\n" + diagnostics);
        }
        int lists = countPackageLists(config);
        report("a01 package lists=" + lists);
        if (lists == 0) {
            String diagnostics = collectDiagnostics(config);
            report("A01 NO LISTS\nupdate output:\n" + update.output + "\n" + diagnostics);
            fail("apt-get update exited 0 but produced no package lists.\nupdate output:\n"
                + update.output + "\n" + diagnostics);
        }

        HarnessCommandRunner.CommandResult install =
            runner.run(Arrays.asList("apt-get", "-y", "install", "jq"), 20 * 60_000L);
        report("a01 apt-get install jq exit=" + install.exitCode + " : " + oneLine(install.output));
        if (!install.isSuccess()) {
            String diagnostics = collectDiagnostics(config);
            report("A01 INSTALL FAILED\n" + diagnostics);
            fail("apt-get install jq failed (exit " + install.exitCode + "):\n"
                + install.output + "\n" + diagnostics);
        }
        HarnessCommandRunner.CommandResult jq = runner.run(Arrays.asList("jq", "--version"), 120_000L);
        report("a01 jq --version exit=" + jq.exitCode + " : " + oneLine(jq.output));
        assertTrue("jq did not execute: " + jq.output, jq.isSuccess());
        report("A01 SUCCESS");
        // Interception probe: is libharness-exec actually preloaded, and
        // does an official-prefix shebang script execute through it?
        try {
            HarnessCommandRunner runner2 = new HarnessCommandRunner(config);
            HarnessCommandRunner.CommandResult preload = runner2.run(
                Arrays.asList("bash", "-c",
                    "echo LD_PRELOAD=$LD_PRELOAD; "
                        + "grep -E 'harness-exec|termux-exec' /proc/self/maps | head -3; "
                        + "ls $PREFIX/lib/libtermux-exec* $PREFIX/lib/libharness-exec* 2>&1; "
                        + "printf '#!/data/data/com.termux/files/usr/bin/sh\\necho SCRIPT-RAN-OK\\n' > $PREFIX/tmp/t.sh; "
                        + "chmod 755 $PREFIX/tmp/t.sh; $PREFIX/tmp/t.sh; echo SCRIPT_EXIT=$?"),
                60_000L);
            report("a01 preload probe: " + preload.output);
        } catch (Exception probeFailure) {
            report("a01 preload probe FAILED: " + probeFailure);
        }
        // Maintainer-script probe: nodejs 26.4.0-1's preinst exits 2 under
        // dpkg and blocks the whole a02 toolchain install. Reproduce it
        // faithfully (same env, same shebang interception dpkg relies on)
        // and capture what the script actually is and says.
        try {
            HarnessCommandRunner probe = new HarnessCommandRunner(config);
            probe.run(Arrays.asList("apt-get", "install", "-y", "--download-only", "nodejs"),
                10 * 60_000L);
            HarnessCommandRunner.CommandResult preinst = probe.run(
                Arrays.asList("bash", "-c",
                    "deb=$(ls $PREFIX/var/cache/apt/archives/nodejs_*.deb | head -1); "
                        + "echo DEB=$deb; rm -rf $PREFIX/tmp/njctrl; mkdir -p $PREFIX/tmp/njctrl; "
                        + "dpkg-deb -e \"$deb\" $PREFIX/tmp/njctrl && echo CONTROL-EXTRACTED; "
                        + "ls -la $PREFIX/tmp/njctrl; echo ---preinst---; cat $PREFIX/tmp/njctrl/preinst; "
                        + "chmod 755 $PREFIX/tmp/njctrl/preinst; "
                        + "echo ---run---; cd $PREFIX/tmp/njctrl && ./preinst install; echo PREINST_EXIT=$?"),
                120_000L);
            report("a01 nodejs preinst probe exit=" + preinst.exitCode + " : "
                + preinst.output);
        } catch (Exception probeFailure) {
            report("a01 nodejs preinst probe FAILED: " + probeFailure);
        }
    }

    private int countPackageLists(HarnessConfig config) {
        File[] entries = new File(config.prefix, "var/lib/apt/lists").listFiles();
        if (entries == null) return 0;
        int count = 0;
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().contains("_Packages")) count++;
        }
        return count;
    }

    @Test
    public void a02_fullSetupInstallsWorkingToolchain() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        initReport(context);
        if (config == null) config = buildConfig(context);
        HarnessConfig config = this.config;
        report("a02 start; prefix=" + config.prefix.getAbsolutePath());

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        report("MainActivity launched; waiting for automatic setup");

        long deadline = System.currentTimeMillis() + SETUP_DEADLINE_MS;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            if (HarnessPackageInstaller.isCodingToolchainReady(config)) {
                ready = true;
                break;
            }
            // Fail fast if the app itself already recorded a setup failure
            // (MainActivity persists it); polling to the deadline would
            // just burn emulator time without adding information.
            String setupResult = readSetupResult(context);
            if (setupResult != null && setupResult.startsWith("FAILED")) {
                String diagnostics = collectDiagnostics(config);
                report("SETUP FAILED (app-reported): " + setupResult + "\n" + diagnostics);
                fail("App-reported setup failure: " + setupResult + "\n" + diagnostics);
            }
            report("waiting… ready=" + readinessSummary(config));
            Thread.sleep(POLL_MS);
        }
        if (!ready) {
            String diagnostics = collectDiagnostics(config);
            report("SETUP TIMEOUT\n" + diagnostics);
            fail("Coding toolchain was not installed within "
                + (SETUP_DEADLINE_MS / 60000) + " minutes.\n" + diagnostics);
        }
        report("toolchain executables present; verifying each tool runs");

        HarnessCommandRunner runner = new HarnessCommandRunner(config);
        List<String> failures = new ArrayList<>();
        for (String[] command : VERSION_COMMANDS) {
            HarnessCommandRunner.CommandResult result =
                runner.run(Arrays.asList(command), 180_000L);
            String line = String.join(" ", command) + " -> exit " + result.exitCode
                + " : " + oneLine(result.output);
            report(line);
            if (!result.isSuccess() || oneLine(result.output).isEmpty()) {
                failures.add(line);
            }
        }
        assertTrue("Some tools failed to run:\n" + String.join("\n", failures),
            failures.isEmpty());
        report("E2E SUCCESS: all tools execute");
    }

    private String readinessSummary(HarnessConfig config) {
        String[] commands = {"bash", "git", "rg", "python", "node", "npm", "perl", "ruby", "curl"};
        StringBuilder out = new StringBuilder();
        for (String command : commands) {
            File exe = new File(config.toolDirectory, command);
            out.append(command).append(exe.isFile() && exe.canExecute() ? "=Y " : "=n ");
        }
        return out.toString().trim();
    }

    private String collectDiagnostics(HarnessConfig config) {
        StringBuilder out = new StringBuilder();
        try {
            File prefix = config.prefix;
            out.append("apt-get exists=").append(new File(prefix, "bin/apt-get").isFile())
                .append(", apt-key=").append(new File(prefix, "bin/apt-key").length())
                .append(", cert=").append(new File(prefix, "etc/tls/cert.pem").length())
                .append('\n');
            File[] keys = new File(prefix, "etc/apt/trusted.gpg.d")
                .listFiles((dir, name) -> name.endsWith(".gpg"));
            out.append("trusted keys=").append(keys == null ? 0 : keys.length).append('\n');
            out.append("--- sources.list ---\n")
                .append(readTail(new File(prefix, "etc/apt/sources.list"), 2000)).append('\n');
            out.append("--- harness-apt.conf ---\n")
                .append(readTail(new File(prefix, "etc/apt/harness-apt.conf"), 3000)).append('\n');
            out.append("--- harness-command.log (tail) ---\n")
                .append(readTail(new File(prefix, "var/log/harness-command.log"), 6000)).append('\n');
            out.append("--- bootstrap-error.log (tail) ---\n")
                .append(readTail(new File(config.prefix.getParentFile(), "bootstrap-error.log"), 3000)).append('\n');
            File[] lists = new File(prefix, "var/lib/apt/lists").listFiles();
            out.append("apt lists entries=").append(lists == null ? 0 : lists.length).append('\n');
            if (lists != null) {
                for (File list : lists) {
                    out.append("  list: ").append(list.getName())
                        .append(" (").append(list.length()).append("B)\n");
                }
            }
            File[] partial = new File(prefix, "var/lib/apt/lists/partial").listFiles();
            if (partial != null) {
                for (File p : partial) {
                    out.append("  partial: ").append(p.getName())
                        .append(" (").append(p.length()).append("B)\n");
                }
            }
        } catch (Exception e) {
            out.append("diagnostics failed: ").append(e).append('\n');
        }
        return out.toString();
    }

    private static String readSetupResult(Context context) {
        try {
            File file = new File(new File(context.getFilesDir(), "coding-harness"),
                "setup-result.txt");
            if (!file.isFile()) return null;
            return readTail(file, 4000).trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readTail(File file, int maxChars) {
        try {
            if (!file.isFile()) return "(missing: " + file.getAbsolutePath() + ")";
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
        } catch (Exception e) {
            return "(unreadable " + file.getAbsolutePath() + ": " + e.getMessage() + ")";
        }
    }

    private static String oneLine(String output) {
        if (output == null) return "";
        String normalized = output.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }

    private synchronized void report(String line) {
        String entry = "[" + System.currentTimeMillis() + "] " + line + "\n";
        // Mirror into logcat as an independent channel: the report file is
        // pulled over flaky adb and wiped when Gradle uninstalls the app,
        // while e2e-logs/logcat.log is captured straight from the device.
        android.util.Log.i("CodingHarnessE2E", line);
        try (FileOutputStream out = new FileOutputStream(reportFile, true)) {
            out.write(entry.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Reporting must never fail the test.
        }
        System.out.println("HARNESS-E2E " + line);
    }
}
