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
                runOnUiThread(this::attachTerminal);
            } catch (Exception e) {
                setStatus("bootstrap failed: " + shortMessage(e));
            }
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
            HarnessPackageInstaller.InstallResult result =
                HarnessPackageInstaller.installCodingToolchain(harnessConfig);
            setupRunning.set(false);
            if (result.success) {
                setStatus(result.message);
            } else {
                setStatus("setup failed — tap SETUP to retry");
                runOnUiThread(() -> showSetupFailure(result.message));
            }
        }, "coding-harness-packages").start();
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
        StringBuilder text = new StringBuilder("Coding harness tools\n\n");
        for (Map.Entry<String, Toolchain.ToolStatus> entry : Toolchain.inspect(harnessConfig).entrySet()) {
            Toolchain.ToolStatus tool = entry.getValue();
            text.append(tool.name)
                .append(tool.available ? "  READY" : "  —")
                .append("\n  ")
                .append(tool.note)
                .append('\n');
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Toolchain")
            .setMessage(text.toString())
            .setPositiveButton("Close", null)
            .show();
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
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
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
