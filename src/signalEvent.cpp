/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

#include <errno.h>
#include <sched.h>
#include <stdlib.h>
#include <string.h>
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#endif
#include "event.h"
#include "log.h"
#include "os.h"
#include "profiler.h"
#include "signalEvent.h"
#include "tsc.h"
#include "vmEntry.h"
#include "writer.h"


int SignalEvent::_signal;
long SignalEvent::_interval;
volatile u64 SignalEvent::_last_sample;
static const u64 SIGNAL_HANDLER_GATE_CLOSED = 1ULL << 63;
static const u64 SIGNAL_HANDLER_GATE_COUNT_MASK = SIGNAL_HANDLER_GATE_CLOSED - 1;

#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
// Shared only by test builds. A separate test process controls this page so it
// can release a paused signal handler while the normal attach command is
// blocked in stop(). Production builds contain neither the checks nor mapping.
static const u32 SIGNAL_TEST_GATE_MAGIC = 0x53474654;
static const u32 SIGNAL_TEST_GATE_VERSION = 1;

enum SignalTestPausePoint {
    SIGNAL_TEST_AFTER_ADMISSION = 1,
    SIGNAL_TEST_AFTER_EPOCH_READ = 2,
    SIGNAL_TEST_BEFORE_RECORDING = 3,
};

struct SignalTestGate {
    volatile u32 magic;
    volatile u32 version;
    volatile u32 requested_point;
    volatile u32 requested_token;
    volatile u32 reached_token;
    volatile u32 release_token;
    volatile u32 observed_epoch;
    volatile u32 closed_token;
    volatile u32 closed_epoch;
    volatile u32 closed_ready;
    volatile u32 drained_token;
    volatile u32 drained_epoch;
    volatile u32 drained_ready;
    volatile u32 recorded_token;
    volatile u32 stats_token;
    volatile u32 finalized_token;
    volatile u32 milestone_sequence;
    volatile u32 closed_order;
    volatile u32 drained_order;
    volatile u32 stats_order;
    volatile u32 finalized_order;
    volatile u32 attached;
};

static SignalTestGate* _signal_test_gate;

static void initializeSignalTestGate() {
    if (_signal_test_gate != NULL) return;

    const char* path = getenv("ASYNC_PROFILER_SIGNAL_GATE_TEST");
    if (path == NULL || *path == 0) return;

    int fd = open(path, O_RDWR);
    if (fd < 0) return;

    void* page = mmap(NULL, sizeof(SignalTestGate), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (page == MAP_FAILED) return;

    SignalTestGate* gate = (SignalTestGate*)page;
    if (__atomic_load_n(&gate->magic, __ATOMIC_ACQUIRE) != SIGNAL_TEST_GATE_MAGIC ||
            __atomic_load_n(&gate->version, __ATOMIC_RELAXED) != SIGNAL_TEST_GATE_VERSION) {
        munmap(page, sizeof(SignalTestGate));
        return;
    }
    // Tell the controlling test that this build has the pause points, so it
    // can skip instead of timing out against a production library.
    __atomic_store_n(&gate->attached, 1, __ATOMIC_RELEASE);
    _signal_test_gate = gate;
}

static u32 signalTestToken() {
    SignalTestGate* gate = _signal_test_gate;
    return gate == NULL ? 0 : __atomic_load_n(&gate->requested_token, __ATOMIC_ACQUIRE);
}

static u32 signalTestNextOrder() {
    return __atomic_add_fetch(&_signal_test_gate->milestone_sequence, 1, __ATOMIC_RELAXED);
}

static void signalTestPause(u32 point, u32 epoch) {
    SignalTestGate* gate = _signal_test_gate;
    if (gate == NULL || __atomic_load_n(&gate->requested_point, __ATOMIC_ACQUIRE) != point) return;

    u32 token = __atomic_load_n(&gate->requested_token, __ATOMIC_ACQUIRE);
    if (token == 0) return;

    if (epoch != 0) {
        __atomic_store_n(&gate->observed_epoch, epoch, __ATOMIC_RELEASE);
    }
    __atomic_store_n(&gate->reached_token, token, __ATOMIC_RELEASE);
    while (__atomic_load_n(&gate->release_token, __ATOMIC_ACQUIRE) != token) {
        // Test-only deterministic pause in the sampled thread.
    }
}

static void signalTestGateClosed(u32 token, u32 epoch, bool ready) {
    SignalTestGate* gate = _signal_test_gate;
    if (gate == NULL || token == 0) return;
    __atomic_store_n(&gate->closed_epoch, epoch, __ATOMIC_RELAXED);
    __atomic_store_n(&gate->closed_ready, ready ? 1 : 0, __ATOMIC_RELAXED);
    __atomic_store_n(&gate->closed_order, signalTestNextOrder(), __ATOMIC_RELAXED);
    __atomic_store_n(&gate->closed_token, token, __ATOMIC_RELEASE);
}

static void signalTestGateDrained(u32 token, u32 epoch, bool ready) {
    SignalTestGate* gate = _signal_test_gate;
    if (gate == NULL || token == 0) return;
    __atomic_store_n(&gate->drained_epoch, epoch, __ATOMIC_RELAXED);
    __atomic_store_n(&gate->drained_ready, ready ? 1 : 0, __ATOMIC_RELAXED);
    __atomic_store_n(&gate->drained_order, signalTestNextOrder(), __ATOMIC_RELAXED);
    __atomic_store_n(&gate->drained_token, token, __ATOMIC_RELEASE);
}

static void signalTestMilestone(volatile u32* field, u32 token) {
    if (_signal_test_gate != NULL && token != 0) {
        __atomic_store_n(field, token, __ATOMIC_RELEASE);
    }
}
#endif

volatile u64 SignalEvent::_handler_gate = SIGNAL_HANDLER_GATE_CLOSED;
volatile u64 SignalEvent::_cookie_handler_gate = SIGNAL_HANDLER_GATE_CLOSED;
volatile u64 SignalEvent::_failed_traces;
volatile u32 SignalEvent::_next_capture_epoch;
volatile u32 SignalEvent::_capture_epoch;
bool SignalEvent::_capture_ready;
int SignalEvent::_cookie_signal;
bool SignalEvent::_cookie_coalescing;
char SignalEvent::_session_id[37];
volatile u64 SignalEvent::_admitted_signals;
volatile u64 SignalEvent::_invalid_signal_code;
volatile u64 SignalEvent::_zero_cookie;
volatile u64 SignalEvent::_zero_sequence;
volatile u64 SignalEvent::_stale_epoch;
volatile u64 SignalEvent::_accepted_cookies;
volatile u64 SignalEvent::_capture_failures;
volatile u64 SignalEvent::_submitted_samples;
SignalCaptureStats SignalEvent::_stopped_stats;
u64 SignalEvent::_stopped_at;
bool SignalEvent::_terminal_valid;
bool SignalEvent::_terminal_finalized;
char SignalEvent::_terminal_session_id[37];
u32 SignalEvent::_terminal_epoch;
int SignalEvent::_terminal_signal;
bool SignalEvent::_terminal_coalescing;
SignalCaptureStats SignalEvent::_terminal_stats;
u64 SignalEvent::_terminal_stopped_at;
char SignalEvent::_terminal_reason[32];

void SignalEvent::signalHandler(int signo, siginfo_t* siginfo, void* ucontext) {
    int saved_errno = errno;
    if (!enterSignalHandler()) {
        errno = saved_errno;
        return;
    }
    if (!_enabled) {
        leaveSignalHandler(saved_errno);
        return;
    }

    u64 now = OS::nanotime();
    u64 last_sample = __atomic_load_n(&_last_sample, __ATOMIC_RELAXED);
    while (true) {
        if (now - last_sample < (u64)_interval) {
            leaveSignalHandler(saved_errno);
            return;
        }
        if (__atomic_compare_exchange_n(&_last_sample, &last_sample, now, false,
                                        __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
            break;
        }
    }

    ExecutionEvent execution_event(TSC::ticks());
    if (Profiler::instance()->recordSample(ucontext, 1, EXECUTION_SAMPLE, &execution_event) == 0) {
        __atomic_add_fetch(&_failed_traces, 1, __ATOMIC_RELAXED);
    }
    leaveSignalHandler(saved_errno);
}

void SignalEvent::cookieSignalHandler(int signo, siginfo_t* siginfo, void* ucontext) {
    int saved_errno = errno;
    if (!enterCookieSignalHandler()) {
        errno = saved_errno;
        return;
    }
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
    signalTestPause(SIGNAL_TEST_AFTER_ADMISSION, 0);
#endif

    u64 signal_ticks = TSC::ticks();
    u64 now = OS::nanotime();
    __atomic_add_fetch(&_admitted_signals, 1, __ATOMIC_RELAXED);
    if (!allowedSignalCode(siginfo == NULL ? 0 : siginfo->si_code)) {
        __atomic_add_fetch(&_invalid_signal_code, 1, __ATOMIC_RELAXED);
        leaveCookieSignalHandler(saved_errno);
        return;
    }

    u64 cookie = siginfo == NULL ? 0 : (u64)(uintptr_t)siginfo->si_value.sival_ptr;
    if (cookie == 0) {
        __atomic_add_fetch(&_zero_cookie, 1, __ATOMIC_RELAXED);
        leaveCookieSignalHandler(saved_errno);
        return;
    }
    if ((u32)cookie == 0) {
        __atomic_add_fetch(&_zero_sequence, 1, __ATOMIC_RELAXED);
        leaveCookieSignalHandler(saved_errno);
        return;
    }
    u32 active_epoch = __atomic_load_n(&_capture_epoch, __ATOMIC_RELAXED);
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
    signalTestPause(SIGNAL_TEST_AFTER_EPOCH_READ, active_epoch);
#endif
    if ((u32)(cookie >> 32) != active_epoch) {
        __atomic_add_fetch(&_stale_epoch, 1, __ATOMIC_RELAXED);
        leaveCookieSignalHandler(saved_errno);
        return;
    }

    __atomic_add_fetch(&_accepted_cookies, 1, __ATOMIC_RELAXED);
    SignalSampleEvent signal_event(signal_ticks, cookie, now);
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
    signalTestPause(SIGNAL_TEST_BEFORE_RECORDING, active_epoch);
    u32 test_token = signalTestToken();
#endif
    if (Profiler::instance()->recordSample(ucontext, 1, SIGNAL_SAMPLE, &signal_event) == 0) {
        __atomic_add_fetch(&_capture_failures, 1, __ATOMIC_RELAXED);
    } else {
        __atomic_add_fetch(&_submitted_samples, 1, __ATOMIC_RELAXED);
    }
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
    if (_signal_test_gate != NULL) {
        signalTestMilestone(&_signal_test_gate->recorded_token, test_token);
    }
#endif
    leaveCookieSignalHandler(saved_errno);
}

bool SignalEvent::allowedSignalCode(int code) {
#ifdef __linux__
    return code == SI_KERNEL || code == SI_QUEUE;
#else
    return false;
#endif
}

bool SignalEvent::validSessionId(const char* id) {
    if (id == NULL || strlen(id) != 36) return false;
    for (int i = 0; i < 36; i++) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (id[i] != '-') return false;
        } else if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f'))) {
            return false;
        }
    }
    return true;
}

void SignalEvent::resetCaptureCounters() {
    _admitted_signals = 0;
    _invalid_signal_code = 0;
    _zero_cookie = 0;
    _zero_sequence = 0;
    _stale_epoch = 0;
    _accepted_cookies = 0;
    _capture_failures = 0;
    _submitted_samples = 0;
}

Error SignalEvent::validateCookieArguments(Arguments& args) {
    if (!args._signal_cookie) return Error::OK;
    if (!OS::isLinux() || sizeof(void*) != sizeof(u64)) {
        return Error("signalcookie requires 64-bit Linux");
    }
    if (!validSessionId(args._signal_id)) {
        return Error("signalcookie requires a canonical lowercase signalid UUID");
    }
    if (args._signal_epoch != 0) {
        return Error("signalepoch is only valid for identity-guarded stop");
    }
    if (args._action != ACTION_START) {
        return Error("signalcookie capture can only be started with the start action");
    }
    if (args._output != OUTPUT_JFR) {
        return Error("signalcookie requires JFR output");
    }
    if (args._loop != 0 || args._nostop) {
        return Error("signalcookie does not support loop or nostop");
    }
    return Error::OK;
}

Error SignalEvent::reserveCaptureEpoch(Arguments& args) {
    u32 epoch = __atomic_load_n(&_next_capture_epoch, __ATOMIC_RELAXED);
    while (true) {
        if (epoch == UINT32_MAX) {
            return Error("signalcookie capture epoch exhausted");
        }
        if (__atomic_compare_exchange_n(&_next_capture_epoch, &epoch, epoch + 1, false,
                                        __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
            break;
        }
    }
    args._signal_epoch = epoch + 1;
    return Error::OK;
}

Error SignalEvent::reserveCookieCapture(Arguments& args) {
    Error error = reserveCaptureEpoch(args);
    if (error) {
        return error;
    }

    bool coalescing = args._signal_cookie_delivery == SIGNAL_COOKIE_COALESCING;
    int signal = OS::reserveCookieSignal(args._cookie_signal, args._signal, coalescing, cookieSignalHandler);
    if (signal == 0) {
        return Error("Cannot reserve a dedicated signal for signalcookie delivery mode");
    }

    args._cookie_signal = signal;
    __atomic_store_n(&_capture_epoch, args._signal_epoch, __ATOMIC_RELAXED);
    memcpy(_session_id, args._signal_id, sizeof(_session_id));
    _cookie_signal = signal;
    _cookie_coalescing = coalescing;
    _capture_ready = false;
    _stopped_at = 0;
    __atomic_store_n(&_cookie_handler_gate, SIGNAL_HANDLER_GATE_CLOSED, __ATOMIC_RELEASE);
    resetCaptureCounters();
    return Error::OK;
}

void SignalEvent::publishCookieCapture() {
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
    initializeSignalTestGate();
#endif
    _capture_ready = true;
    __atomic_store_n(&_cookie_handler_gate, 0, __ATOMIC_RELEASE);
}

bool SignalEvent::cookieCaptureReady() {
    return _capture_ready;
}

u32 SignalEvent::captureEpoch() {
    return __atomic_load_n(&_capture_epoch, __ATOMIC_RELAXED);
}

SignalCaptureStats SignalEvent::captureStats() {
    SignalCaptureStats stats = {
        __atomic_load_n(&_admitted_signals, __ATOMIC_RELAXED),
        __atomic_load_n(&_invalid_signal_code, __ATOMIC_RELAXED),
        __atomic_load_n(&_zero_cookie, __ATOMIC_RELAXED),
        __atomic_load_n(&_zero_sequence, __ATOMIC_RELAXED),
        __atomic_load_n(&_stale_epoch, __ATOMIC_RELAXED),
        __atomic_load_n(&_accepted_cookies, __ATOMIC_RELAXED),
        __atomic_load_n(&_capture_failures, __ATOMIC_RELAXED),
        __atomic_load_n(&_submitted_samples, __ATOMIC_RELAXED),
    };
    return stats;
}

void SignalEvent::recordCaptureStats() {
    Profiler::instance()->jfr()->recordSignalCaptureStats(
        _session_id, captureEpoch(), _stopped_stats);
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
    if (_signal_test_gate != NULL) {
        __atomic_store_n(&_signal_test_gate->stats_order, signalTestNextOrder(), __ATOMIC_RELAXED);
        signalTestMilestone(&_signal_test_gate->stats_token, signalTestToken());
    }
#endif
}

void SignalEvent::writeCaptureStatus(Writer& out) {
    out << "signal-capture-v1 id=" << _session_id << " signal=" << _cookie_signal
        << " epoch=" << (u64)captureEpoch()
        << " delivery=" << (_cookie_coalescing ? "coalescing" : "queued") << '\n';
}

void SignalEvent::writeStats(Writer& out, const SignalCaptureStats& stats) {
    out << " admitted=" << stats.admitted_signals
        << " invalid-code=" << stats.invalid_signal_code
        << " zero-cookie=" << stats.zero_cookie
        << " zero-sequence=" << stats.zero_sequence
        << " stale-epoch=" << stats.stale_epoch
        << " accepted=" << stats.accepted_cookies
        << " capture-failures=" << stats.capture_failures
        << " submitted=" << stats.submitted_samples;
}

void SignalEvent::abortCookieCapture() {
    closeCookieSignalHandlerGate();
    _capture_ready = false;
    __atomic_store_n(&_capture_epoch, 0, __ATOMIC_RELAXED);
    _session_id[0] = 0;
}

void SignalEvent::stopCookieCapture() {
    _stopped_at = closeCookieSignalHandlerGate();
    _capture_ready = false;
    _stopped_stats = captureStats();
}

void SignalEvent::finishCookieCapture(bool finalized, const char* reason) {
    memcpy(_terminal_session_id, _session_id, sizeof(_terminal_session_id));
    _terminal_epoch = captureEpoch();
    _terminal_signal = _cookie_signal;
    _terminal_coalescing = _cookie_coalescing;
    _terminal_stats = _stopped_stats;
    _terminal_stopped_at = _stopped_at;
    _terminal_finalized = finalized;
    snprintf(_terminal_reason, sizeof(_terminal_reason), "%s", reason == NULL ? "unknown" : reason);
    _terminal_valid = true;
    __atomic_store_n(&_capture_epoch, 0, __ATOMIC_RELAXED);
    _session_id[0] = 0;
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
    if (_signal_test_gate != NULL) {
        __atomic_store_n(&_signal_test_gate->finalized_order, signalTestNextOrder(), __ATOMIC_RELAXED);
        signalTestMilestone(&_signal_test_gate->finalized_token, signalTestToken());
    }
#endif
}

SignalCookieLookup SignalEvent::lookupCapture(const char* id, u64 epoch) {
    if (_capture_ready && id != NULL && strcmp(id, _session_id) == 0 &&
            (epoch == 0 || epoch == captureEpoch())) {
        return SIGNAL_COOKIE_ACTIVE;
    }
    if (_terminal_valid && id != NULL && strcmp(id, _terminal_session_id) == 0 &&
            (epoch == 0 || epoch == _terminal_epoch)) {
        return SIGNAL_COOKIE_TERMINAL;
    }
    if (_capture_ready || _terminal_valid) {
        return SIGNAL_COOKIE_MISMATCH;
    }
    return SIGNAL_COOKIE_INACTIVE;
}

void SignalEvent::writeTerminalStatus(Writer& out) {
    out << "signal-capture-v1 stopped id=" << _terminal_session_id << " signal=" << _terminal_signal
        << " epoch=" << (u64)_terminal_epoch
        << " delivery=" << (_terminal_coalescing ? "coalescing" : "queued");
    writeStats(out, _terminal_stats);
    out << " finalized=" << (_terminal_finalized ? "true" : "false")
        << " stopped-at=" << _terminal_stopped_at
        << " reason=" << _terminal_reason << '\n';
}

#ifdef ASYNC_PROFILER_TEST
void SignalEvent::setNextCaptureEpochForTest(u32 epoch) {
    __atomic_store_n(&_next_capture_epoch, epoch, __ATOMIC_RELAXED);
}

Error SignalEvent::reserveCaptureEpochForTest(Arguments& args) {
    return reserveCaptureEpoch(args);
}
#endif

bool SignalEvent::enterSignalHandler() {
    u64 gate = __atomic_load_n(&_handler_gate, __ATOMIC_ACQUIRE);
    while ((gate & SIGNAL_HANDLER_GATE_CLOSED) == 0) {
        if (__atomic_compare_exchange_n(&_handler_gate, &gate, gate + 1, false,
                                        __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) {
            return true;
        }
    }
    return false;
}

void SignalEvent::leaveSignalHandler(int saved_errno) {
    __atomic_sub_fetch(&_handler_gate, 1, __ATOMIC_RELEASE);
    errno = saved_errno;
}

bool SignalEvent::enterCookieSignalHandler() {
    u64 gate = __atomic_load_n(&_cookie_handler_gate, __ATOMIC_ACQUIRE);
    while ((gate & SIGNAL_HANDLER_GATE_CLOSED) == 0) {
        if (__atomic_compare_exchange_n(&_cookie_handler_gate, &gate, gate + 1, false,
                                        __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) {
            return true;
        }
    }
    return false;
}

void SignalEvent::leaveCookieSignalHandler(int saved_errno) {
    __atomic_sub_fetch(&_cookie_handler_gate, 1, __ATOMIC_RELEASE);
    errno = saved_errno;
}

void SignalEvent::closeSignalHandlerGate() {
    // Atomically close admission before waiting. Once the admitted count
    // reaches zero, no signal handler can still be recording a sample.
    __atomic_fetch_or(&_handler_gate, SIGNAL_HANDLER_GATE_CLOSED, __ATOMIC_ACQ_REL);
    while ((__atomic_load_n(&_handler_gate, __ATOMIC_ACQUIRE) &
            SIGNAL_HANDLER_GATE_COUNT_MASK) != 0) {
        sched_yield();
    }
}

u64 SignalEvent::closeCookieSignalHandlerGate() {
    __atomic_fetch_or(&_cookie_handler_gate, SIGNAL_HANDLER_GATE_CLOSED, __ATOMIC_ACQ_REL);
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
    u32 test_token = signalTestToken();
    signalTestGateClosed(test_token, captureEpoch(), _capture_ready);
#endif
    // This is the admission cutoff: after CLOSED is visible, no new handler can
    // enter. Existing handlers may still be draining when the timestamp is read.
    u64 stopped_at = OS::nanotime();
    while ((__atomic_load_n(&_cookie_handler_gate, __ATOMIC_ACQUIRE) &
            SIGNAL_HANDLER_GATE_COUNT_MASK) != 0) {
        sched_yield();
    }
#if defined(ASYNC_PROFILER_TEST) && defined(__linux__)
    signalTestGateDrained(test_token, captureEpoch(), _capture_ready);
#endif
    return stopped_at;
}

Error SignalEvent::start(Arguments& args) {
    if (!VM::loaded()) {
        return Error("signal event requires a JVM");
    }

    if (args._interval < 0) {
        return Error("interval must be positive");
    }
    _interval = args._interval ? args._interval : DEFAULT_INTERVAL;
    _last_sample = 0;
    _failed_traces = 0;

    _signal = args._signal == 0 ? SIGPROF : args._signal & 0xff;
    // Keep this disabled handler installed after stop, like async-profiler's
    // other sampling engines. A thread may retain a pending profiling signal;
    // restoring the process's previous (possibly default) disposition could
    // otherwise terminate the JVM when that thread later unmasks the signal.
    OS::installSignalHandler(_signal, signalHandler);
    __atomic_store_n(&_handler_gate, 0, __ATOMIC_RELEASE);
    return Error::OK;
}

void SignalEvent::stop() {
    closeSignalHandlerGate();
    _capture_ready = false;
    u64 failed_traces = __atomic_load_n(&_failed_traces, __ATOMIC_RELAXED);
    if (failed_traces != 0) {
        Log::warn("Signal event failed to obtain %llu stack traces", failed_traces);
    }
}
