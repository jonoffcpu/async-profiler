/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "arguments.h"
#include "os.h"
#include "signalEvent.h"
#include "testRunner.hpp"

#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>

static Error parseAndValidate(const char* command) {
    Arguments args;
    Error error = args.parse(command);
    return error ? error : SignalEvent::validateCookieArguments(args);
}

TEST_CASE(SignalCookie_validate_supported_mode) {
    Error error = parseAndValidate(
        "start,event=signal,jfr,file=capture.jfr,signalcookie,"
        "signalid=01234567-89ab-cdef-0123-456789abcdef");
    ASSERT_FALSE(error);
}

TEST_CASE(SignalCookie_rejects_noncanonical_uuid) {
    Error error = parseAndValidate(
        "start,event=signal,jfr,file=capture.jfr,signalcookie,"
        "signalid=01234567-89AB-cdef-0123-456789abcdef");
    ASSERT(error);
    ASSERT_EQ(strcmp(error.message(), "signalcookie requires a canonical lowercase signalid UUID"), 0);
}

TEST_CASE(SignalCookie_allows_combined_events_and_normal_controls) {
    Error error = parseAndValidate(
        "start,event=cpu,interval=1ms,alloc,wall=10ms,lock=1us,timeout=1s,"
        "jfrsync=profile,ratelimit=cpu:1000,begin=kill,end=getpid,"
        "filter=2147483647,jfr,file=capture.jfr,signalcookie,"
        "signalid=01234567-89ab-cdef-0123-456789abcdef");
    ASSERT_FALSE(error);
}

TEST_CASE(SignalCookie_rejects_unsafe_lifecycle_modes) {
    Error loop_error = parseAndValidate(
        "start,event=cpu,loop=1s,jfr,file=capture.jfr,signalcookie,"
        "signalid=01234567-89ab-cdef-0123-456789abcdef");
    ASSERT(loop_error);
    ASSERT_EQ(strcmp(loop_error.message(), "signalcookie does not support loop or nostop"), 0);

    Error nostop_error = parseAndValidate(
        "start,event=cpu,begin=kill,end=getpid,nostop,jfr,file=capture.jfr,signalcookie,"
        "signalid=01234567-89ab-cdef-0123-456789abcdef");
    ASSERT(nostop_error);
    ASSERT_EQ(strcmp(nostop_error.message(), "signalcookie does not support loop or nostop"), 0);
}

TEST_CASE(SignalCookie_rejects_non_jfr_output) {
    Error error = parseAndValidate(
        "start,event=cpu,collapsed,file=capture.txt,signalcookie,"
        "signalid=01234567-89ab-cdef-0123-456789abcdef");
    ASSERT(error);
    ASSERT_EQ(strcmp(error.message(), "signalcookie requires JFR output"), 0);
}

TEST_CASE(SignalCookie_capture_epoch_uses_last_value_then_fails_without_wrap) {
    Arguments last;
    ASSERT_FALSE(last.parse(
        "start,event=signal,jfr,file=last.jfr,signalcookie,"
        "signalid=01234567-89ab-cdef-0123-456789abcdef"));
    ASSERT_FALSE(SignalEvent::validateCookieArguments(last));

    SignalEvent::setNextCaptureEpochForTest(UINT32_MAX - 1);
    Error last_error = SignalEvent::reserveCaptureEpochForTest(last);
    ASSERT_FALSE(last_error);
    ASSERT_EQ(last._signal_epoch, UINT32_MAX);

    Arguments exhausted;
    ASSERT_FALSE(exhausted.parse(
        "start,event=signal,jfr,file=exhausted.jfr,signalcookie,"
        "signalid=11111111-1111-1111-1111-111111111111"));
    ASSERT_FALSE(SignalEvent::validateCookieArguments(exhausted));
    Error exhausted_error = SignalEvent::reserveCaptureEpochForTest(exhausted);
    ASSERT(exhausted_error);
    ASSERT_EQ(strcmp(exhausted_error.message(), "signalcookie capture epoch exhausted"), 0);
    ASSERT_EQ(exhausted._signal_epoch, 0);

    // Restore the initial state for the remaining process-local test cases.
    SignalEvent::setNextCaptureEpochForTest(0);
}

#ifdef __linux__
static void testCookieHandler(int signo, siginfo_t* siginfo, void* ucontext) {
}

static void occupiedCookieHandler(int signo) {
}

TEST_CASE(SignalCookie_auto_signal_pool_refuses_occupied_handlers) {
    struct sigaction occupied;
    memset(&occupied, 0, sizeof(occupied));
    occupied.sa_handler = occupiedCookieHandler;
    sigemptyset(&occupied.sa_mask);

    struct sigaction saved[NSIG];
    for (int signo = SIGRTMIN; signo <= SIGRTMAX; signo++) {
        ASSERT_EQ(sigaction(signo, NULL, &saved[signo]), 0);
        ASSERT_EQ(sigaction(signo, &occupied, NULL), 0);
    }
    ASSERT_EQ(OS::reserveCookieSignal(0, 0, false, testCookieHandler), 0);
    ASSERT_EQ(errno, EBUSY);
    for (int signo = SIGRTMIN; signo <= SIGRTMAX; signo++) {
        ASSERT_EQ(sigaction(signo, &saved[signo], NULL), 0);
    }

    int standard[] = {SIGSTKFLT, SIGPWR};
    for (int signo : standard) {
        ASSERT_EQ(sigaction(signo, NULL, &saved[signo]), 0);
        ASSERT_EQ(sigaction(signo, &occupied, NULL), 0);
    }
    ASSERT_EQ(OS::reserveCookieSignal(0, 0, true, testCookieHandler), 0);
    ASSERT_EQ(errno, EBUSY);
    for (int signo : standard) {
        ASSERT_EQ(sigaction(signo, &saved[signo], NULL), 0);
    }
}

TEST_CASE(SignalCookie_failed_handler_install_does_not_reserve_signal) {
    pid_t child = fork();
    ASSERT(child >= 0);
    if (child == 0) {
        OS::failNextCookieSignalInstallForTest();
        if (OS::reserveCookieSignal(SIGRTMAX, 0, false, testCookieHandler) != 0 ||
                OS::cookieSignal() != 0 ||
                OS::reserveCookieSignal(SIGRTMAX, 0, false, testCookieHandler) != SIGRTMAX) {
            _exit(1);
        }
        _exit(0);
    }

    int status;
    ASSERT_EQ(waitpid(child, &status, 0), child);
    ASSERT(WIFEXITED(status));
    ASSERT_EQ(WEXITSTATUS(status), 0);
}

TEST_CASE(SignalCookie_signal_reservation_is_owned_and_mode_stable) {
    struct sigaction ignored;
    memset(&ignored, 0, sizeof(ignored));
    ignored.sa_handler = SIG_IGN;
    sigemptyset(&ignored.sa_mask);
    ASSERT_EQ(sigaction(SIGRTMIN, &ignored, NULL), 0);
    ASSERT_EQ(OS::reserveCookieSignal(SIGRTMIN, 0, false, testCookieHandler), 0);

    struct sigaction current;
    ASSERT_EQ(sigaction(SIGRTMIN, NULL, &current), 0);
    ASSERT_EQ(current.sa_handler, SIG_IGN);

    struct sigaction reset;
    memset(&reset, 0, sizeof(reset));
    reset.sa_handler = SIG_DFL;
    sigemptyset(&reset.sa_mask);
    ASSERT_EQ(sigaction(SIGRTMIN, &reset, NULL), 0);

    ASSERT_EQ(OS::reserveCookieSignal(SIGRTMAX, 0, false, testCookieHandler), SIGRTMAX);
    ASSERT_EQ(sigaction(SIGRTMAX, NULL, &current), 0);
    ASSERT((current.sa_flags & SA_SIGINFO) != 0);
    ASSERT((current.sa_flags & SA_RESTART) != 0);
    ASSERT((current.sa_flags & SA_NODEFER) == 0);
    ASSERT_EQ(current.sa_sigaction, testCookieHandler);

    ASSERT_EQ(OS::reserveCookieSignal(SIGRTMAX, SIGRTMAX, false, testCookieHandler), 0);
    ASSERT_EQ(OS::reserveCookieSignal(0, 0, true, testCookieHandler), 0);
    ASSERT_EQ(OS::reserveCookieSignal(SIGRTMAX, 0, false, testCookieHandler), SIGRTMAX);
}
#endif
