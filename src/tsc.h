/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

#ifndef _TSC_H
#define _TSC_H

#include "arguments.h"
#include "os.h"


const u64 NANOTIME_FREQ = 1000000000;


#if defined(__x86_64__) || defined(__i386__)

#include <cpuid.h>

#define TSC_SUPPORTED true

// JFR prefers TSC only if its frequency is higher than 1GHz
#define JFR_ALLOWED_FREQUENCY(freq) ((freq) > NANOTIME_FREQ)

static inline u64 rdtsc() {
#if defined(__x86_64__)
    u32 lo, hi;
    asm volatile("rdtsc" : "=a" (lo), "=d" (hi));
    return ((u64)hi << 32) | lo;
#else
    u64 result;
    asm volatile("rdtsc" : "=A" (result));
    return result;
#endif
}

// Returns true if this CPU has a good ("invariant") timestamp counter
static bool cpuHasGoodTimestampCounter() {
    unsigned int eax, ebx, ecx, edx;

    // Check if CPUID supports misc feature flags
    __cpuid(0x80000000, eax, ebx, ecx, edx);
    if (eax < 0x80000007) {
        return 0;
    }

    // Get misc feature flags
    __cpuid(0x80000007, eax, ebx, ecx, edx);

    // Bit 8 of EDX indicates invariant TSC
    return (edx & (1 << 8)) != 0;
}

#elif defined(__aarch64__)

#define TSC_SUPPORTED true
#define JFR_ALLOWED_FREQUENCY(freq) true

static inline u64 rdtsc() {
    u64 value;
    asm volatile("mrs %0, cntvct_el0" : "=r"(value));
    return value;
}

static bool cpuHasGoodTimestampCounter() {
    // AARCH64 always has a good timestamp counter.
    return true;
}

#else

#define TSC_SUPPORTED false
#define JFR_ALLOWED_FREQUENCY(freq) false
#define rdtsc() 0

static bool cpuHasGoodTimestampCounter() {
    return false;
}

#endif


// The clock the JVM uses for JFR timestamps, once async-profiler has synchronized with it
enum JvmClock {
    JVM_CLOCK_UNKNOWN,
    JVM_CLOCK_TSC,      // the time stamp counter, with an offset
    JVM_CLOCK_NANOTIME  // the monotonic clock, counted from the JVM's start
};

class TSC {
  private:
    static bool _initialized;
    static bool _available;
    static bool _enabled;
    static u64 _offset;
    static u64 _frequency;
    static JvmClock _jvm_clock;
    static u64 _nanotime_offset;

    static void syncWithJvm();

  public:
    static void enable(Clock clock);

    static bool enabled() {
        return TSC_SUPPORTED && _enabled;
    }

    // True when ticks() has the origin and the frequency of the JVM's JFR clock, so that a JFR chunk
    // written with them lines up with the JVM's own chunks: JDK 22+ readers convert every chunk of
    // a file with the first chunk's origin.
    static bool alignedWithJvm() {
        return _jvm_clock == JVM_CLOCK_NANOTIME || (_jvm_clock == JVM_CLOCK_TSC && enabled());
    }

    static u64 ticks() {
        return enabled() ? rdtsc() - _offset : OS::nanotime() - _nanotime_offset;
    }

    // Ticks per second.
    // When using the TSC with no JVM, since there is no calibration,
    // this function will return an incorrect value.
    static u64 frequency() {
        return enabled() ? _frequency : NANOTIME_FREQ;
    }
};

#endif // _TSC_H
