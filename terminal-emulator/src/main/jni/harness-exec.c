#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

/*
 * The official Termux bootstrap is compiled for /data/data/com.termux. The
 * standalone harness deliberately has a different package and prefix, so the
 * bootstrap's absolute script interpreters need one small compatibility layer.
 * This library is loaded only into harness child processes through LD_PRELOAD.
 * It translates old Termux interpreter paths before the kernel handles a
 * shebang, which also covers package maintainer scripts invoked by dpkg.
 */

typedef int (*execve_function)(const char *, char *const[], char *const[]);
typedef int (*execvp_function)(const char *, char *const[]);

static execve_function real_execve(void) {
    static execve_function function;
    if (function == NULL) function = (execve_function) dlsym(RTLD_NEXT, "execve");
    return function;
}

static execvp_function real_execvp(void) {
    static execvp_function function;
    if (function == NULL) function = (execvp_function) dlsym(RTLD_NEXT, "execvp");
    return function;
}

static int has_path_prefix(const char *path, const char *prefix) {
    size_t length = strlen(prefix);
    return strncmp(path, prefix, length) == 0 &&
        (path[length] == '\0' || path[length] == '/');
}

static const char *replacement_for(const char *old_prefix) {
    const char *replacement = getenv(old_prefix);
    return replacement == NULL ? "" : replacement;
}

static const char *translate_path(const char *path, char *buffer, size_t buffer_size) {
    static const char *old_prefixes[] = {
        "/data/data/com.termux/files/usr",
        "/data/user/0/com.termux/files/usr",
        "/data/data/com.termux/files",
        "/data/user/0/com.termux/files",
        "/data/data/com.termux",
        "/data/user/0/com.termux"
    };
    static const char *replacement_variables[] = {
        "TERMUX__PREFIX",
        "TERMUX__PREFIX",
        "TERMUX__ROOTFS",
        "TERMUX__ROOTFS",
        "TERMUX_APP__DATA_DIR",
        "TERMUX_APP__DATA_DIR"
    };

    if (path == NULL) return path;
    for (size_t i = 0; i < sizeof(old_prefixes) / sizeof(old_prefixes[0]); i++) {
        const char *old_prefix = old_prefixes[i];
        if (!has_path_prefix(path, old_prefix)) continue;

        const char *replacement = replacement_for(replacement_variables[i]);
        if (replacement[0] == '\0') return path;
        size_t replacement_length = strlen(replacement);
        size_t suffix_length = strlen(path + strlen(old_prefix));
        if (replacement_length + suffix_length + 1 > buffer_size) {
            errno = ENAMETOOLONG;
            return NULL;
        }
        memcpy(buffer, replacement, replacement_length);
        memcpy(buffer + replacement_length, path + strlen(old_prefix), suffix_length + 1);
        return buffer;
    }
    return path;
}

static size_t argument_count(char *const argv[]) {
    size_t count = 0;
    if (argv != NULL) {
        while (argv[count] != NULL) count++;
    }
    return count;
}

static int execute_with_translated_shebang(
    const char *path,
    char *const argv[],
    char *const envp[]
) {
    execve_function function = real_execve();
    if (function == NULL) {
        errno = ENOSYS;
        return -1;
    }

    char translated_path[PATH_MAX];
    const char *executable_path = translate_path(path, translated_path, sizeof(translated_path));
    if (executable_path == NULL) return -1;

    int file = open(executable_path, O_RDONLY | O_CLOEXEC);
    if (file < 0) return function(executable_path, argv, envp);

    char header[PATH_MAX];
    ssize_t length = read(file, header, sizeof(header) - 1);
    close(file);
    if (length < 2 || header[0] != '#' || header[1] != '!') {
        return function(executable_path, argv, envp);
    }
    header[length] = '\0';

    char *interpreter = header + 2;
    while (*interpreter == ' ' || *interpreter == '\t') interpreter++;
    char *end = interpreter;
    while (*end != '\0' && *end != ' ' && *end != '\t' && *end != '\r' && *end != '\n') end++;
    if (*end == '\0') return function(executable_path, argv, envp);
    *end = '\0';

    char translated_interpreter[PATH_MAX];
    const char *interpreter_path = translate_path(
        interpreter, translated_interpreter, sizeof(translated_interpreter));
    if (interpreter_path == NULL) return -1;

    char *shebang_argument = end + 1;
    while (*shebang_argument == ' ' || *shebang_argument == '\t') shebang_argument++;
    char *argument_end = shebang_argument;
    while (*argument_end != '\0' && *argument_end != '\r' && *argument_end != '\n') argument_end++;
    *argument_end = '\0';

    size_t argc = argument_count(argv);
    char *translated_argv[argc + 4];
    size_t output_index = 0;
    translated_argv[output_index++] = (char *) interpreter_path;
    if (*shebang_argument != '\0') translated_argv[output_index++] = shebang_argument;
    translated_argv[output_index++] = (char *) executable_path;
    for (size_t i = 1; i < argc; i++) translated_argv[output_index++] = argv[i];
    translated_argv[output_index] = NULL;
    return function(interpreter_path, translated_argv, envp);
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    return execute_with_translated_shebang(path, (char *const *) argv, (char *const *) envp);
}

int execv(const char *path, char *const argv[]) {
    extern char **environ;
    return execve(path, argv, environ);
}

int execvp(const char *file, char *const argv[]) {
    extern char **environ;
    if (file != NULL && strchr(file, '/') != NULL) return execve(file, argv, environ);

    const char *path = getenv("PATH");
    if (path != NULL && file != NULL) {
        const char *part = path;
        while (*part != '\0') {
            const char *separator = strchr(part, ':');
            size_t directory_length = separator == NULL ? strlen(part) : (size_t) (separator - part);
            char candidate[PATH_MAX];
            int written = snprintf(candidate, sizeof(candidate), "%.*s/%s",
                (int) directory_length, part, file);
            if (written > 0 && (size_t) written < sizeof(candidate) && access(candidate, X_OK) == 0) {
                return execve(candidate, argv, environ);
            }
            if (separator == NULL) break;
            part = separator + 1;
        }
    }
    execvp_function function = real_execvp();
    if (function == NULL) {
        errno = ENOENT;
        return -1;
    }
    return function(file, argv);
}
