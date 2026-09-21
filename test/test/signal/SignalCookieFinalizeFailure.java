/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test.signal;

import java.nio.file.Files;
import java.nio.file.Path;
import one.profiler.AsyncProfiler;

public class SignalCookieFinalizeFailure {
    private static final String SESSION_ID = "01234567-89ab-cdef-0123-456789abcdef";

    public static void main(String[] args) throws Exception {
        AsyncProfiler profiler = AsyncProfiler.getInstance();
        profiler.execute("start,event=cpu,interval=1ms,file=" + args[0]
                + ",jfrsync=default,signalcookie,signalid=" + SESSION_ID);

        Path destination = Path.of(args[0]);
        Files.deleteIfExists(destination);
        Files.createDirectory(destination);
        try {
            profiler.execute("stop,signalcookie,signalid=" + SESSION_ID + ",signalepoch=1");
            throw new AssertionError("JFR destination failure should fail guarded stop");
        } catch (IllegalStateException expected) {
            // The identity-specific terminal receipt remains available for recovery.
        }

        String terminal = profiler.execute("status,signalcookie,signalid=" + SESSION_ID + ",signalepoch=1");
        assert terminal.contains(" finalized=false ") : terminal;
        assert terminal.endsWith(" reason=finalization-failed\n") : terminal;
        Files.delete(destination);
    }
}
