/*
 * jni_pty.c - JNI PTY manager for Android (no libutil dependency)
 *
 * Uses standard POSIX PTY API available in Android Bionic (API 23+).
 * Compile: clang -shared -fPIC -o libptyjni.so -I$JAVA_HOME/include -I$JAVA_HOME/include/linux jni_pty.c
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <errno.h>

/* Android Bionic has grantpt/unlockpt/ptsname since API 23 */
#if __ANDROID_API__ < 23
#error "Need Android API 23+ for PTY support"
#endif

typedef struct {
    int master;
    int slave;
    pid_t pid;
    int exited;
    int exit_status;
} PtySession;

static PtySession sessions[16];
static int num_sessions = 0;

/* Create a PTY manually: open /dev/ptmx, grantpt, unlockpt, get slave name */
static int create_pty(int *master_out, int *slave_out, int rows, int cols) {
    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (master < 0) return -1;

    if (grantpt(master) < 0) { close(master); return -1; }
    if (unlockpt(master) < 0) { close(master); return -1; }

    char *slave_name = ptsname(master);
    if (!slave_name) { close(master); return -1; }

    int slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave < 0) { close(master); return -1; }

    /* Set window size */
    struct winsize ws;
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    ioctl(master, TIOCSWINSZ, &ws);

    *master_out = master;
    *slave_out = slave;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_nousresearch_reasonix_TerminalBridge_createPty(
    JNIEnv *env, jclass clazz,
    jstring cmd, jobjectArray args,
    jint rows, jint cols) {

    if (num_sessions >= 16) return -1;

    const char *cmd_str = (*env)->GetStringUTFChars(env, cmd, NULL);

    /* Build argv */
    int argc = (*env)->GetArrayLength(env, args) + 2;
    char **argv = calloc(argc, sizeof(char *));
    argv[0] = strdup(cmd_str);
    for (int i = 0; i < argc - 2; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, NULL);
        argv[i + 1] = strdup(arg_str);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
    }
    argv[argc - 1] = NULL;
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);

    int master, slave;
    if (create_pty(&master, &slave, rows, cols) < 0) {
        for (int i = 0; i < argc - 1; i++) free(argv[i]);
        free(argv);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(master); close(slave);
        for (int i = 0; i < argc - 1; i++) free(argv[i]);
        free(argv);
        return -1;
    }

    if (pid == 0) {
        /* Child */
        setsid();
        close(master);

        /* Set controlling terminal */
        if (ioctl(slave, TIOCSCTTY, 0) < 0) { /* ignore error */ }

        /* Dup slave to stdin/stdout/stderr */
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        /* Set environment and working directory */
        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("HOME", "/data/data/com.nousresearch.reasonix/files", 1);
        setenv("TMPDIR", "/data/data/com.nousresearch.reasonix/files/tmp", 1);
        setenv("PATH", "/data/data/com.nousresearch.reasonix/files:/system/bin:/system/xbin", 1);
        chdir("/data/data/com.nousresearch.reasonix/files");

        /* Reset signals to default */
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);

        execvp(argv[0], argv);
        _exit(127);
    }

    /* Parent */
    close(slave);
    for (int i = 0; i < argc - 1; i++) free(argv[i]);
    free(argv);

    /* Set non-blocking on master */
    int flags = fcntl(master, F_GETFL, 0);
    fcntl(master, F_SETFL, flags | O_NONBLOCK);

    sessions[num_sessions].master = master;
    sessions[num_sessions].slave = -1;
    sessions[num_sessions].pid = pid;
    sessions[num_sessions].exited = 0;
    sessions[num_sessions].exit_status = 0;
    int idx = num_sessions++;

    return (jint)(idx | 0x10000000);
}

JNIEXPORT jint JNICALL
Java_com_nousresearch_reasonix_TerminalBridge_readPty(
    JNIEnv *env, jclass clazz,
    jint session_id, jbyteArray buf) {

    int idx = session_id & 0x0FFFFFFF;
    if (idx < 0 || idx >= num_sessions) return -1;
    if (sessions[idx].exited) return -2;

    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    jsize len = (*env)->GetArrayLength(env, buf);

    ssize_t n = read(sessions[idx].master, data, len);
    (*env)->ReleaseByteArrayElements(env, buf, data, 0);

    if (n > 0) return (jint)n;
    if (n == 0) { sessions[idx].exited = 1; return -2; }
    if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
    if (errno == EIO) { sessions[idx].exited = 1; return -2; }  /* slave closed */
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_nousresearch_reasonix_TerminalBridge_writePty(
    JNIEnv *env, jclass clazz,
    jint session_id, jbyteArray buf) {

    int idx = session_id & 0x0FFFFFFF;
    if (idx < 0 || idx >= num_sessions) return -1;
    if (sessions[idx].exited) return -2;

    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    jsize len = (*env)->GetArrayLength(env, buf);

    ssize_t n = write(sessions[idx].master, data, len);
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);

    return (n >= 0) ? (jint)n : -1;
}

JNIEXPORT void JNICALL
Java_com_nousresearch_reasonix_TerminalBridge_resizePty(
    JNIEnv *env, jclass clazz,
    jint session_id, jint rows, jint cols) {

    int idx = session_id & 0x0FFFFFFF;
    if (idx < 0 || idx >= num_sessions) return;

    struct winsize ws;
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    ioctl(sessions[idx].master, TIOCSWINSZ, &ws);
    kill(sessions[idx].pid, SIGWINCH);
}

JNIEXPORT jint JNICALL
Java_com_nousresearch_reasonix_TerminalBridge_waitPty(
    JNIEnv *env, jclass clazz,
    jint session_id) {

    int idx = session_id & 0x0FFFFFFF;
    if (idx < 0 || idx >= num_sessions) return -1;
    if (sessions[idx].exited) return sessions[idx].exit_status;

    int status;
    pid_t result = waitpid(sessions[idx].pid, &status, WNOHANG);
    if (result == 0) return -256;
    if (result < 0) return -1;

    sessions[idx].exited = 1;
    sessions[idx].exit_status = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
    return sessions[idx].exit_status;
}

JNIEXPORT void JNICALL
Java_com_nousresearch_reasonix_TerminalBridge_closePty(
    JNIEnv *env, jclass clazz,
    jint session_id) {

    int idx = session_id & 0x0FFFFFFF;
    if (idx < 0 || idx >= num_sessions) return;

    close(sessions[idx].master);
    kill(sessions[idx].pid, SIGTERM);
    usleep(100000);
    kill(sessions[idx].pid, SIGKILL);
    waitpid(sessions[idx].pid, NULL, 0);
    sessions[idx].exited = 1;
}
