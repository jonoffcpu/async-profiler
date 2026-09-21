/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test.signal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import one.profiler.AsyncProfiler;
import one.profiler.Span;

public class SignalCookieApi {
    private static final String SESSION_ID = "01234567-89ab-cdef-0123-456789abcdef";

    public static void main(String[] args) throws Exception {
        AsyncProfiler profiler = AsyncProfiler.getInstance();
        String started = profiler.execute("start,event=signal,file=" + args[0]
                + ",signalcookie,signalid=" + SESSION_ID);
        assert started.matches("signal-capture-v1 id=" + SESSION_ID
                + " signal=\\d+ epoch=1 delivery=queued\\n") : started;

        String status = profiler.execute("status,signalcookie");
        assert status.equals(started) : status;

        try {
            profiler.execute("stop");
            throw new AssertionError("unguarded stop should fail");
        } catch (IllegalStateException expected) {
            // Cookie captures require the atomic identity guard.
        }

        String stopped = stopWhileSpansAreWritten(profiler);
        assert stopped.startsWith("signal-capture-v1 stopped id=" + SESSION_ID) : stopped;
        assert stopped.contains(" admitted=0 ") : stopped;
        assert stopped.contains(" submitted=0 finalized=true ") : stopped;
        assert stopped.endsWith(" reason=guarded-stop\n") : stopped;
        String repeated = profiler.execute("stop,signalcookie,signalid=" + SESSION_ID);
        assert repeated.equals(stopped) : repeated;
    }

    private static String stopWhileSpansAreWritten(AsyncProfiler profiler) throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch ready = new CountDownLatch(32);
        Thread[] writers = new Thread[32];
        try {
            for (int i = 0; i < writers.length; i++) {
                writers[i] = new Thread(() -> {
                    Span.end(Span.start(), "concurrent-cookie-stop");
                    ready.countDown();
                    while (running.get()) {
                        Span.end(Span.start(), "concurrent-cookie-stop");
                    }
                });
                writers[i].start();
            }
            assert ready.await(10, TimeUnit.SECONDS) : "span writers did not start";
            return profiler.execute("stop,signalcookie,signalid=" + SESSION_ID + ",signalepoch=1");
        } finally {
            running.set(false);
            for (Thread writer : writers) {
                if (writer != null) {
                    writer.join(10_000);
                    assert !writer.isAlive() : "span writer did not stop";
                }
            }
        }
    }
}
