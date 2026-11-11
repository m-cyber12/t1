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
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/*
 * --exec <path>: replace this process with <path>. Used by the on-device gate
 * to exercise the LD_PRELOAD exec layer from a *sealed* binary (the shape every
 * runtime in the prefix has), instead of from a system binary: an app-owned
 * executable is what the preload is loaded into in production, and the kernel
 * permission check for the target is then made by the preload, not by the
 * direct execve() the JVM would issue.
 */
static int exec_mode(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "harness-probe: --exec needs a path\n");
        return 64;
    }
    execv(argv[2], &argv[2]);
    fprintf(stderr, "harness-probe-exec failed: %s (errno=%d)\n", strerror(errno), errno);
    return 127;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--exec") == 0) return exec_mode(argc, argv);

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
    printf("ld_preload=%s\n", getenv("LD_PRELOAD") ? getenv("LD_PRELOAD") : "(unset)");
    printf("argc=%d\n", argc);
    for (int i = 1; i < argc; i++) printf("arg%d=%s\n", i, argv[i]);
    fflush(stdout);
    return 0;
}
