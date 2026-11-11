# The harness keeps the terminal emulator's public callbacks reachable.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-keep class io.mcyber12.codingharness.core.** { *; }
