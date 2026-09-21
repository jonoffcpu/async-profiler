/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test.signal;

import java.nio.file.Files;
import java.nio.file.Path;

public class SignalCookieTrapApp {
    private static volatile double sink;

    private static native void beginMarker();

    private static native void endMarker();

    static {
        System.loadLibrary("signaltraps");
    }

    public static void main(String[] args) throws Exception {
        Path begin = Path.of(args[0]);
        Path end = Path.of(args[1]);
        awaitTrigger(begin);
        beginMarker();
        Files.writeString(begin, "done");
        burnUntilTriggered(end);
        endMarker();
        Files.writeString(end, "done");
        Thread.sleep(10_000);
    }

    private static void awaitTrigger(Path path) throws Exception {
        while (Files.size(path) == 0) {
            Thread.sleep(10);
        }
    }

    private static void burnUntilTriggered(Path path) throws Exception {
        double value = 1.0;
        while (Files.size(path) == 0) {
            for (int i = 0; i < 10_000; i++) {
                value = Math.sqrt(value + i);
            }
            sink = value;
        }
    }
}
