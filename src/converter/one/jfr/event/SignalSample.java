/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.jfr.event;

public class SignalSample extends Event {
    public final long correlationId;
    public final long monotonicTimeNanos;

    public SignalSample(long time, int tid, int stackTraceId, long correlationId, long monotonicTimeNanos) {
        super(time, tid, stackTraceId);
        this.correlationId = correlationId;
        this.monotonicTimeNanos = monotonicTimeNanos;
    }
}
