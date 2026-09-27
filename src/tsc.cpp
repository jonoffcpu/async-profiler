/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

#include <jvmti.h>
#include "log.h"
#include "tsc.h"
#include "vmEntry.h"
#include "vmStructs.h"


bool TSC::_initialized = false;
bool TSC::_available = false;
bool TSC::_enabled = false;
u64 TSC::_offset = 0;
u64 TSC::_frequency = NANOTIME_FREQ;
JvmClock TSC::_jvm_clock = JVM_CLOCK_UNKNOWN;
u64 TSC::_nanotime_offset = 0;

void TSC::enable(Clock clock) {
    if (VM::loaded()) {
        // Retried until it succeeds, since the JFR natives may not be registered yet: a recording
        // without jfrsync can start before the JVM's first JFR recording
        if (_jvm_clock == JVM_CLOCK_UNKNOWN) {
            syncWithJvm();
        }
    } else if (!_initialized) {
        _available = cpuHasGoodTimestampCounter();
        _initialized = true;
    }

    _enabled = TSC_SUPPORTED && clock != CLK_MONOTONIC && _available;
}

// Try to use the same clock source with the same offset/frequency as the JVM does
void TSC::syncWithJvm() {
    JNIEnv* env = VM::jni();

    jfieldID jvm;
    jmethodID counterTime, getTicksFrequency;
    jclass cls = env->FindClass("jdk/jfr/internal/JVM");
    if (cls == nullptr || (counterTime = env->GetStaticMethodID(cls, "counterTime", "()J")) == nullptr) {
        env->ExceptionClear();
        return;
    }

    u64 frequency = 0;
    // Method is static since JDK 22
    if ((getTicksFrequency = env->GetStaticMethodID(cls, "getTicksFrequency", "()J")) != nullptr) {
        frequency = env->CallStaticLongMethod(cls, getTicksFrequency);
    } else {
        env->ExceptionClear();
        if ((getTicksFrequency = env->GetMethodID(cls, "getTicksFrequency", "()J")) != nullptr &&
                (jvm = env->GetStaticFieldID(cls, "jvm", "Ljdk/jfr/internal/JVM;")) != nullptr) {
            frequency = env->CallLongMethod(env->GetStaticObjectField(cls, jvm), getTicksFrequency);
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

#ifdef __linux__
    if (frequency == NANOTIME_FREQ) {
        // Without the time stamp counter, which is on every architecture but x86, HotSpot counts JFR
        // ticks with os::elapsed_counter(): CLOCK_MONOTONIC nanoseconds from the JVM's start. The offset
        // is taken from the shortest of a few brackets around the JVM's clock; the first one also warms
        // up the call.
        u64 shortest = (u64)-1;
        u64 offset = 0;
        for (int i = 0; i < 8; i++) {
            u64 before = OS::nanotime();
            u64 jvm_ticks = env->CallStaticLongMethod(cls, counterTime);
            u64 after = OS::nanotime();
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return;
            }
            if (after - before < shortest) {
                shortest = after - before;
                offset = before + shortest / 2 - jvm_ticks;
            }
        }
        _nanotime_offset = offset;
        _jvm_clock = JVM_CLOCK_NANOTIME;
        Log::debug("Monotonic clock synchronized with the JVM: offset %llu ns, bracket %llu ns",
                   (unsigned long long)offset, (unsigned long long)shortest);
        return;
    }
#endif

#if defined(__x86_64__) || defined(__i386__)
    // HotSpot has its time stamp counter clock only on x86, and uses the counter only when its
    // frequency is above the monotonic clock's
    JVMFlag* f = JVMFlag::find("UseFastUnorderedTimeStamps");
    if (f == nullptr || !f->get() || frequency <= NANOTIME_FREQ || !JFR_ALLOWED_FREQUENCY(frequency)) {
        return;
    }

    u64 jvm_ticks = env->CallStaticLongMethod(cls, counterTime);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    _offset = rdtsc() - jvm_ticks;
    _frequency = frequency;
    _available = true;
    _jvm_clock = JVM_CLOCK_TSC;
#endif
}
