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
import io.mcyber12.codingharness.core.HarnessBootstrap;
import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.sealed.ExecAudit;
import io.mcyber12.codingharness.core.exec.SealedPayloadInstaller;
import io.mcyber12.codingharness.core.packs.Pack;
import io.mcyber12.codingharness.core.packs.PackInstaller;
import io.mcyber12.codingharness.core.packs.PackResult;
import io.mcyber12.codingharness.core.packs.PackState;
import io.mcyber12.codingharness.core.packs.Packs;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
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
        // The payload is hashed as it is written: every extracted file must have
        // matched its manifest record. A payload that ships without the manifest
        // reports 0 here, which is a packaging bug, not a tolerable difference.
        assertEquals("payload was not verified against its manifest: " + install.summary(),
            install.files, install.verified);

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

    /**
     * The app's own startup path, not the installer on its own: a sealed APK
     * must install itself once and then stop re-extracting 14k files on every
     * launch, and its dpkg database must agree with what was shipped.
     */
    @Test
    public void startupInstallsOnceAndDpkgAgrees() throws Exception {
        HarnessConfig config = HarnessConfig.builder(context).build();
        String abi = SealedPayloadInstaller.pickAbi(context);
        assumeTrue("this APK carries no sealed payload (not the sealed variant)", abi != null);

        assertTrue("the app did not take the sealed path",
            HarnessBootstrap.ensureSealedPayload(context, config));

        long started = android.os.SystemClock.uptimeMillis();
        assertTrue("the second startup did not take the sealed path",
            HarnessBootstrap.ensureSealedPayload(context, config));
        long elapsed = android.os.SystemClock.uptimeMillis() - started;
        report("sealed-shell: second startup took " + elapsed + " ms (no re-extract)");
        assertTrue("second startup re-extracted the payload (" + elapsed + " ms)",
            elapsed < 5_000L);

        // dpkg has to know about the packages whose files came from the APK:
        // without this, apt offers to install tools that are already present.
        File status = new File(config.prefix, "var/lib/dpkg/status");
        assertTrue("no dpkg status in the payload: " + status, status.isFile());
        String database = new String(java.nio.file.Files.readAllBytes(status.toPath()),
            StandardCharsets.UTF_8);
        for (String packageName : new String[] {"nodejs", "perl", "ruby", "git"}) {
            int at = database.indexOf("Package: " + packageName + "\n");
            assertTrue(packageName + " is not recorded as installed in dpkg",
                at >= 0 && database.startsWith("Status: install ok installed",
                    database.indexOf("\n", at) + 1));
        }
        report("sealed-shell: dpkg records the shipped packages as installed");
    }

    /**
     * The pack channel of a store build.
     *
     * <p>An optional pack may not arrive through {@code apt} here: Play's rules
     * forbid downloading executable code, and this APK's own binaries already
     * run out of the APK. So the sealed build must answer on the store channel —
     * and when the device has no store, it must say so and leave the runtime
     * alone, rather than quietly installing packages on the side.</p>
     *
     * <p>A universal APK that fuses the pack module exercises the other branch:
     * the payload is local, so the pack installs and must then be removable.</p>
     */
    @Test
    public void packsComeFromTheStoreAndNeverFromApt() throws Exception {
        HarnessConfig config = HarnessConfig.builder(context).build();
        assumeTrue("not the sealed variant", SealedPayloadInstaller.pickAbi(context) != null);
        // This test may run alone (the pack gate instruments just this method),
        // so it installs the runtime it is about to add to — but only when the
        // runtime is not already there. Re-extracting 13,858 files to prove a
        // pack installs is minutes of emulator time for nothing.
        if (!new File(config.prefix, "bin/git").exists()) {
            SealedPayloadInstaller.Result base = SealedPayloadInstaller.install(context, config);
            assertTrue("the core payload did not install: "
                + (base == null ? "no payload" : base.summary()), base != null && base.ok());
        }

        PackInstaller packs = Packs.installer(context, config);
        assertEquals("a sealed build must use the store channel",
            "play-feature-delivery", packs.channel());

        PackState before = packs.state(Pack.BUILD_TOOLS);
        report("sealed-shell: pack state before -> " + before.describe());
        assertTrue("a sealed build must know where the pack would come from",
            before.isInstalled() || before.isInstallable()
                || before.status == PackState.Status.UNAVAILABLE);

        PackResult result = packs.install(Pack.BUILD_TOOLS);
        report("sealed-shell: pack install -> " + result.describe());
        // The fused build exists to prove the install/remove path, so it may not
        // quietly pass on the "no payload here" branch. An honest refusal is a
        // valid outcome only for a build that really has no pack in it.
        boolean packMustInstall = "true".equals(
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("packDemo"));
        if (packMustInstall) {
            assertEquals("this build carries the pack payload, so it must install: "
                + result.describe(), PackResult.Kind.INSTALLED, result.kind);
        }

        HarnessCommandRunner runner = new HarnessCommandRunner(config);
        if (result.kind == PackResult.Kind.INSTALLED) {
            // A fused module: the pack's own tools must run, from the APK.
            File make = new File(config.prefix, "bin/make");
            assertTrue("pack installed but make is missing: " + make, make.exists());
            HarnessCommandRunner.CommandResult ran = runner.run(make.getAbsolutePath(), "--version");
            report("sealed-shell: make -> exit " + ran.exitCode + " : "
                + ran.output.replace("\n", " | ").trim());
            assertTrue("the pack's tools do not run: " + ran.output, ran.isSuccess());

            PackResult removed = packs.remove(Pack.BUILD_TOOLS);
            report("sealed-shell: pack remove -> " + removed.describe());
            assertEquals("the pack did not come back out", PackResult.Kind.REMOVED, removed.kind);
            assertTrue("removal left the tool behind", !new File(config.prefix, "bin/make").exists());
        } else {
            // No store on this device and no fused module: an honest refusal.
            assertTrue("unexpected pack outcome: " + result.describe(),
                result.kind == PackResult.Kind.UNAVAILABLE
                    || result.kind == PackResult.Kind.DELIVERY_FAILED);
            assertTrue("the refusal must not have installed anything: "
                + result.describe(), !new File(config.prefix, "bin/clang").exists());
        }

        // Whatever happened to the pack, the runtime underneath is intact.
        File git = new File(config.prefix, "bin/git");
        HarnessCommandRunner.CommandResult gitRan = runner.run(git.getAbsolutePath(), "--version");
        report("sealed-shell: git after pack attempt -> exit " + gitRan.exitCode);
        assertTrue("the pack attempt damaged the core runtime", gitRan.isSuccess());
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
