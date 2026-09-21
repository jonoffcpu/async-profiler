/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

#include <jni.h>
#include <pthread.h>
#include <signal.h>
#ifdef __linux__
#include <sys/syscall.h>
#include <unistd.h>
#endif

JNIEXPORT void JNICALL Java_test_signal_SignalCookieTrapApp_beginMarker(JNIEnv* env, jclass cls) {
}

JNIEXPORT void JNICALL Java_test_signal_SignalCookieTrapApp_endMarker(JNIEnv* env, jclass cls) {
}

JNIEXPORT jint JNICALL Java_test_signal_SignalCookieMaskApp_currentThreadId(JNIEnv* env, jclass cls) {
#ifdef __linux__
    return (jint)syscall(SYS_gettid);
#else
    return 0;
#endif
}

JNIEXPORT jint JNICALL Java_test_signal_SignalCookieMaskApp_setSignalBlocked(
        JNIEnv* env, jclass cls, jint signal, jboolean blocked) {
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, signal);
    return pthread_sigmask(blocked ? SIG_BLOCK : SIG_UNBLOCK, &set, NULL);
}
