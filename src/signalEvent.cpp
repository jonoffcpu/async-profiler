/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

#include <errno.h>
#include <sched.h>
#include "event.h"
#include "log.h"
#include "os.h"
#include "profiler.h"
#include "signalEvent.h"
#include "tsc.h"
#include "vmEntry.h"


int SignalEvent::_signal;
long SignalEvent::_interval;
volatile u64 SignalEvent::_last_sample;
static const u64 SIGNAL_HANDLER_GATE_CLOSED = 1ULL << 63;
static const u64 SIGNAL_HANDLER_GATE_COUNT_MASK = SIGNAL_HANDLER_GATE_CLOSED - 1;

volatile u64 SignalEvent::_handler_gate = SIGNAL_HANDLER_GATE_CLOSED;
volatile u64 SignalEvent::_failed_traces;

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

void SignalEvent::closeSignalHandlerGate() {
    // Atomically close admission before waiting. Once the admitted count
    // reaches zero, no signal handler can still be recording a sample.
    __atomic_fetch_or(&_handler_gate, SIGNAL_HANDLER_GATE_CLOSED, __ATOMIC_ACQ_REL);
    while ((__atomic_load_n(&_handler_gate, __ATOMIC_ACQUIRE) &
            SIGNAL_HANDLER_GATE_COUNT_MASK) != 0) {
        sched_yield();
    }
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
    u64 failed_traces = __atomic_load_n(&_failed_traces, __ATOMIC_RELAXED);
    if (failed_traces != 0) {
        Log::warn("Signal event failed to obtain %llu stack traces", failed_traces);
    }
}
