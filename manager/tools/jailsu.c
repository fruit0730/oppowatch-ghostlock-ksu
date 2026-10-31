/*
 * libksud.so - KernelSU watch port root bridge.
 *
 * Executed by libsu as the "root shell". It is a plain TCP client for the
 * GhostLock root shell listening on 127.0.0.1:25555 and proxies stdin/stdout
 * byte-for-byte, so all existing libsu based code keeps working.
 *
 * Build:
 *   armv7a-linux-androideabi30-clang -O2 -fPIE -pie jailsu.c -o libksud.so
 */
#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#ifndef KSUW_DEFAULT_PORT
#define KSUW_DEFAULT_PORT 25555
#endif

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int connect_root(int port, int timeout_ms) {
    long long deadline = now_ms() + timeout_ms;
    for (;;) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) return -1;
        struct sockaddr_in sa;
        memset(&sa, 0, sizeof(sa));
        sa.sin_family = AF_INET;
        sa.sin_port = htons((unsigned short)port);
        sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (connect(fd, (struct sockaddr *)&sa, sizeof(sa)) == 0) return fd;
        close(fd);
        if (now_ms() >= deadline) return -1;
        usleep(100 * 1000);
    }
}

static int write_all(int fd, const char *buf, size_t len) {
    while (len > 0) {
        ssize_t n = write(fd, buf, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        buf += n;
        len -= (size_t)n;
    }
    return 0;
}

/*
 * libsu ends a job with:
 *   echo <uuid>            (stdout)
 *   echo <uuid> >&2        (stderr)
 *   echo $__RET            (stdout)
 * and blocks until BOTH gobblers have seen the uuid.
 *
 * The GhostLock shell has fd 2 dup'ed onto the same socket, so the stderr copy
 * arrives interleaved on stdout and the job would wait forever. Buffer uuid
 * lines and route the duplicate copy to the proxy's own stderr (which is a
 * separate pipe to libsu), restoring the two-stream contract.
 */
#define LINE_MAX 16384
static char g_line[LINE_MAX];
static size_t g_line_len;
static char g_pending_uuid[64];
static size_t g_pending_len;

static int is_hex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

static int is_uuid_line(const char *s, size_t n) {
    if (n != 36) return 0;
    for (size_t i = 0; i < n; i++) {
        char c = s[i];
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (c != '-') return 0;
        } else if (!is_hex(c)) {
            return 0;
        }
    }
    return 1;
}

static void emit_stdout(const char *s, size_t n) {
    write_all(STDOUT_FILENO, s, n);
}

static void emit_stderr(const char *s, size_t n) {
    write_all(STDERR_FILENO, s, n);
}

static void flush_pending_uuid(int with_newline) {
    if (g_pending_len == 0) return;
    emit_stdout(g_pending_uuid, g_pending_len);
    if (with_newline) emit_stdout("\n", 1);
    g_pending_len = 0;
}

static void handle_line(const char *s, size_t n, int with_newline) {
    if (is_uuid_line(s, n)) {
        if (g_pending_len == 0) {
            if (n < sizeof(g_pending_uuid)) {
                memcpy(g_pending_uuid, s, n);
                g_pending_len = n;
                return;
            }
        } else if (g_pending_len == n && memcmp(g_pending_uuid, s, n) == 0) {
            /* first copy -> stdout, second copy (the >&2 echo) -> stderr */
            emit_stdout(g_pending_uuid, g_pending_len);
            if (with_newline) emit_stdout("\n", 1);
            emit_stderr(s, n);
            if (with_newline) emit_stderr("\n", 1);
            g_pending_len = 0;
            return;
        }
        flush_pending_uuid(1);
        if (n < sizeof(g_pending_uuid)) {
            memcpy(g_pending_uuid, s, n);
            g_pending_len = n;
            return;
        }
    }

    flush_pending_uuid(1);
    emit_stdout(s, n);
    if (with_newline) emit_stdout("\n", 1);
}

static void feed_bytes(const char *buf, size_t len) {
    for (size_t i = 0; i < len; i++) {
        char c = buf[i];
        if (c == '\n') {
            handle_line(g_line, g_line_len, 1);
            g_line_len = 0;
        } else if (g_line_len == sizeof(g_line)) {
            handle_line(g_line, g_line_len, 0);
            g_line_len = 0;
            g_line[g_line_len++] = c;
        } else {
            g_line[g_line_len++] = c;
        }
    }
}

static void flush_stream(void) {
    if (g_line_len) {
        handle_line(g_line, g_line_len, 0);
        g_line_len = 0;
    }
    flush_pending_uuid(0);
}

/*
 * The remote shell is `sh -i`; consume its banner/prompt and make sure no
 * prompt is emitted later, otherwise the first libsu job would parse it as
 * command output.
 *
 * Returns bytes already read after the marker (kept in carry), or -1.
 */
static int handshake(int fd, char *carry, size_t carry_cap) {
    char marker[64];
    snprintf(marker, sizeof(marker), "__KSUW_READY_%d__", (int)getpid());
    char script[256];
    snprintf(script, sizeof(script),
             "PS1=''\nunset PS1\nstty -echo 2>/dev/null\necho %s\n", marker);
    if (write_all(fd, script, strlen(script)) != 0) return -1;

    char buf[4096];
    size_t used = 0;
    long long deadline = now_ms() + 4000;
    ssize_t mpos = -1;
    for (;;) {
        mpos = -1;
        for (size_t i = 0; i + strlen(marker) <= used; i++) {
            if (memcmp(buf + i, marker, strlen(marker)) == 0) {
                mpos = (ssize_t)i;
                break;
            }
        }
        if (mpos >= 0) break;
        long long left = deadline - now_ms();
        if (left <= 0) break;
        struct pollfd pfd = {.fd = fd, .events = POLLIN};
        int pr = poll(&pfd, 1, (int)left);
        if (pr <= 0) break;
        ssize_t n = read(fd, buf + used, sizeof(buf) - used);
        if (n <= 0) return -1;
        used += (size_t)n;
        if (used == sizeof(buf)) {
            /* Marker not found within 4K: give up on the banner, drop it. */
            used = 0;
            break;
        }
    }

    size_t skip = 0;
    if (mpos >= 0) {
        skip = (size_t)mpos + strlen(marker);
        if (skip < used && buf[skip] == '\r') skip++;
        if (skip < used && buf[skip] == '\n') skip++;
    }
    size_t carry_len = used > skip ? used - skip : 0;
    if (carry_len > carry_cap) carry_len = carry_cap;
    if (carry_len) memcpy(carry, buf + skip, carry_len);
    return (int)carry_len;
}

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    signal(SIGPIPE, SIG_IGN);

    int port = KSUW_DEFAULT_PORT;
    const char *env_port = getenv("KSUW_ROOT_PORT");
    if (env_port && *env_port) {
        int p = atoi(env_port);
        if (p > 0 && p < 65536) port = p;
    }

    int fd = connect_root(port, 1500);
    if (fd < 0) {
        /* No jailbreak root on this boot: let libsu fall back. */
        fprintf(stderr, "jailsu: 127.0.0.1:%d not reachable\n", port);
        return 1;
    }

    char carry[8192];
    int carry_len = handshake(fd, carry, sizeof(carry));
    if (carry_len < 0) {
        close(fd);
        return 1;
    }
    if (carry_len > 0) {
        feed_bytes(carry, (size_t)carry_len);
    }

    int stdin_open = 1;
    char buf[8192];
    for (;;) {
        struct pollfd fds[2];
        nfds_t nfds = 0;
        int stdin_idx = -1, sock_idx = 0;
        if (stdin_open) {
            stdin_idx = (int)nfds;
            fds[nfds].fd = STDIN_FILENO;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            nfds++;
        }
        sock_idx = (int)nfds;
        fds[nfds].fd = fd;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        nfds++;

        int pr = poll(fds, nfds, -1);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (stdin_idx >= 0 && (fds[stdin_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0) {
                stdin_open = 0;
                shutdown(fd, SHUT_WR);
            } else if (write_all(fd, buf, (size_t)n) != 0) {
                break;
            }
        }

        if (fds[sock_idx].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(fd, buf, sizeof(buf));
            if (n <= 0) break;
            feed_bytes(buf, (size_t)n);
        }
    }

    flush_stream();
    close(fd);
    return 0;
}
