/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test.signal;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import one.profiler.AsyncProfiler;

public class SignalCookieMasterStop {
    private static final String SESSION_ID = "01234567-89ab-cdef-0123-456789abcdef";

    public static void main(String[] args) throws Exception {
        AsyncProfiler profiler = AsyncProfiler.getInstance();
        String started = profiler.execute("start,event=cpu,interval=1ms,file=" + args[0]
                + ",jfrsync=default,signalcookie,signalid=" + SESSION_ID);
        assert started.contains(" epoch=1 delivery=queued") : started;

        Recording master = FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(recording -> recording.getState() == RecordingState.RUNNING)
                .findFirst()
                .orElseThrow(() -> new AssertionError("JfrSync master recording is not running"));
        master.stop();

        String terminal = profiler.execute("status,signalcookie,signalid=" + SESSION_ID + ",signalepoch=1");
        assert terminal.contains("signal-capture-v1 stopped id=" + SESSION_ID) : terminal;
        assert terminal.contains(" finalized=true ") : terminal;
        assert terminal.endsWith(" reason=master-stop\n") : terminal;
    }
}
