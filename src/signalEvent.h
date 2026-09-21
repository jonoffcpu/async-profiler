/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

#ifndef _SIGNALEVENT_H
#define _SIGNALEVENT_H

#include <signal.h>
#include "engine.h"


class SignalEvent : public Engine {
  private:
    static int _signal;
    static long _interval;
    static volatile u64 _last_sample;
    static volatile u64 _handler_gate;
    static volatile u64 _failed_traces;

    static void signalHandler(int signo, siginfo_t* siginfo, void* ucontext);
    static bool enterSignalHandler();
    static void leaveSignalHandler(int saved_errno);
    static void closeSignalHandlerGate();

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
};

#endif // _SIGNALEVENT_H
