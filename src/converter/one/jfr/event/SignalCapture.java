/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.jfr.event;

public class SignalCapture extends Event {
    public final int schemaVersion;
    public final String sessionId;
    public final long captureEpoch;
    public final int signal;
    public final String signalDelivery;
    public final long processId;
    public final long processStartTimeMillis;

    public SignalCapture(long time, int schemaVersion, String sessionId, long captureEpoch,
                         int signal, String signalDelivery, long processId, long processStartTimeMillis) {
        super(time, 0, 0);
        this.schemaVersion = schemaVersion;
        this.sessionId = sessionId;
        this.captureEpoch = captureEpoch;
        this.signal = signal;
        this.signalDelivery = signalDelivery;
        this.processId = processId;
        this.processStartTimeMillis = processStartTimeMillis;
    }
}
