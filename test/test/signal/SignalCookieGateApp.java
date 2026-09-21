/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test.signal;

import java.nio.file.Files;
import java.nio.file.Path;

public class SignalCookieGateApp {
    private static native int currentThreadId();

    static {
        System.loadLibrary("signaltraps");
    }

    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]), Integer.toString(currentThreadId()));
        Thread.sleep(120_000);
    }
}
