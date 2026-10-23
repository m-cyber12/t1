package io.mcyber12.codingharness.core.packs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * The pack layer's pure logic: what counts as installed, and what a result says.
 *
 * <p>Everything here is JVM-only, which is deliberate — a pack that can be
 * installed but whose state is wrong is a support ticket, and this is the
 * cheapest place to catch it. The Android-dependent halves
 * ({@code PlayPackInstaller}, {@code AptPackInstaller}) are thin enough to be
 * exercised by the instrumentation gate.</p>
 */
public class PackCommandsTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File bin() throws Exception {
        return temporary.newFolder("bin");
    }

    private static void executable(File directory, String name) throws Exception {
        File file = new File(directory, name);
        file.createNewFile();
        assertTrue("could not mark " + name + " executable", file.setExecutable(true));
    }

    @Test
    public void aPackIsInstalledOnlyWhenEveryCommandCanRun() throws Exception {
        File bin = bin();
        executable(bin, "make");
        executable(bin, "pkg-config");
        executable(bin, "clang");

        PackState state = new PackState(Pack.BUILD_TOOLS, PackState.Status.INSTALLED,
            "apt", PackCommands.missingFrom(bin, Pack.BUILD_TOOLS));
        // cmake is the one that is missing: three of four is not installed.
        assertEquals(1, state.missingCommands.size());
        assertEquals("cmake", state.missingCommands.get(0));
        assertFalse(PackCommands.present(bin, Pack.BUILD_TOOLS));

        executable(bin, "cmake");
        assertTrue(PackCommands.present(bin, Pack.BUILD_TOOLS));
        assertTrue(PackCommands.missingFrom(bin, Pack.BUILD_TOOLS).isEmpty());
        assertEquals(4, PackCommands.presentIn(bin, Pack.BUILD_TOOLS).size());
    }

    @Test
    public void aFileOnDiskThatCannotExecuteIsNotInstalled() throws Exception {
        File bin = bin();
        for (String command : Pack.BUILD_TOOLS.requiredCommands) {
            File file = new File(bin, command);
            file.createNewFile();
        }
        // Present but not executable: apt would consider it installed, a shell
        // would not, and the user would get "Permission denied".
        assertEquals(4, PackCommands.missingFrom(bin, Pack.BUILD_TOOLS).size());
    }

    @Test
    public void anEmptyPrefixInstallsNothing() throws Exception {
        File bin = bin();
        assertTrue(PackCommands.presentIn(bin, Pack.BUILD_TOOLS).isEmpty());
        assertEquals(Pack.BUILD_TOOLS.requiredCommands,
            PackCommands.missingFrom(bin, Pack.BUILD_TOOLS));
        // A null directory is a configuration bug, not a crash in the UI thread.
        assertTrue(PackCommands.presentIn(null, Pack.BUILD_TOOLS).isEmpty());
        assertFalse(PackCommands.present(null, Pack.BUILD_TOOLS));
    }

    @Test
    public void theDeliveredCommandsComeFromThePayloadNotFromTheAdvertisedList() {
        // A CI build ships two of the four advertised tools. That is a smaller
        // pack, not a broken one: what has to be verified is what arrived.
        java.util.List<String> exec = java.util.Arrays.asList(
            "bin/make\tlibmake.so",
            "bin/pkg-config\tlibpkg-config.so",
            "libexec/gcc/x86_64-linux-gnu/14/cc1\tlibcc1.so",
            "share/man/man1/make.1\tmake.1",
            "# a comment",
            "");
        java.util.List<String> commands = PackCommands.commandsIn(exec);
        assertEquals(java.util.Arrays.asList("make", "pkg-config"), commands);

        // Nested paths are not commands, duplicates collapse, and null is empty.
        assertTrue(PackCommands.commandsIn(null).isEmpty());
        assertEquals(java.util.Arrays.asList("clang"),
            PackCommands.commandsIn(java.util.Arrays.asList("bin/clang\tlibclang.so",
                "bin/clang\tlibclang.so")));
    }

    @Test
    public void verifyingAListWorksForPartialPacks() throws Exception {
        File bin = bin();
        executable(bin, "make");
        // Only make arrived; pkg-config was not part of this payload.
        assertEquals(java.util.Arrays.asList("pkg-config"),
            PackCommands.missingFrom(bin, java.util.Arrays.asList("make", "pkg-config")));
        assertEquals(java.util.Collections.singletonList("make"),
            PackCommands.presentIn(bin, java.util.Arrays.asList("make", "pkg-config")));
        assertTrue(PackCommands.presentIn(bin, (java.util.List<String>) null).isEmpty());
        assertTrue(PackCommands.missingFrom(bin, null).isEmpty());
    }

    @Test
    public void aPackNamesBothOfItsDeliveryChannels() {
        // The store module and the apt tier are the same pack on two devices.
        assertEquals("pack_buildtools", Pack.BUILD_TOOLS.moduleName);
        assertEquals("build-tools", Pack.BUILD_TOOLS.tierId);
        // The asset prefix is what keeps the pack's payload out of the base one.
        assertTrue(Pack.BUILD_TOOLS.assetPrefix.endsWith("-"));
        assertFalse(Pack.BUILD_TOOLS.assetPrefix.isEmpty());
        assertEquals(Pack.BUILD_TOOLS, Pack.byId("build-tools"));
        assertEquals(1, Pack.all().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void anUnknownPackIdIsNotSilentlyAccepted() {
        Pack.byId("no-such-pack");
    }

    @Test
    public void aResultSaysWhetherItChangedAnything() {
        PackResult installed = PackResult.ok(PackResult.Kind.INSTALLED, "build-tools", "done");
        assertTrue(installed.isSuccess());
        assertTrue(installed.describe().contains("build-tools"));
        assertTrue(installed.problems.isEmpty());

        assertTrue(PackResult.ok(PackResult.Kind.UNCHANGED, "build-tools", "already").isSuccess());
        assertTrue(PackResult.ok(PackResult.Kind.REMOVED, "build-tools", "gone").isSuccess());

        // The failures are not interchangeable: the UI offers a different next
        // step for "no store" than for "the download broke".
        PackResult unavailable = PackResult.failed(PackResult.Kind.UNAVAILABLE, "build-tools",
            "no Play Store on this device");
        assertFalse(unavailable.isSuccess());
        assertTrue(unavailable.describe().contains("no Play Store"));

        PackResult failed = new PackResult(PackResult.Kind.INSTALL_FAILED, "build-tools",
            "payload broken", 4096, java.util.Arrays.asList("bin/clang missing"));
        assertFalse(failed.isSuccess());
        assertEquals(4096, failed.bytes);
        assertTrue(failed.describe().contains("4096"));
        assertTrue(failed.describe().contains("bin/clang missing"));
    }

    @Test
    public void packStateTellsTheUiWhetherInstallIsWorthOffering() {
        PackState installed = new PackState(Pack.BUILD_TOOLS, PackState.Status.INSTALLED,
            "apt", null);
        assertTrue(installed.isInstalled());
        assertFalse(installed.isInstallable());

        assertTrue(new PackState(Pack.BUILD_TOOLS, PackState.Status.AVAILABLE_LOCALLY,
            "play-feature-delivery", null).isInstallable());
        assertTrue(new PackState(Pack.BUILD_TOOLS, PackState.Status.AVAILABLE_FROM_STORE,
            "play-feature-delivery", null).isInstallable());
        assertTrue(new PackState(Pack.BUILD_TOOLS, PackState.Status.AVAILABLE_FROM_PACKAGES,
            "apt", null).isInstallable());

        PackState unavailable = new PackState(Pack.BUILD_TOOLS, PackState.Status.UNAVAILABLE,
            "no store on this device", null);
        assertFalse(unavailable.isInstallable());
        assertNull(unavailable.missingCommands.isEmpty() ? null : "unexpected");
        assertTrue(unavailable.describe().contains("no store on this device"));
    }
}
