/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef __linux__
#include <sys/syscall.h>
#endif
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc != 4 && argc != 5) {
        fprintf(stderr, "Usage: %s <pid> <signal> <cookie> [tid]\n", argv[0]);
        return 2;
    }

    char* end;
    long pid = strtol(argv[1], &end, 10);
    if (*end != 0 || pid <= 0) {
        fprintf(stderr, "Invalid pid\n");
        return 2;
    }
    long signal = strtol(argv[2], &end, 10);
    if (*end != 0 || signal <= 0) {
        fprintf(stderr, "Invalid signal\n");
        return 2;
    }
    unsigned long long cookie = strtoull(argv[3], &end, 0);
    if (*end != 0) {
        fprintf(stderr, "Invalid cookie\n");
        return 2;
    }

    union sigval value;
    value.sival_ptr = (void*)(uintptr_t)cookie;
    int result;
    if (argc == 4) {
        result = sigqueue((pid_t)pid, (int)signal, value);
    } else {
#ifdef __linux__
        long tid = strtol(argv[4], &end, 10);
        if (*end != 0 || tid <= 0) {
            fprintf(stderr, "Invalid tid\n");
            return 2;
        }
        siginfo_t info;
        memset(&info, 0, sizeof(info));
        info.si_signo = (int)signal;
        info.si_code = SI_QUEUE;
        info.si_pid = getpid();
        info.si_uid = getuid();
        info.si_value = value;
        result = syscall(SYS_rt_tgsigqueueinfo, (pid_t)pid, (pid_t)tid, (int)signal, &info);
#else
        fprintf(stderr, "Thread-directed cookie delivery requires Linux\n");
        return 2;
#endif
    }
    if (result != 0) {
        perror(argc == 4 ? "sigqueue" : "rt_tgsigqueueinfo");
        return errno == 0 ? 1 : errno;
    }
    return 0;
}
