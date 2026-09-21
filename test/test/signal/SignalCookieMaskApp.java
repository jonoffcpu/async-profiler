/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test.signal;

import java.nio.file.Files;
import java.nio.file.Path;

public class SignalCookieMaskApp {
    private static native int currentThreadId();

    private static native int setSignalBlocked(int signal, boolean blocked);

    static {
        System.loadLibrary("signaltraps");
    }

    public static void main(String[] args) throws Exception {
        Path control = Path.of(args[0]);
        Path status = Path.of(args[1]);
        int tid = currentThreadId();
        Files.writeString(status, "ready:" + tid);

        String previous = "";
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String command = Files.exists(control) ? Files.readString(control).strip() : "";
            if (!command.isEmpty() && !command.equals(previous)) {
                previous = command;
                String[] parts = command.split(" ", 2);
                int signal = Integer.parseInt(parts[1]);
                boolean blocked = parts[0].equals("block");
                if (!blocked && !parts[0].equals("unblock")) {
                    throw new IllegalArgumentException("Unknown command: " + command);
                }
                int error = setSignalBlocked(signal, blocked);
                if (error != 0) {
                    throw new IllegalStateException("pthread_sigmask failed: " + error);
                }
                Files.writeString(status, (blocked ? "blocked:" : "unblocked:") + tid);
                if (!blocked) {
                    Thread.sleep(5_000);
                    return;
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Timed out waiting for signal mask commands");
    }
}
