package io.mcyber12.codingharness;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.mcyber12.codingharness.core.HarnessBootstrap;
import io.mcyber12.codingharness.core.HarnessConfig;
import io.mcyber12.codingharness.core.HarnessPackageInstaller;
import io.mcyber12.codingharness.core.HarnessPaths;
import io.mcyber12.codingharness.core.HarnessSession;
import io.mcyber12.codingharness.core.Toolchain;

/**
 * Standalone terminal app host for the reusable coding harness.
 *
 * <p>The Activity contains no package/bootstrap logic. That lives in
 * harness-core so this UI can later be removed and the same session can be
 * attached to the main OpenCode app.</p>
 */
public final class MainActivity extends Activity {
    private TerminalView terminalView;
    private HarnessSession harnessSession;
    private HarnessConfig harnessConfig;
    private TextView statusView;
    private int fontSize = 14;
    private final AtomicBoolean setupRunning = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(5, 7, 10));
        window.setNavigationBarColor(Color.rgb(5, 7, 10));
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        buildUi();
        prepareRuntime();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(5, 7, 10));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(14), 0, dp(8), 0);
        toolbar.setBackgroundColor(Color.rgb(11, 16, 22));

        TextView title = new TextView(this);
        title.setText("CODING HARNESS");
        title.setTextColor(Color.rgb(226, 232, 240));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        statusView = new TextView(this);
        statusView.setText("preparing Termux runtime...");
        statusView.setTextColor(Color.rgb(125, 211, 252));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setMaxLines(2);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        toolbar.addView(statusView, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button setupButton = new Button(this);
        setupButton.setText("SETUP");
        setupButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        setupButton.setTextColor(Color.rgb(186, 230, 253));
        setupButton.setOnClickListener(v -> installToolchain());
        toolbar.addView(setupButton, new LinearLayout.LayoutParams(dp(76), dp(44)));

        Button toolsButton = new Button(this);
        toolsButton.setText("TOOLS");
        toolsButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        toolsButton.setTextColor(Color.rgb(186, 230, 253));
        toolsButton.setOnClickListener(v -> showToolInventory());
        toolbar.addView(toolsButton, new LinearLayout.LayoutParams(dp(76), dp(44)));
        root.addView(toolbar);

        terminalView = new TerminalView(this, null);
        terminalView.setTextSize(fontSize);
        terminalView.setBackgroundColor(Color.BLACK);
        terminalView.setFocusableInTouchMode(true);
        root.addView(terminalView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void prepareRuntime() {
        HarnessPaths paths = HarnessPaths.forApp(this);
        harnessConfig = HarnessConfig.builder(this)
            .workspace(paths.workspace())
            .home(paths.home())
            .prefix(paths.prefix())
            .toolDirectory(paths.bin())
            .libraryDirectory(paths.lib())
            .nativeLibraryDirectory(new java.io.File(getApplicationInfo().nativeLibraryDir))
            .shellPath(paths.defaultShell())
            .transcriptRows(2_000)
            .build();

        new Thread(() -> {
            try {
                setStatus("installing base runtime...");
                HarnessBootstrap.ensureInstalled(this, harnessConfig);
            } catch (Exception e) {
                logBootstrapError("ensureInstalled", e);
                setStatus("bootstrap failed: " + shortMessage(e));
                runOnUiThread(() -> showBootstrapFailure(e));
                return;
            }
            // Certificate/keyring provisioning is also retried on the SETUP
            // path and self-reported by the installer diagnostics; a failure
            // here must not prevent the terminal from starting.
            try {
                HarnessBootstrap.ensureCertificates(this, harnessConfig);
                HarnessBootstrap.ensureKeyring(this, harnessConfig);
            } catch (Exception e) {
                logBootstrapError("ensureCertificates/ensureKeyring", e);
            }
            runOnUiThread(this::attachTerminal);
        }, "coding-harness-bootstrap").start();
    }

    private void attachTerminal() {
        SessionClient client = new SessionClient();
        terminalView.setTerminalViewClient(client);
        terminalView.setTerminalCursorBlinkerRate(500);
        harnessSession = HarnessSession.start(this, harnessConfig, client);
        terminalView.attachSession(harnessSession.terminalSession());
        terminalView.requestFocus();
        setStatus("base ready; installing coding tools...");

        // Do this automatically on the first run, while SETUP remains available
        // for retrying after a network failure.
        installToolchain();
    }

    private void installToolchain() {
        if (harnessConfig == null || harnessSession == null) {
            setStatus("base runtime is still preparing...");
            return;
        }
        if (HarnessPackageInstaller.isCodingToolchainReady(harnessConfig)) {
            setStatus("ready: Python / Node/npm / Perl / Ruby");
            return;
        }
        if (!setupRunning.compareAndSet(false, true)) return;

        setStatus("installing Python / Node/npm / Perl / Ruby...");
        new Thread(() -> {
            // Retry asset-based provisioning on the setup path itself, so it
            // does not depend on the startup thread having succeeded.
            try {
                HarnessBootstrap.ensureCertificates(this, harnessConfig);
                HarnessBootstrap.ensureKeyring(this, harnessConfig);
            } catch (Exception e) {
                logBootstrapError("setup provisioning", e);
            }
            HarnessPackageInstaller.InstallResult result =
                HarnessPackageInstaller.installCodingToolchain(harnessConfig);
            setupRunning.set(false);
            writeSetupResult(result);
            if (result.success) {
                setStatus(result.message);
            } else {
                setStatus("setup failed — tap SETUP to retry");
                runOnUiThread(() -> showSetupFailure(result.message));
            }
        }, "coding-harness-packages").start();
    }

    /**
     * Persist the setup outcome so it survives the dialog (and so the
     * emulator E2E can tell "still installing" from "installation died"
     * instead of polling the toolchain forever): coding-harness/
     * setup-result.txt contains OK or FAILED plus the full message.
     */
    private void writeSetupResult(HarnessPackageInstaller.InstallResult result) {
        try {
            java.io.File file = new java.io.File(
                new java.io.File(getFilesDir(), "coding-harness"), "setup-result.txt");
            java.io.File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            String entry = (result.success ? "OK " : "FAILED ") + result.message + "\n";
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                out.write(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Status persistence must never affect setup itself.
        }
    }

    /** Full, actionable bootstrap failure: dialog + persistent log file. */
    private void showBootstrapFailure(Throwable throwable) {
        if (isFinishing()) return;
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (chain.length() > 0) chain.append("\ncaused by: ");
            chain.append(cause.getClass().getSimpleName())
                .append(": ")
                .append(cause.getMessage() == null ? "(no message)" : cause.getMessage());
        }
        chain.append("\n\nFull stack trace saved to coding-harness/bootstrap-error.log");
        new android.app.AlertDialog.Builder(this)
            .setTitle("Bootstrap failed")
            .setMessage(chain.toString())
            .setPositiveButton("Close", null)
            .show();
    }

    private void logBootstrapError(String step, Throwable throwable) {
        try {
            java.io.File log = new java.io.File(
                new java.io.File(getFilesDir(), "coding-harness"), "bootstrap-error.log");
            java.io.File parent = log.getParentFile();
            if (parent != null) parent.mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(log, true)) {
                String entry = step + " @ " + System.currentTimeMillis() + "\n"
                    + android.util.Log.getStackTraceString(throwable) + "\n";
                out.write(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Logging must never mask the original failure.
        }
    }

    private void showSetupFailure(String message) {
        if (isFinishing()) return;
        new android.app.AlertDialog.Builder(this)
            .setTitle("Coding tools are not installed")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show();
    }

    private void showToolInventory() {
        if (harnessConfig == null) return;
        setStatus("measuring storage...");
        // Size accounting walks the prefix and parses the dpkg database;
        // keep it off the UI thread, then show inventory + storage together.
        new Thread(() -> {
            StringBuilder text = new StringBuilder("Coding harness tools\n\n");
            for (Map.Entry<String, Toolchain.ToolStatus> entry : Toolchain.inspect(harnessConfig).entrySet()) {
                Toolchain.ToolStatus tool = entry.getValue();
                text.append(tool.name)
                    .append(tool.available ? "  READY" : "  —")
                    .append("\n  ")
                    .append(tool.note)
                    .append('\n');
            }
            text.append('\n')
                .append(io.mcyber12.codingharness.core.HarnessSizeReport.summarize(harnessConfig));
            boolean buildReady = HarnessPackageInstaller.isBuildToolchainReady(harnessConfig);
            boolean languagesReady = HarnessPackageInstaller.isExtraLanguagesReady(harnessConfig);
            runOnUiThread(() -> {
                setStatus("ready");
                if (isFinishing()) return;
                android.app.AlertDialog.Builder dialog = new android.app.AlertDialog.Builder(this)
                    .setTitle("Toolchain")
                    .setMessage(text.toString())
                    .setPositiveButton("Close", null);
                if (!buildReady) {
                    dialog.setNeutralButton("Install build tools",
                        (d, which) -> installOptionalPack(true));
                }
                if (!languagesReady) {
                    dialog.setNegativeButton("Install perl/ruby",
                        (d, which) -> installOptionalPack(false));
                }
                dialog.show();
            });
        }, "coding-harness-inventory").start();
    }

    /** Installs one on-demand pack (Build tools or Extra languages). */
    private void installOptionalPack(boolean buildTools) {
        if (harnessConfig == null || harnessSession == null) {
            setStatus("base runtime is still preparing...");
            return;
        }
        if (!setupRunning.compareAndSet(false, true)) return;
        String label = buildTools ? "build tools (clang/cmake)" : "perl/ruby";
        setStatus("installing " + label + "...");
        new Thread(() -> {
            HarnessPackageInstaller.InstallResult result = buildTools
                ? HarnessPackageInstaller.installBuildTools(harnessConfig)
                : HarnessPackageInstaller.installExtraLanguages(harnessConfig);
            setupRunning.set(false);
            writeSetupResult(result);
            if (result.success) {
                setStatus(result.message);
            } else {
                setStatus("optional install failed");
                runOnUiThread(() -> showSetupFailure(result.message));
            }
        }, "coding-harness-optional-pack").start();
    }

    private void showKeyboard() {
        terminalView.requestFocus();
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    private void setStatus(String status) {
        runOnUiThread(() -> {
            if (statusView != null) statusView.setText(status);
        });
    }

    private String shortMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + ": " + (message == null ? "(no message)" : message);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        if (harnessSession != null) harnessSession.stop();
        super.onDestroy();
    }

    private final class SessionClient implements TerminalSessionClient, TerminalViewClient {
        @Override
        public void onTextChanged(TerminalSession changedSession) {
            if (terminalView != null) terminalView.invalidate();
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
            if (statusView != null && changedSession.getTitle() != null) {
                statusView.setText(changedSession.getTitle());
            }
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            setStatus("shell exited: " + finishedSession.getExitStatus());
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text));
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && session != null) {
                CharSequence text = clip.getItemAt(0).coerceToText(MainActivity.this);
                if (text != null) session.write(text.toString());
            }
        }

        @Override public void onBell(TerminalSession session) { }
        @Override public void onColorsChanged(TerminalSession session) {
            if (terminalView != null) terminalView.invalidate();
        }
        @Override public void onTerminalCursorStateChange(boolean state) {
            if (terminalView != null) terminalView.setTerminalCursorBlinkerState(state, true);
        }
        @Override public void setTerminalShellPid(TerminalSession session, int pid) { }
        @Override public Integer getTerminalCursorStyle() {
            return TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE;
        }

        @Override public float onScale(float scale) {
            if (scale > 1.08f) {
                fontSize = Math.min(24, fontSize + 1);
                terminalView.setTextSize(fontSize);
                return 1.0f;
            }
            if (scale < 0.92f) {
                fontSize = Math.max(9, fontSize - 1);
                terminalView.setTextSize(fontSize);
                return 1.0f;
            }
            return scale;
        }

        @Override public void onSingleTapUp(MotionEvent event) { showKeyboard(); }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return true; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return true; }
        @Override public void copyModeChanged(boolean copyMode) { }
        @Override public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) { return false; }
        @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return false; }
        @Override public boolean onLongPress(MotionEvent event) { return false; }
        @Override public boolean readControlKey() { return false; }
        @Override public boolean readAltKey() { return false; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }
        @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }
        @Override public void onEmulatorSet() {
            terminalView.setTerminalCursorBlinkerState(true, true);
        }

        @Override public void logError(String tag, String message) { android.util.Log.e(tag, message); }
        @Override public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
        @Override public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
        @Override public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
        @Override public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { android.util.Log.e(tag, message, e); }
        @Override public void logStackTrace(String tag, Exception e) { android.util.Log.e(tag, "terminal error", e); }
    }
}
