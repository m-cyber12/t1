#define _GNU_SOURCE

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
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

/*
 * Maintainer scripts and package hooks invoke ordinary tools (`rm`, `mv`,
 * `sed`, `install`, ...) with absolute paths baked in at build time:
 * /data/data/com.termux/files/usr/... . The kernel resolves those against
 * the real filesystem, so without translation the tool operates on ANOTHER
 * app's private directory: dpkg reports "rm: cannot remove
 * '.../share/glib-2.0/schemas/gschemas.compiled': Permission denied" and
 * aborts the transaction ("too many errors, stopping"). termux-exec has the
 * same responsibility for its relocated prefix; translate path arguments on
 * every exec so removals, prerm/postrm hooks and scripted builds behave like
 * an official Termux install.
 */
static void free_translated_arguments(char **translated, char *const argv[]) {
    if (translated == NULL) return;
    if (argv != NULL) {
        for (size_t i = 0; argv[i] != NULL && translated[i] != NULL; i++) {
            if (translated[i] != argv[i]) free(translated[i]);
        }
    }
    free(translated);
}

static char **translate_arguments(char *const argv[], size_t argc) {
    if (argv == NULL || argc == 0) return NULL;
    char **translated = calloc(argc + 1, sizeof(char *));
    if (translated == NULL) return NULL;
    for (size_t i = 0; i < argc; i++) {
        char buffer[PATH_MAX];
        const char *path = translate_path(argv[i], buffer, sizeof(buffer));
        if (path == NULL) { free_translated_arguments(translated, argv); return NULL; }
        if (path == argv[i]) {
            translated[i] = argv[i];
            continue;
        }
        size_t length = strlen(path);
        char *copy = malloc(length + 1);
        if (copy == NULL) { free_translated_arguments(translated, argv); return NULL; }
        memcpy(copy, path, length + 1);
        translated[i] = copy;
    }
    return translated;
}

/* execve with translated executable path AND translated path arguments. */
static int execute_translated(
    const char *executable_path,
    char *const argv[],
    char *const envp[]
) {
    execve_function function = real_execve();
    if (function == NULL) {
        errno = ENOSYS;
        return -1;
    }
    size_t argc = argument_count(argv);
    if (argc == 0) return function(executable_path, argv, envp);
    char **arguments = translate_arguments(argv, argc);
    if (arguments == NULL) {
        // Translation is an optimization for relocated paths: if it cannot
        // allocate, run the command unchanged rather than failing it.
        return function(executable_path, argv, envp);
    }
    int result = function(executable_path, arguments, envp);
    int saved_errno = errno;
    free_translated_arguments(arguments, argv);
    errno = saved_errno;
    return result;
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
    if (file < 0) return execute_translated(executable_path, argv, envp);

    char header[PATH_MAX];
    ssize_t length = read(file, header, sizeof(header) - 1);
    close(file);
    if (length < 2 || header[0] != '#' || header[1] != '!') {
        return execute_translated(executable_path, argv, envp);
    }
    header[length] = '\0';

    char *interpreter = header + 2;
    while (*interpreter == ' ' || *interpreter == '\t') interpreter++;
    char *end = interpreter;
    while (*end != '\0' && *end != ' ' && *end != '\t' && *end != '\r' && *end != '\n') end++;
    if (*end == '\0') return execute_translated(executable_path, argv, envp);
    // *end is the first delimiter after the interpreter. If it is end-of-line
    // the shebang has NO optional argument: end + 1 would land on the script
    // body, and the interpreter would receive the script's first line as a
    // filename ("sh: cannot open echo ..."). Only a space/tab delimiter is
    // followed by an argument token.
    bool end_of_line = (*end == '\r' || *end == '\n');
    *end = '\0';

    char translated_interpreter[PATH_MAX];
    const char *interpreter_path = translate_path(
        interpreter, translated_interpreter, sizeof(translated_interpreter));
    if (interpreter_path == NULL) return -1;

    char *shebang_argument = end + 1;
    if (end_of_line) {
        shebang_argument = end; // Points at the '\0' written above: no argument.
    } else {
        while (*shebang_argument == ' ' || *shebang_argument == '\t') shebang_argument++;
        char *argument_end = shebang_argument;
        while (*argument_end != '\0' && *argument_end != '\r' && *argument_end != '\n') argument_end++;
        *argument_end = '\0';
    }

    size_t argc = argument_count(argv);
    char **arguments = argc > 1 ? translate_arguments(argv, argc) : NULL;
    char *translated_argv[argc + 4];
    size_t output_index = 0;
    translated_argv[output_index++] = (char *) interpreter_path;
    if (*shebang_argument != '\0') translated_argv[output_index++] = shebang_argument;
    translated_argv[output_index++] = (char *) executable_path;
    for (size_t i = 1; i < argc; i++) {
        translated_argv[output_index++] = arguments != NULL ? arguments[i] : argv[i];
    }
    translated_argv[output_index] = NULL;
    int result = function(interpreter_path, translated_argv, envp);
    int saved_errno = errno;
    free_translated_arguments(arguments, argv);
    errno = saved_errno;
    return result;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    return execute_with_translated_shebang(path, (char *const *) argv, (char *const *) envp);
}

/*
 * dpkg additionally reads its own configuration from a compile-time
 * CONFIGDIR (the official prefix's etc/dpkg). dpkg tolerates that
 * directory being absent (ENOENT) but treats any other error as fatal.
 * In the official app the path is dpkg's own data; here it points into
 * another app's private directory (or a nonexistent one behind an
 * SELinux boundary), so a stock phone answers EACCES, not ENOENT, and
 * every dpkg run dies with "error opening configuration directory ...
 * Permission denied". Translate official-prefix paths for the open
 * family dpkg (and friends) use, exactly as for exec: the relocated
 * etc/dpkg/dpkg.cfg.d does not exist, scandir yields ENOENT, and dpkg
 * proceeds - and if the relocated dpkg package ships config there, it
 * is found, matching official semantics.
 */
typedef int (*scandir_function)(const char *, struct dirent ***,
    int (*)(const struct dirent *),
    int (*)(const struct dirent **, const struct dirent **));
typedef int (*scandir64_function)(const char *, struct dirent64 ***,
    int (*)(const struct dirent64 *),
    int (*)(const struct dirent64 **, const struct dirent64 **));
typedef DIR *(*opendir_function)(const char *);
typedef FILE *(*fopen_function)(const char *, const char *);

int scandir(const char *dirp, struct dirent ***namelist,
    int (*filter)(const struct dirent *),
    int (*compar)(const struct dirent **, const struct dirent **)) {
    static scandir_function function;
    if (function == NULL) function = (scandir_function) dlsym(RTLD_NEXT, "scandir");
    if (function == NULL) { errno = ENOSYS; return -1; }
    char translated[PATH_MAX];
    const char *path = translate_path(dirp, translated, sizeof(translated));
    if (path == NULL) return -1;
    return function(path, namelist, filter, compar);
}

int scandir64(const char *dirp, struct dirent64 ***namelist,
    int (*filter)(const struct dirent64 *),
    int (*compar)(const struct dirent64 **, const struct dirent64 **)) {
    static scandir64_function function;
    if (function == NULL) function = (scandir64_function) dlsym(RTLD_NEXT, "scandir64");
    if (function == NULL) { errno = ENOSYS; return -1; }
    char translated[PATH_MAX];
    const char *path = translate_path(dirp, translated, sizeof(translated));
    if (path == NULL) return -1;
    return function(path, namelist, filter, compar);
}

DIR *opendir(const char *name) {
    static opendir_function function;
    if (function == NULL) function = (opendir_function) dlsym(RTLD_NEXT, "opendir");
    if (function == NULL) { errno = ENOSYS; return NULL; }
    char translated[PATH_MAX];
    const char *path = translate_path(name, translated, sizeof(translated));
    if (path == NULL) return NULL;
    return function(path);
}

FILE *fopen(const char *pathname, const char *mode) {
    static fopen_function function;
    if (function == NULL) function = (fopen_function) dlsym(RTLD_NEXT, "fopen");
    if (function == NULL) { errno = ENOSYS; return NULL; }
    char translated[PATH_MAX];
    const char *path = translate_path(pathname, translated, sizeof(translated));
    if (path == NULL) return NULL;
    return function(path, mode);
}

FILE *fopen64(const char *pathname, const char *mode) {
    static fopen_function function;
    if (function == NULL) function = (fopen_function) dlsym(RTLD_NEXT, "fopen64");
    if (function == NULL) { errno = ENOSYS; return NULL; }
    char translated[PATH_MAX];
    const char *path = translate_path(pathname, translated, sizeof(translated));
    if (path == NULL) return NULL;
    return function(path, mode);
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
