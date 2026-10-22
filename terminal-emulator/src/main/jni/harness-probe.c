/*
 * harness-probe.c — the smallest possible executable, used to *measure*
 * Android's execution rules instead of assuming them.
 *
 * It is built as a PIE executable and shipped in the APK under a JNI library
 * name (libharnessprobe.so), so installing the app both places it in the
 * native library directory and proves that the packaging step works. The
 * on-device test then executes it three ways — from the native library
 * directory, through a symlink in app-private storage, and as an interpreter
 * named by a shebang — and expects: allowed, allowed, allowed; while executing
 * a *copy* of it under app-private storage is expected to be denied for a
 * modern targetSdkVersion.
 *
 * Output is deliberately one line per fact and machine-greppable, because the
 * result is read back out of a CI log.
 */
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char self[4096];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n < 0) {
        snprintf(self, sizeof(self), "(unavailable)");
    } else {
        self[n] = '\0';
    }
    printf("harness-probe-ok\n");
    printf("argv0=%s\n", argc > 0 && argv[0] ? argv[0] : "(null)");
    printf("exe=%s\n", self);
    printf("argc=%d\n", argc);
    for (int i = 1; i < argc; i++) printf("arg%d=%s\n", i, argv[i]);
    fflush(stdout);
    return 0;
}
