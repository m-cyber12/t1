package io.mcyber12.codingharness;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import io.mcyber12.codingharness.core.HarnessCommandRunner;
import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.sealed.ExecAudit;
import io.mcyber12.codingharness.core.exec.SealedPayloadInstaller;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

/**
 * The milestone gate for a store-legal build: a real shell, shipped by the APK,
 * running at a modern targetSdkVersion.
 *
 * <p>Every previous run proved the <em>mechanism</em> (a probe binary). This one
 * installs the sealed userland from the APK — the Termux bootstrap split into
 * data plus JNI libraries by {@code scripts/build-sealed-payload.py} — and then
 * runs the actual userland: {@code bash} and the core utilities, through the
 * prefix paths the rest of the harness uses, with the data-directory exec
 * exemption gone (targetSdk 34).</p>
 *
 * <p>It skips in the apt-based build, which has no payload; the workflow that
 * runs it asserts it did not skip.</p>
 */
@RunWith(AndroidJUnit4.class)
public class SealedShellTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void shellAndToolsRunFromTheApkPayload() throws Exception {
        HarnessConfig config = HarnessConfig.builder(context).build();
        int targetSdk = context.getApplicationInfo().targetSdkVersion;
        String abi = SealedPayloadInstaller.pickAbi(context);
        assumeTrue("this APK carries no sealed payload (not the sealed variant)", abi != null);
        report("sealed-shell: payload abi=" + abi + " targetSdk=" + targetSdk);

        SealedPayloadInstaller.Result install = SealedPayloadInstaller.install(context, config);
        assertNotNull("payload present but install returned nothing", install);
        report("sealed-shell: " + install.summary());
        assertTrue(install.summary(), install.ok());

        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File bash = new File(config.prefix, "bin/bash");
        assertTrue("bash missing from the prefix: " + bash, bash.isFile());

        // The shape is the design: the prefix path is a symlink into the APK.
        assertTrue("bash must be a symlink into the native library directory: " + bash,
            Files.isSymbolicLink(bash.toPath()));
        String target = ExecAudit.readLink(bash);
        assertTrue("bash points outside the APK: " + target,
            target != null && target.startsWith(nativeDir.getAbsolutePath()));
        report("sealed-shell: bin/bash -> " + target);

        HarnessCommandRunner runner = new HarnessCommandRunner(config);

        HarnessCommandRunner.CommandResult shell = runner.run(
            bash.getAbsolutePath(), "-c",
            "echo SEALED-SHELL-OK; echo version=$BASH_VERSION; echo prefix=$PREFIX; "
                + "echo path=$PATH; command -v sed; echo exit-ok");
        report("sealed-shell: bash -> exit " + shell.exitCode + " : "
            + shell.output.replace("\n", " | ").trim());
        assertTrue("bash did not run (exit " + shell.exitCode + "): " + shell.output,
            shell.output.contains("SEALED-SHELL-OK"));
        assertTrue("unexpected bash version output: " + shell.output,
            shell.output.toLowerCase(Locale.ROOT).contains("version"));
        assertTrue("bash did not reach the end of the script: " + shell.output,
            shell.output.contains("exit-ok"));

        // A second, different executable from the same payload: proves the split
        // produced a usable userland, not one lucky binary.
        File sed = new File(config.prefix, "bin/sed");
        assertTrue("sed missing: " + sed, sed.isFile());
        HarnessCommandRunner.CommandResult sedRun = runner.run(
            bash.getAbsolutePath(), "-c",
            "printf 'a1\\na2\\nb1\\n' | sed -n 's/^a/A/p'");
        report("sealed-shell: sed -> exit " + sedRun.exitCode + " : "
            + sedRun.output.replace("\n", " | ").trim());
        assertTrue("sed did not filter through bash: " + sedRun.output,
            sedRun.output.contains("A1") && sedRun.output.contains("A2")
                && !sedRun.output.contains("B1"));

        // The core tier, when this payload carries it: the same tools the apt
        // build installs on first run, executed at a store-legal targetSdk.
        File python = new File(config.prefix, "bin/python");
        if (python.exists()) {
            String[][] commands = {
                {"bin/git", "--version"},
                {"bin/python", "-c", "import ssl, json, sqlite3; print('py-ssl', ssl.OPENSSL_VERSION)"},
                {"bin/node", "--version"},
                {"bin/perl", "-e", "print $^V"},
                {"bin/ruby", "--version"},
                {"bin/rg", "--version"},
            };
            for (String[] command : commands) {
                File tool = new File(config.prefix, command[0]);
                assertTrue("core tool missing from the sealed payload: " + tool, tool.exists());
                String[] arguments = java.util.Arrays.copyOfRange(command, 1, command.length);
                HarnessCommandRunner.CommandResult result = runner.run(tool.getAbsolutePath(), arguments);
                String shown = result.output.replace("\n", " ").trim();
                if (shown.length() > 140) shown = shown.substring(0, 140);
                report("sealed-shell: " + command[0] + " -> exit " + result.exitCode + " : " + shown);
                assertTrue(command[0] + " failed in the sealed payload (exit "
                    + result.exitCode + "): " + result.output, result.isSuccess());
            }
        } else {
            report("sealed-shell: payload has no core tier (bootstrap only)");
        }

        // And the whole prefix is executable without the exemption.
        ExecAudit.Report audit = ExecAudit.audit(config.prefix, nativeDir);
        report("sealed-shell: " + audit.toText().replace("\n", " | "));
        assertFalse("audit could not finish: " + audit.toText(), audit.truncated);
        assertTrue("sealed payload leaves binaries outside the APK: "
            + audit.outsideApk().size(), audit.publishable());
        assertTrue("nothing was shipped in the APK: " + audit.toText(), audit.shippedCount() > 0);
        // Scripts stay in the payload by design (they are interpreted, never
        // exec'd); the count is reported so the reliance is visible, not hidden.
        report("sealed-shell: " + audit.outsideScripts().size()
            + " scripts in the payload run through the interpreter layer");
    }

    /** Append to the report the CI pulls, and to logcat so a failure is visible. */
    private void report(String line) {
        android.util.Log.i("SealedShell", line);
        File external = context.getExternalFilesDir(null);
        if (external == null) return;
        try (FileOutputStream output =
                 new FileOutputStream(new File(external, "setup-e2e-report.txt"), true)) {
            output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Reporting must never fail the measurement.
        }
    }
}
