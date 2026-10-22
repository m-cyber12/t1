package io.mcyber12.codingharness;

import android.content.Context;
import android.system.Os;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.codingharness.core.exec.ExecAudit;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Measures Android's execution rules for this app, rather than assuming them.
 *
 * <p>The harness runs a Linux userland it installed itself. On API 29+ Android
 * refuses {@code execve()} for anything under the app's data directory — the
 * reason the Termux app is frozen at {@code targetSdkVersion 28} and cannot be
 * published to Play. The way out is not a compile flag: it is to have the bytes
 * that run live in the app's native library directory (i.e. come out of the
 * APK) while the prefix keeps its absolute paths by pointing at them with
 * symlinks. This test proves each half of that claim on a real device, and its
 * expectations follow the target SDK of the build under test, so the same test
 * is meaningful in both variants:</p>
 *
 * <ul>
 *   <li>{@code targetSdk 28} — data-dir exec is expected to <b>work</b>; that
 *       is the shipping build and the test asserts the baseline.</li>
 *   <li>{@code targetSdk 34} — data-dir exec is expected to be <b>denied</b>,
 *       while the native-library and symlink paths still work, which is the
 *       store-legal design.</li>
 * </ul>
 *
 * <p>It needs no network and no package installation: the executable it runs is
 * {@code libharnessprobe.so}, shipped by this APK (see
 * {@code terminal-emulator/src/main/jni/harness-probe.c}).</p>
 */
@RunWith(AndroidJUnit4.class)
public class ExecModelTest {

    private static final String PROBE_BINARY = "libharnessprobe.so";
    private static final String PROBE_MARKER = "harness-probe-ok";
    /** The prefix path every Termux binary and package script hard-codes. */
    private static final String OFFICIAL_PREFIX = "/data/data/com.termux/files/usr";
    private static final long TIMEOUT_MS = 30_000;

    private final Context context = ApplicationProvider.getApplicationContext();

    /** Result of one attempted execution. */
    private static final class Outcome {
        int exitCode;
        String output = "";
        String failure;
    }

    @Test
    public void dataDirectoryExecutionFollowsTargetSdk() throws Exception {
        int targetSdk = context.getApplicationInfo().targetSdkVersion;
        report("exec-model: targetSdk=" + targetSdk + " abi="
            + android.os.Build.SUPPORTED_ABIS[0]);


        // First, the audit: "how much of this install still needs the data-dir
        // exec exemption?" It works in every variant (an empty prefix answers
        // zero), so the number is recorded even where the measurement below is
        // skipped.
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        ExecAudit.Report audit = ExecAudit.audit(config().prefix, nativeDir);
        report("exec-model: " + audit.toText().replace("\n", " | "));
        if (targetSdk >= 29) {
            assertFalse("the audit could not finish (truncated or failed), so it cannot "
                + "certify the install: " + audit.toText(), audit.truncated);
            assertTrue("a targetSdk " + targetSdk + " build may not leave executables in app data: "
                + audit.outsideApk().size(), audit.publishable());
        }

        File probe = new File(nativeDir, PROBE_BINARY);
        // Only the sealed variant extracts native libraries (extractNativeLibs
        // in app/src/sealed/AndroidManifest.xml). In the shipping build the APK
        // has no exec-allowed directory at all, so there is nothing to measure
        // here and the test is skipped rather than reported as a failure — the
        // exec-model gate asserts that this skip did not happen.
        assumeTrue("not a sealed build (no extracted native libraries); "
                + "the exec model only exists in the sealed variant: " + probe,
            probe.isFile());

        android.util.Log.i("ExecModel", "measuring the sealed exec model at targetSdk " + targetSdk);

        // (1) The native library directory is executable at every target.
        Outcome fromNative = execute(probe.getAbsolutePath(), null, null);
        assertProbeRan("native library directory", fromNative);

        // (2) The same bytes, copied into app-private storage. This is what a
        //     Termux-style prefix does with every binary it installs, and it is
        //     exactly what targetSdk 29+ forbids.
        File copy = new File(context.getFilesDir(), "exec-probe-copy.bin");
        copyFile(probe, copy);
        Os.chmod(copy.getAbsolutePath(), 0700);
        Outcome fromData = execute(copy.getAbsolutePath(), null, null);
        if (targetSdk >= 29) {
            assertTrue("app-private executable must not run at targetSdk " + targetSdk
                    + " (output: " + fromData.output + ")",
                fromData.failure != null || fromData.exitCode != 0);
            report("exec-model: data-dir exec denied as required ("
                + (fromData.failure != null ? fromData.failure : "exit " + fromData.exitCode) + ")");
        } else {
            assertProbeRan("app-private storage (legacy target)", fromData);
        }

        // (3) A prefix symlink to the APK's copy. The kernel resolves the link
        //     before checking permissions, so the inode that runs is the
        //     allowed one while the path stays the prefix path.
        File prefixBin = new File(config().prefix, "bin");
        assertTrue("cannot create " + prefixBin, prefixBin.isDirectory() || prefixBin.mkdirs());
        File link = new File(prefixBin, "harness-probe");
        if (link.exists()) link.delete();
        Os.symlink(probe.getAbsolutePath(), link.getAbsolutePath());
        Outcome throughLink = execute(link.getAbsolutePath(), null, null);
        assertProbeRan("prefix symlink into the APK", throughLink);

        // (4) A script whose #! names the *official* Termux prefix, executed by
        //     a preloaded process. Nothing rewrites these scripts (apt, dpkg and
        //     every package maintainer script contain them), so the preload has
        //     to translate the interpreter path and start it itself.
        File script = new File(context.getFilesDir(), "sealed-shebang.sh");
        write(script, "#!" + OFFICIAL_PREFIX + "/bin/harness-probe\n");
        // The preload ships in the APK. With extractNativeLibs (the sealed
        // build) it is already a real file in nativeLibraryDir; otherwise the
        // bootstrap copies it to the harness root. Prefer the APK's own copy —
        // that is the path the sealed design uses.
        File preload = new File(nativeDir, "libharness-exec.so");
        if (!preload.isFile()) {
            preload = new File(config().rootfs.getParentFile(), "libharness-exec.so");
        }
        assertTrue("preload library missing: " + preload, preload.isFile());
        List<String> env = new ArrayList<>();
        env.add("LD_PRELOAD=" + preload.getAbsolutePath());
        env.add("TERMUX__PREFIX=" + config().prefix.getAbsolutePath());
        env.add("TERMUX__ROOTFS=" + config().rootfs.getAbsolutePath());
        env.add("TERMUX_APP__DATA_DIR=" + context.getApplicationInfo().dataDir);
        // The preloaded process is the sealed binary itself, not a system
        // binary: an app-owned executable is what the preload runs inside in
        // production (bash, apt-get, dpkg, python all live in the APK), and the
        // execve() that matters is the one it issues.
        Outcome shebang = execute(probe.getAbsolutePath(),
            new String[]{"--exec", script.getAbsolutePath()}, env);
        assertProbeRan("shebang translated by the preload", shebang);

        // (5) A #! naming a *system* interpreter, executed directly. This is the
        //     kernel's own permission check with nothing in between: allowed at
        //     targetSdk 28, denied at 29+ — which is why (4) exists.
        File direct = new File(context.getFilesDir(), "direct-script.sh");
        write(direct, "#!/system/bin/sh\necho direct-script-ok\n");
        Os.chmod(direct.getAbsolutePath(), 0700);
        Outcome directRun = execute(direct.getAbsolutePath(), null, null);
        if (targetSdk >= 29) {
            assertTrue("direct execution of a data-dir script must be denied at targetSdk "
                    + targetSdk + " (output: " + directRun.output + ")",
                directRun.failure != null || directRun.exitCode != 0);
            report("exec-model: direct script exec denied as required");
        } else {
            assertTrue("expected direct-script-ok at targetSdk " + targetSdk
                + ", got: " + directRun.output, directRun.output.contains("direct-script-ok"));
        }
    }

    private void assertProbeRan(String how, Outcome outcome) {
        assertTrue(how + " failed (failure=" + outcome.failure + ", exit=" + outcome.exitCode
                + "): " + outcome.output,
            outcome.output.contains(PROBE_MARKER));
        report("exec-model: " + how + " -> ok (" + outcome.output.replace("\n", " ").trim() + ")");
    }

    private HarnessConfig config() {
        return HarnessConfig.builder(context).build();
    }

    /**
     * Run {@code path} with an optional env block. A denied exec surfaces as an
     * {@link IOException} from {@link ProcessBuilder} (EACCES), not an exit
     * code, so it is captured as a failure.
     */
    private Outcome execute(String path, String[] arguments, List<String> environment) {
        Outcome outcome = new Outcome();
        List<String> command = new ArrayList<>();
        command.add(path);
        if (arguments != null) command.addAll(java.util.Arrays.asList(arguments));
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (environment != null) {
                builder.environment().putAll(parseEnvironment(environment));
            }
            Process process = builder.start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            drain(process.getInputStream(), buffer);
            drain(process.getErrorStream(), buffer);
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                outcome.failure = "timeout";
            }
            outcome.exitCode = process.exitValue();
            outcome.output = buffer.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            outcome.failure = e.getMessage();
            outcome.exitCode = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome.failure = "interrupted";
            outcome.exitCode = -1;
        }
        return outcome;
    }

    private static java.util.Map<String, String> parseEnvironment(List<String> entries) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String entry : entries) {
            int separator = entry.indexOf('=');
            if (separator > 0) {
                map.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
        }
        return map;
    }

    private static void drain(InputStream input, ByteArrayOutputStream buffer) throws IOException {
        try (InputStream stream = input) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        if (destination.exists()) destination.delete();
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] chunk = new byte[64 * 1024];
            int count;
            while ((count = input.read(chunk)) != -1) output.write(chunk, 0, count);
        }
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Append to the same report file the setup E2E uses, so CI shows it. */
    private void report(String line) {
        android.util.Log.i("ExecModel", line);
        File external = context.getExternalFilesDir(null);
        if (external == null) return;
        File report = new File(external, "setup-e2e-report.txt");
        try (FileOutputStream output = new FileOutputStream(report, true)) {
            output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Reporting must never fail the measurement.
        }
    }
}
