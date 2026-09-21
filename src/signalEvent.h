/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

#ifndef _SIGNALEVENT_H
#define _SIGNALEVENT_H

#include <signal.h>
#include "engine.h"
#include "event.h"

class Writer;

enum SignalCookieLookup {
    SIGNAL_COOKIE_INACTIVE,
    SIGNAL_COOKIE_ACTIVE,
    SIGNAL_COOKIE_TERMINAL,
    SIGNAL_COOKIE_MISMATCH
};

class SignalEvent : public Engine {
  private:
    static int _signal;
    static long _interval;
    static volatile u64 _last_sample;
    static volatile u64 _handler_gate;
    static volatile u64 _failed_traces;
    static volatile u64 _cookie_handler_gate;
    static volatile u32 _next_capture_epoch;
    static volatile u32 _capture_epoch;
    static bool _capture_ready;
    static int _cookie_signal;
    static bool _cookie_coalescing;
    static char _session_id[37];
    static volatile u64 _admitted_signals;
    static volatile u64 _invalid_signal_code;
    static volatile u64 _zero_cookie;
    static volatile u64 _zero_sequence;
    static volatile u64 _stale_epoch;
    static volatile u64 _accepted_cookies;
    static volatile u64 _capture_failures;
    static volatile u64 _submitted_samples;
    static SignalCaptureStats _stopped_stats;
    static u64 _stopped_at;
    static bool _terminal_valid;
    static bool _terminal_finalized;
    static char _terminal_session_id[37];
    static u32 _terminal_epoch;
    static int _terminal_signal;
    static bool _terminal_coalescing;
    static SignalCaptureStats _terminal_stats;
    static u64 _terminal_stopped_at;
    static char _terminal_reason[32];

    static void signalHandler(int signo, siginfo_t* siginfo, void* ucontext);
    static void cookieSignalHandler(int signo, siginfo_t* siginfo, void* ucontext);
    static bool enterSignalHandler();
    static void leaveSignalHandler(int saved_errno);
    static bool enterCookieSignalHandler();
    static void leaveCookieSignalHandler(int saved_errno);
    static void closeSignalHandlerGate();
    static u64 closeCookieSignalHandlerGate();
    static bool validSessionId(const char* id);
    static bool allowedSignalCode(int code);
    static void resetCaptureCounters();
    static void writeStats(Writer& out, const SignalCaptureStats& stats);
    static Error reserveCaptureEpoch(Arguments& args);

  public:
    const char* type() {
        return EVENT_SIGNAL;
    }

    const char* title() {
        return "Externally triggered samples";
    }

    const char* units() {
        return "samples";
    }

    long interval() {
        return _interval;
    }

    Error start(Arguments& args);
    void stop();

    static Error validateCookieArguments(Arguments& args);
    static Error reserveCookieCapture(Arguments& args);
    static void publishCookieCapture();
    static void abortCookieCapture();
    static void stopCookieCapture();
    static void finishCookieCapture(bool finalized, const char* reason);
    static bool cookieCaptureReady();
    static SignalCookieLookup lookupCapture(const char* id, u64 epoch);
    static u32 captureEpoch();
    static SignalCaptureStats captureStats();
    static void recordCaptureStats();
    static void writeCaptureStatus(Writer& out);
    static void writeTerminalStatus(Writer& out);
#ifdef ASYNC_PROFILER_TEST
    static void setNextCaptureEpochForTest(u32 epoch);
    static Error reserveCaptureEpochForTest(Arguments& args);
#endif
};

#endif // _SIGNALEVENT_H
