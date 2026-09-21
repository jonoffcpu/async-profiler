/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.jfr.event;

public class SignalCaptureStats extends Event {
    public final int schemaVersion;
    public final String sessionId;
    public final long captureEpoch;
    public final long admittedSignals;
    public final long invalidSignalCode;
    public final long zeroCookie;
    public final long zeroSequence;
    public final long staleEpoch;
    public final long acceptedCookies;
    public final long captureFailures;
    public final long submittedSamples;

    public SignalCaptureStats(long time, int schemaVersion, String sessionId, long captureEpoch,
                              long admittedSignals, long invalidSignalCode, long zeroCookie,
                              long zeroSequence, long staleEpoch, long acceptedCookies,
                              long captureFailures, long submittedSamples) {
        super(time, 0, 0);
        this.schemaVersion = schemaVersion;
        this.sessionId = sessionId;
        this.captureEpoch = captureEpoch;
        this.admittedSignals = admittedSignals;
        this.invalidSignalCode = invalidSignalCode;
        this.zeroCookie = zeroCookie;
        this.zeroSequence = zeroSequence;
        this.staleEpoch = staleEpoch;
        this.acceptedCookies = acceptedCookies;
        this.captureFailures = captureFailures;
        this.submittedSamples = submittedSamples;
    }
}
