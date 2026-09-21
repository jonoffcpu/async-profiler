/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test.signal;

import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import one.profiler.AsyncProfiler;

public class SignalCookieRedirectedMaster {
    private static final String SESSION_ID = "01234567-89ab-cdef-0123-456789abcdef";

    public static void main(String[] args) throws Exception {
        AsyncProfiler profiler = AsyncProfiler.getInstance();
        profiler.execute("start,event=cpu,interval=1ms,file=" + args[0]
                + ",jfrsync=default,signalcookie,signalid=" + SESSION_ID);

        Recording master = FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(recording -> recording.getState() == RecordingState.RUNNING)
                .findFirst()
                .orElseThrow(() -> new AssertionError("JfrSync master recording is not running"));
        Path redirected = Path.of(args[0] + ".redirected");
        master.setDestination(redirected);
        master.stop();

        String terminal = profiler.execute("status,signalcookie,signalid=" + SESSION_ID + ",signalepoch=1");
        assert terminal.contains(" finalized=false ") : terminal;
        assert terminal.endsWith(" reason=finalization-failed\n") : terminal;
        assert Files.size(redirected) > 0;
        Files.delete(redirected);
    }
}
