/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test.signal;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import one.jfr.JfrReader;
import one.jfr.event.ExecutionSample;
import one.jfr.event.SignalCapture;
import one.jfr.event.SignalCaptureStats;
import one.jfr.event.SignalSample;
import one.jfr.event.SpanEvent;
import one.profiler.test.Assert;
import one.profiler.test.Os;
import one.profiler.test.Output;
import one.profiler.test.Test;
import one.profiler.test.TestProcess;
import one.profiler.test.TestSkippedException;
import test.cpu.CpuBurner;

public class SignalTests {
    private static final String SESSION_ID = "01234567-89ab-cdef-0123-456789abcdef";

    @Test(mainClass = CpuBurner.class, os = Os.LINUX, runIsolated = true)
    public void externalSignalsRespectInterval(TestProcess p) throws Exception {
        p.profile("start -e signal -i 100ms");

        for (int i = 0; i < 20; i++) {
            Process signal = new ProcessBuilder("kill", "-PROF", Long.toString(p.pid())).start();
            Assert.isEqual(signal.waitFor(), 0, "sending SIGPROF should succeed");
            Thread.sleep(20);
        }

        Output out = p.profile("stop -o collapsed");
        Assert.isGreaterOrEqual(out.total(), 2, "external signals should produce samples");
        Assert.isLessOrEqual(out.total(), 6, "the interval should limit accepted samples");
    }

    @Test(mainClass = CpuBurner.class, os = Os.LINUX, runIsolated = true)
    public void signalEngineCanRestartWhileSignalsRaceWithStop(TestProcess p) throws Exception {
        for (int round = 0; round < 10; round++) {
            p.profile("start -e signal -i 1ns");

            Process signals = new ProcessBuilder(
                    "sh", "-c",
                    "i=0; while [ $i -lt 200 ]; do kill -PROF " + p.pid()
                            + " || exit; i=$((i + 1)); done")
                    .start();
            p.profile("stop");
            Assert.isEqual(signals.waitFor(), 0, "signals racing with stop should not terminate the JVM");

            Process lateSignal = new ProcessBuilder("kill", "-PROF", Long.toString(p.pid())).start();
            Assert.isEqual(lateSignal.waitFor(), 0, "a late pending signal should be safely ignored");
            p.profile("status");
        }
    }

    @Test(mainClass = CpuBurner.class, os = Os.LINUX, jvmVer = {17, Integer.MAX_VALUE}, runIsolated = true)
    public void signalCookieJfrRoundTrip(TestProcess p) throws Exception {
        p.profile("start -e cpu -i 1ms --alloc 1m --wall 10ms --lock 1us"
                + " -o jfr -f %cookie.jfr --jfrsync default"
                + " --signalcookie --signalid " + SESSION_ID);
        Output status = p.profile("status --signalcookie");
        Matcher matcher = Pattern.compile("signal-capture-v1 id=" + SESSION_ID
                + " signal=(\\d+) epoch=(\\d+)").matcher(status.toString());
        assert matcher.find() : status;
        long epoch = Long.parseLong(matcher.group(2));
        int signal = Integer.parseInt(matcher.group(1));
        assert epoch > 0 && epoch <= 0xffffffffL;
        assert status.contains("delivery=queued") : status;

        assert status.contains("signal-capture-v1 id=" + SESSION_ID);
        assert status.contains("epoch=" + epoch);

        sendCookie(p, signal, 0);
        sendCookie(p, signal, epoch << 32);
        sendCookie(p, signal, ((epoch + 1) << 32) | 1);
        sendCookie(p, signal, (epoch << 32) | 1);
        Process invalidCode = new ProcessBuilder("kill", "-" + signal, Long.toString(p.pid())).start();
        Assert.isEqual(invalidCode.waitFor(), 0, "sending payload-less signal should succeed");
        Thread.sleep(100);

        p.profile("dump -o jfr");
        sendCookie(p, signal, (epoch << 32) | 2);
        Thread.sleep(100);

        expectProfileFailure(p, "stop");
        assert p.profile("status --signalcookie").contains("id=" + SESSION_ID);

        Output mismatch = p.profile("stop --signalcookie"
                + " --signalid 11111111-1111-1111-1111-111111111111 --signalepoch " + epoch);
        assert mismatch.contains("signal-capture-v1 mismatch");
        assert p.profile("status --signalcookie").contains("id=" + SESSION_ID);

        Output stopped = p.profile("stop --signalcookie --signalid " + SESSION_ID + " --signalepoch " + epoch);
        assert stopped.contains("signal-capture-v1 stopped id=" + SESSION_ID) : stopped;
        assert stopped.contains("admitted=6") : stopped;
        assert stopped.contains("invalid-code=1") : stopped;
        assert stopped.contains("zero-cookie=1") : stopped;
        assert stopped.contains("zero-sequence=1") : stopped;
        assert stopped.contains("stale-epoch=1") : stopped;
        assert stopped.contains("accepted=2") : stopped;
        assert stopped.contains("submitted=2") : stopped;
        assert stopped.contains("finalized=true") : stopped;
        assert stopped.contains("reason=guarded-stop") : stopped;

        Output retained = p.profile("status --signalcookie --signalid " + SESSION_ID
                + " --signalepoch " + epoch);
        assert retained.toString().equals(stopped.toString()) : retained;

        verifyJfr(Path.of(p.getFilePath("%cookie")), epoch);
        verifyAsyncProfilerReader(p.getFilePath("%cookie"), epoch);
        Assert.isGreaterOrEqual(Output.convertJfrToCollapsed(p.getFilePath("%cookie")).total(), 1,
                "default conversion should retain CPU samples while excluding signal samples");
    }

    @Test(mainClass = CpuBurner.class, os = Os.LINUX, runIsolated = true)
    public void signalCookieStatusReportsOtherCapture(TestProcess p) throws Exception {
        assert p.profile("status --signalcookie").contains("signal-capture-v1 inactive");
        p.profile("start -e cpu");
        assert p.profile("status --signalcookie").contains("signal-capture-v1 busy mode=other");
        p.profile("stop");
    }

    @Test(mainClass = CpuBurner.class, os = Os.LINUX, jvmVer = {17, Integer.MAX_VALUE}, runIsolated = true)
    public void signalCookieCoalescingModeIsExplicit(TestProcess p) throws Exception {
        p.profile("start -e signal -o jfr -f %coalescing.jfr --signalcookie=coalescing"
                + " --signalid " + SESSION_ID);
        Output status = p.profile("status --signalcookie");
        Matcher matcher = Pattern.compile("signal=(\\d+) epoch=(\\d+) delivery=coalescing")
                .matcher(status.toString());
        assert matcher.find() : status;
        int signal = Integer.parseInt(matcher.group(1));
        long epoch = Long.parseLong(matcher.group(2));
        sendCookie(p, signal, (epoch << 32) | 1);

        Output stopped = p.profile("stop --signalcookie --signalid " + SESSION_ID
                + " --signalepoch " + epoch);
        assert stopped.contains(" delivery=coalescing ") : stopped;
        assert stopped.contains("accepted=1") : stopped;

        for (RecordedEvent event : RecordingFile.readAllEvents(Path.of(p.getFilePath("%coalescing")))) {
            if (event.getEventType().getName().equals("profiler.SignalCapture")) {
                assert event.getString("signalDelivery").equals("coalescing");
            }
        }

        expectProfileFailure(p, "start -e signal -o jfr -f %policy-change.jfr"
                + " --signalcookie=queued --signalid 11111111-1111-1111-1111-111111111111");
    }

    @Test(mainClass = SignalCookieTrapApp.class, args = "%begin %end",
            os = Os.LINUX, jvmVer = {17, Integer.MAX_VALUE}, runIsolated = true)
    public void signalCookieRemainsActiveAcrossPrimaryTrapsAndThreadFilter(TestProcess p) throws Exception {
        String beginSymbol = "Java_test_signal_SignalCookieTrapApp_beginMarker";
        String endSymbol = "Java_test_signal_SignalCookieTrapApp_endMarker";
        p.profile("start -e cpu -i 1ms --begin " + beginSymbol + " --end " + endSymbol
                + " -o jfr -f %traps.jfr --signalcookie --signalid " + SESSION_ID);
        Output trapStatus = p.profile("status --signalcookie");
        Matcher trapMatcher = Pattern.compile("signal=(\\d+) epoch=(\\d+)").matcher(trapStatus.toString());
        assert trapMatcher.find() : trapStatus;
        int signal = Integer.parseInt(trapMatcher.group(1));
        long trapEpoch = Long.parseLong(trapMatcher.group(2));
        sendCookie(p, signal, (trapEpoch << 32) | 1);
        triggerAndAwait(p, "%begin");
        sendCookie(p, signal, (trapEpoch << 32) | 2);
        triggerAndAwait(p, "%end");
        sendCookie(p, signal, (trapEpoch << 32) | 3);
        Output trapStop = p.profile("stop --signalcookie --signalid " + SESSION_ID
                + " --signalepoch " + trapEpoch);
        assert trapStop.contains("submitted=3") : trapStop;
        assertSignalSampleCount(Path.of(p.getFilePath("%traps")), 3);
        Assert.isGreaterOrEqual(eventCount(Path.of(p.getFilePath("%traps")), "jdk.ExecutionSample"), 1,
                "the primary begin trap should retain its ordinary enable semantics");
        Assert.isEqual(eventCount(Path.of(p.getFilePath("%traps")), "profiler.Window"), 1,
                "the primary end trap should close and record one profiling window");

        String filterId = "11111111-1111-1111-1111-111111111111";
        p.profile("start -e wall -i 10ms --filter 2147483647"
                + " -o jfr -f %filter.jfr --signalcookie --signalid " + filterId);
        Output filterStatus = p.profile("status --signalcookie");
        Matcher filterMatcher = Pattern.compile("signal=(\\d+) epoch=(\\d+)")
                .matcher(filterStatus.toString());
        assert filterMatcher.find() : filterStatus;
        signal = Integer.parseInt(filterMatcher.group(1));
        long filterEpoch = Long.parseLong(filterMatcher.group(2));
        sendCookie(p, signal, (filterEpoch << 32) | 1);
        Output filterStop = p.profile("stop --signalcookie --signalid " + filterId
                + " --signalepoch " + filterEpoch);
        assert filterStop.contains("submitted=1") : filterStop;
        assertSignalSampleCount(Path.of(p.getFilePath("%filter")), 1);
        Assert.isEqual(eventCount(Path.of(p.getFilePath("%filter")), "profiler.WallClockSample"), 0,
                "the primary wall-clock thread filter should still exclude ordinary samples");
    }

    @Test(mainClass = SignalCookieApi.class, args = "%api.jfr", os = Os.LINUX, runIsolated = true)
    public void signalCookieUsesJavaExecuteResult(TestProcess p) throws Exception {
        p.waitForExit();
        Assert.isEqual(p.exitCode(), 0, "Java execute cookie lifecycle should succeed");
        try (JfrReader reader = new JfrReader(p.getFilePath("%api"))) {
            Assert.isEqual(reader.readAllEvents(SignalCaptureStats.class).size(), 1,
                    "concurrent span writers must not overwrite terminal signal statistics");
        }
        try (JfrReader reader = new JfrReader(p.getFilePath("%api"))) {
            Assert.isGreaterOrEqual(reader.readAllEvents(SpanEvent.class).size(), 1,
                    "the recording should exercise public span event writers");
        }
    }

    @Test(mainClass = SignalCookieMasterStop.class, args = "%master.jfr",
            os = Os.LINUX, jvmVer = {17, Integer.MAX_VALUE}, runIsolated = true)
    public void signalCookieRetainsJfrSyncMasterStop(TestProcess p) throws Exception {
        p.waitForExit();
        Assert.isEqual(p.exitCode(), 0, "JfrSync stop callback should retain a finalized receipt");
        try (JfrReader reader = new JfrReader(p.getFilePath("%master"))) {
            Assert.isEqual(reader.readAllEvents(SignalCaptureStats.class).size(), 1,
                    "master stop should write one terminal stats event");
        }
    }

    @Test(mainClass = SignalCookieFinalizeFailure.class, args = "%finalize-failure.jfr",
            os = Os.LINUX, jvmVer = {17, Integer.MAX_VALUE}, runIsolated = true)
    public void signalCookieReportsJfrFinalizationFailure(TestProcess p) throws Exception {
        p.waitForExit();
        Assert.isEqual(p.exitCode(), 0,
                "failed JFR destination should retain finalized=false terminal receipt");
    }

    @Test(mainClass = SignalCookieRedirectedMaster.class, args = "%redirect.jfr",
            os = Os.LINUX, jvmVer = {17, Integer.MAX_VALUE}, runIsolated = true)
    public void signalCookieRejectsRedirectedJfrMaster(TestProcess p) throws Exception {
        p.waitForExit();
        Assert.isEqual(p.exitCode(), 0,
                "redirected JFR master should retain finalized=false terminal receipt");
    }

    @Test(mainClass = CpuBurner.class, os = Os.LINUX, runIsolated = true)
    public void signalCookieBurnsEpochAfterStartupFailure(TestProcess p) throws Exception {
        String failedId = "11111111-1111-1111-1111-111111111111";
        expectProfileFailure(p, "start -e signal -o jfr --jfrsync default"
                + " -f /no/such/async-profiler/capture.jfr"
                + " --signalcookie --signalid " + failedId);
        assert p.profile("status --signalcookie").contains("signal-capture-v1 inactive");

        p.profile("start -e signal -o jfr -f %burn.jfr --jfrsync default"
                + " --signalcookie --signalid " + SESSION_ID);
        Output status = p.profile("status --signalcookie");
        assert status.contains("epoch=2") : status;

        Output failedCleanup = p.profile("stop --signalcookie --signalid " + failedId);
        assert failedCleanup.contains("signal-capture-v1 mismatch") : failedCleanup;
        Output stillActive = p.profile("status --signalcookie");
        assert stillActive.contains("id=" + SESSION_ID) : stillActive;
        assert stillActive.contains("epoch=2") : stillActive;

        Output stopped = p.profile("stop --signalcookie --signalid " + SESSION_ID);
        assert stopped.contains("signal-capture-v1 stopped id=" + SESSION_ID);
        assert stopped.contains("epoch=2");
    }

    @Test(mainClass = CpuBurner.class, os = Os.LINUX, jvmVer = {17, Integer.MAX_VALUE}, runIsolated = true)
    public void signalCookieTimeoutRetainsTerminalReceipt(TestProcess p) throws Exception {
        p.profile("start -e cpu -i 1ms --timeout 1 -o jfr -f %timeout.jfr"
                + " --signalcookie --signalid " + SESSION_ID);
        Thread.sleep(1500);
        Output terminal = p.profile("status --signalcookie --signalid " + SESSION_ID + " --signalepoch 1");
        assert terminal.contains(" finalized=true ") : terminal;
        assert terminal.contains(" stopped-at=") : terminal;
        assert terminal.contains(" reason=timeout") : terminal;
    }

    @Test(mainClass = CpuBurner.class, os = Os.LINUX, runIsolated = true)
    public void signalCookieRejectsOldEpochAfterRestart(TestProcess p) throws Exception {
        String firstId = "11111111-1111-1111-1111-111111111111";
        p.profile("start -e signal -o jfr -f %first.jfr --signalcookie --signalid " + firstId);
        p.profile("stop --signalcookie --signalid " + firstId + " --signalepoch 1");

        p.profile("start -e signal -o jfr -f %second.jfr --signalcookie --signalid " + SESSION_ID);
        Output status = p.profile("status --signalcookie");
        assert status.contains("epoch=2") : status;
        Output retainedFirst = p.profile("status --signalcookie --signalid " + firstId + " --signalepoch 1");
        assert retainedFirst.contains("signal-capture-v1 stopped id=" + firstId) : retainedFirst;
        assert p.profile("status --signalcookie").contains("id=" + SESSION_ID);

        Output oldCleanup = p.profile("stop --signalcookie --signalid " + firstId + " --signalepoch 1");
        assert oldCleanup.toString().equals(retainedFirst.toString()) : oldCleanup;
        assert p.profile("status --signalcookie").contains("id=" + SESSION_ID);

        Output wrongEpoch = p.profile("stop --signalcookie --signalid " + SESSION_ID + " --signalepoch 1");
        assert wrongEpoch.contains("signal-capture-v1 mismatch") : wrongEpoch;
        assert p.profile("status --signalcookie").contains("id=" + SESSION_ID);

        Matcher matcher = Pattern.compile(" signal=(\\d+)").matcher(status.toString());
        assert matcher.find() : status;
        int signal = Integer.parseInt(matcher.group(1));
        sendCookie(p, signal, (1L << 32) | 1);
        sendCookie(p, signal, (2L << 32) | 1);
        Output stopped = p.profile("stop --signalcookie --signalid " + SESSION_ID + " --signalepoch 2");
        assert stopped.contains("stale-epoch=1") : stopped;
        assert stopped.contains("accepted=1") : stopped;
        assert stopped.contains("submitted=1") : stopped;

        expectProfileFailure(p, "start -e cpu --signal " + signal);
        p.profile("start -e cpu -i 1ms");
        p.profile("stop");
    }

    @Test(mainClass = SignalCookieMaskApp.class, args = "%mask_control %mask_status",
            os = Os.LINUX, jvmVer = {17, Integer.MAX_VALUE}, runIsolated = true)
    public void signalCookieRejectsPendingCookieAfterRestart(TestProcess p) throws Exception {
        String firstId = "11111111-1111-1111-1111-111111111111";
        p.profile("start -e signal -o jfr -f %pending-first.jfr --signalcookie --signalid " + firstId);
        Output firstStatus = p.profile("status --signalcookie");
        Matcher firstMatcher = Pattern.compile("signal=(\\d+) epoch=(\\d+)").matcher(firstStatus.toString());
        assert firstMatcher.find() : firstStatus;
        int signal = Integer.parseInt(firstMatcher.group(1));
        long firstEpoch = Long.parseLong(firstMatcher.group(2));

        Path control = Path.of(p.getFilePath("%mask_control"));
        Path maskStatus = Path.of(p.getFilePath("%mask_status"));
        int tid = awaitMaskStatus(maskStatus, "ready");
        Files.writeString(control, "block " + signal);
        assert awaitMaskStatus(maskStatus, "blocked") == tid;
        sendCookieToThread(p, signal, (firstEpoch << 32) | 1, tid);

        Output firstStop = p.profile("stop --signalcookie --signalid " + firstId
                + " --signalepoch " + firstEpoch);
        assert firstStop.contains("admitted=0") : firstStop;

        p.profile("start -e signal -o jfr -f %pending-second.jfr --signalcookie --signalid " + SESSION_ID);
        Output secondStatus = p.profile("status --signalcookie");
        Matcher secondMatcher = Pattern.compile("signal=(\\d+) epoch=(\\d+)").matcher(secondStatus.toString());
        assert secondMatcher.find() : secondStatus;
        assert Integer.parseInt(secondMatcher.group(1)) == signal : secondStatus;
        long secondEpoch = Long.parseLong(secondMatcher.group(2));
        assert secondEpoch != firstEpoch;

        Files.writeString(control, "unblock " + signal);
        assert awaitMaskStatus(maskStatus, "unblocked") == tid;
        Thread.sleep(100);
        sendCookieToThread(p, signal, (secondEpoch << 32) | 1, tid);

        Output secondStop = p.profile("stop --signalcookie --signalid " + SESSION_ID
                + " --signalepoch " + secondEpoch);
        assert secondStop.contains("admitted=2") : secondStop;
        assert secondStop.contains("stale-epoch=1") : secondStop;
        assert secondStop.contains("accepted=1") : secondStop;
        assert secondStop.contains("submitted=1") : secondStop;
        p.waitForExit();
        Assert.isEqual(p.exitCode(), 0, "target must survive delayed stale cookie delivery");
    }

    @Test(mainClass = SignalCookieGateApp.class, args = "%gate_tid",
            env = {"ASYNC_PROFILER_SIGNAL_GATE_TEST=%gate_control"},
            os = Os.LINUX, jvmVer = {17, Integer.MAX_VALUE}, runIsolated = true)
    public void signalCookieStopDrainsAdmittedHandlersBeforeReopen(TestProcess p) throws Exception {
        int tid = awaitThreadId(Path.of(p.getFilePath("%gate_tid")));
        List<Long> oldEpochs = new ArrayList<>();
        List<Long> closedCookies = new ArrayList<>();

        try (SignalGateControl gate = new SignalGateControl(Path.of(p.getFilePath("%gate_control")))) {
            for (int point = 1; point <= 3; point++) {
                String id = String.format("10000000-0000-0000-0000-%012d", point);
                String fileId = "%gate" + point;
                p.profile("start -e signal -o jfr -f " + fileId + ".jfr"
                        + " --signalcookie --signalid " + id);
                gate.requireAttached();
                Output status = p.profile("status --signalcookie");
                Matcher matcher = Pattern.compile("signal=(\\d+) epoch=(\\d+)").matcher(status.toString());
                assert matcher.find() : status;
                int signal = Integer.parseInt(matcher.group(1));
                long epoch = Long.parseLong(matcher.group(2));

                if (!oldEpochs.isEmpty()) {
                    long staleCookie = (oldEpochs.get(oldEpochs.size() - 1) << 32) | (0x40L + point);
                    sendCookieToThread(p, signal, staleCookie, tid);
                    Thread.sleep(50);
                }

                int token = 100 + point;
                gate.arm(point, token);
                long recordedCookie = (epoch << 32) | point;
                sendCookieToThread(p, signal, recordedCookie, tid);
                gate.await(SignalGateControl.REACHED_TOKEN, token, "handler pause point " + point);
                if (point >= 2) {
                    Assert.isEqual(gate.read(SignalGateControl.OBSERVED_EPOCH), (int)epoch,
                            "handler must retain the admitted capture epoch");
                }

                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<Output> stopping = executor.submit(() -> p.profile(
                            "stop --signalcookie --signalid " + id + " --signalepoch " + epoch));
                    gate.await(SignalGateControl.CLOSED_TOKEN, token, "closed admission gate");
                    Assert.isEqual(gate.read(SignalGateControl.CLOSED_EPOCH), (int)epoch,
                            "stop must not replace the old epoch before draining");
                    Assert.isEqual(gate.read(SignalGateControl.CLOSED_READY), 1,
                            "the admitted handler must retain the active recording at gate close");
                    Assert.isEqual(gate.read(SignalGateControl.DRAINED_TOKEN), 0,
                            "drain must wait for the paused handler");
                    Assert.isEqual(gate.read(SignalGateControl.STATS_TOKEN), 0,
                            "terminal stats must wait for handler drain");
                    Assert.isEqual(gate.read(SignalGateControl.FINALIZED_TOKEN), 0,
                            "JFR finalization must wait for handler drain");
                    Thread.sleep(100);
                    assert !stopping.isDone() : "stop returned while an admitted handler was paused";

                    long closedCookie = (epoch << 32) | (0x80L + point);
                    closedCookies.add(closedCookie);
                    sendCookieToThread(p, signal, closedCookie, tid);
                    assert !stopping.isDone() : "a closed-gate arrival must not release stop";

                    gate.release(token);
                    Output stopped = stopping.get(10, TimeUnit.SECONDS);
                    int expectedAdmitted = oldEpochs.isEmpty() ? 1 : 2;
                    int expectedStale = oldEpochs.isEmpty() ? 0 : 1;
                    assert stopped.contains("admitted=" + expectedAdmitted) : stopped;
                    assert stopped.contains("stale-epoch=" + expectedStale) : stopped;
                    assert stopped.contains("accepted=1") : stopped;
                    assert stopped.contains("submitted=1") : stopped;
                    assert stopped.contains("finalized=true") : stopped;

                    gate.await(SignalGateControl.DRAINED_TOKEN, token, "handler drain");
                    gate.await(SignalGateControl.RECORDED_TOKEN, token, "old-recording sample write");
                    gate.await(SignalGateControl.STATS_TOKEN, token, "terminal stats write");
                    gate.await(SignalGateControl.FINALIZED_TOKEN, token, "JFR finalization");
                    Assert.isEqual(gate.read(SignalGateControl.DRAINED_EPOCH), (int)epoch,
                            "the old epoch must remain published through drain");
                    Assert.isEqual(gate.read(SignalGateControl.DRAINED_READY), 1,
                            "the old recording must remain active through drain");
                    assert gate.read(SignalGateControl.CLOSED_ORDER)
                            < gate.read(SignalGateControl.DRAINED_ORDER);
                    assert gate.read(SignalGateControl.DRAINED_ORDER)
                            < gate.read(SignalGateControl.STATS_ORDER);
                    assert gate.read(SignalGateControl.STATS_ORDER)
                            < gate.read(SignalGateControl.FINALIZED_ORDER);

                    verifyGateJfr(Path.of(p.getFilePath(fileId)), recordedCookie,
                            expectedAdmitted, expectedStale, closedCookies);
                } finally {
                    executor.shutdownNow();
                }

                gate.disarm();
                oldEpochs.add(epoch);
                Thread.sleep(100);
            }

            String finalId = "10000000-0000-0000-0000-000000000004";
            p.profile("start -e signal -o jfr -f %gate4.jfr"
                    + " --signalcookie --signalid " + finalId);
            Output finalStatus = p.profile("status --signalcookie");
            Matcher finalMatcher = Pattern.compile("signal=(\\d+) epoch=(\\d+)").matcher(finalStatus.toString());
            assert finalMatcher.find() : finalStatus;
            int signal = Integer.parseInt(finalMatcher.group(1));
            long epoch = Long.parseLong(finalMatcher.group(2));
            for (int i = 0; i < oldEpochs.size(); i++) {
                sendCookieToThread(p, signal, (oldEpochs.get(i) << 32) | (0xc0L + i), tid);
                Thread.sleep(25);
            }
            long finalCookie = (epoch << 32) | 1;
            sendCookieToThread(p, signal, finalCookie, tid);
            Thread.sleep(50);
            Output finalStop = p.profile("stop --signalcookie --signalid " + finalId
                    + " --signalepoch " + epoch);
            assert finalStop.contains("admitted=4") : finalStop;
            assert finalStop.contains("stale-epoch=3") : finalStop;
            assert finalStop.contains("accepted=1") : finalStop;
            assert finalStop.contains("submitted=1") : finalStop;
            verifyGateJfr(Path.of(p.getFilePath("%gate4")), finalCookie, 4, 3, closedCookies);
        }
    }

    private static void sendCookie(TestProcess p, int signal, long cookie) throws Exception {
        Process sender = new ProcessBuilder(p.testBinPath() + "/signal_cookie",
                Long.toString(p.pid()), Integer.toString(signal), Long.toUnsignedString(cookie)).start();
        Assert.isEqual(sender.waitFor(), 0, "sending cookie signal should succeed");
        Thread.sleep(50);
    }

    private static void sendCookieToThread(TestProcess p, int signal, long cookie, int tid) throws Exception {
        Process sender = new ProcessBuilder(p.testBinPath() + "/signal_cookie",
                Long.toString(p.pid()), Integer.toString(signal), Long.toUnsignedString(cookie),
                Integer.toString(tid)).start();
        Assert.isEqual(sender.waitFor(), 0, "sending thread-directed cookie signal should succeed");
    }

    private static int awaitMaskStatus(Path path, String expected) throws Exception {
        for (int i = 0; i < 500; i++) {
            String status = Files.readString(path).strip();
            if (status.startsWith(expected + ":")) {
                return Integer.parseInt(status.substring(expected.length() + 1));
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Signal mask status did not become " + expected + ": " + Files.readString(path));
    }

    private static int awaitThreadId(Path path) throws Exception {
        for (int i = 0; i < 500; i++) {
            String value = Files.readString(path).strip();
            if (!value.isEmpty()) {
                return Integer.parseInt(value);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Target thread ID was not published");
    }

    private static void expectProfileFailure(TestProcess p, String command) throws Exception {
        try {
            p.profile(command);
            throw new AssertionError("Expected profiling command to fail: " + command);
        } catch (java.io.IOException expected) {
            // Expected command rejection.
        }
    }

    private static void assertSignalSampleCount(Path path, int expected) throws Exception {
        long samples = eventCount(path, "profiler.SignalSample");
        Assert.isEqual(samples, expected, "cookie samples must bypass primary admission controls");
    }

    private static long eventCount(Path path, String eventName) throws Exception {
        return RecordingFile.readAllEvents(path).stream()
                .filter(event -> event.getEventType().getName().equals(eventName))
                .count();
    }

    private static void verifyGateJfr(Path path, long expectedCookie, long admitted, long stale,
                                      List<Long> closedCookies) throws Exception {
        List<RecordedEvent> events = RecordingFile.readAllEvents(path);
        List<Long> cookies = new ArrayList<>();
        int statsCount = 0;
        for (int i = 0; i < events.size(); i++) {
            RecordedEvent event = events.get(i);
            if (event.getEventType().getName().equals("profiler.SignalSample")) {
                cookies.add(event.getLong("correlationId"));
            } else if (event.getEventType().getName().equals("profiler.SignalCaptureStats")) {
                statsCount++;
                Assert.isEqual(event.getLong("admittedSignals"), admitted,
                        "JFR stats must match the drained handler count");
                Assert.isEqual(event.getLong("staleEpoch"), stale,
                        "JFR stats must count only arrivals admitted in this epoch");
                Assert.isEqual(event.getLong("acceptedCookies"), 1,
                        "exactly one current-epoch cookie must be accepted");
                Assert.isEqual(event.getLong("submittedSamples"), 1,
                        "exactly one current-epoch sample must be submitted");
            }
        }
        Assert.isEqual(cookies.size(), 1, "old recording must contain its admitted sample only");
        Assert.isEqual(cookies.get(0), expectedCookie, "recording must retain the admitted cookie");
        Assert.isEqual(statsCount, 1, "recording must contain exactly one terminal stats event");
        for (long closedCookie : closedCookies) {
            assert !cookies.contains(closedCookie) : "closed-gate cookie leaked into a recording";
        }
    }

    private static final class SignalGateControl implements AutoCloseable {
        static final int REACHED_TOKEN = 4;
        static final int OBSERVED_EPOCH = 6;
        static final int CLOSED_TOKEN = 7;
        static final int CLOSED_EPOCH = 8;
        static final int CLOSED_READY = 9;
        static final int DRAINED_TOKEN = 10;
        static final int DRAINED_EPOCH = 11;
        static final int DRAINED_READY = 12;
        static final int RECORDED_TOKEN = 13;
        static final int STATS_TOKEN = 14;
        static final int FINALIZED_TOKEN = 15;
        static final int CLOSED_ORDER = 17;
        static final int DRAINED_ORDER = 18;
        static final int STATS_ORDER = 19;
        static final int FINALIZED_ORDER = 20;
        static final int ATTACHED = 21;

        private final RandomAccessFile file;
        private final FileChannel channel;

        SignalGateControl(Path path) throws Exception {
            file = new RandomAccessFile(path.toFile(), "rw");
            file.setLength(4096);
            channel = file.getChannel();
            write(0, 0x53474654);
            write(1, 1);
        }

        void arm(int point, int token) throws Exception {
            for (int field = REACHED_TOKEN; field <= FINALIZED_ORDER; field++) {
                write(field, 0);
            }
            write(2, point);
            write(3, token);
            channel.force(false);
        }

        void release(int token) throws Exception {
            write(5, token);
            channel.force(false);
        }

        void disarm() throws Exception {
            write(2, 0);
            write(3, 0);
            channel.force(false);
        }

        // The profiler maps this page when it publishes a cookie capture, but
        // only test builds (-DASYNC_PROFILER_TEST) contain the pause points.
        void requireAttached() throws Exception {
            if (read(ATTACHED) != 1) {
                throw new TestSkippedException("profiler library was built without the signal test gate");
            }
        }

        void await(int field, int expected, String milestone) throws Exception {
            for (int i = 0; i < 500; i++) {
                if (read(field) == expected) return;
                Thread.sleep(10);
            }
            throw new AssertionError("Timed out waiting for " + milestone + ": " + read(field));
        }

        int read(int field) throws Exception {
            ByteBuffer value = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
            int offset = field * Integer.BYTES;
            while (value.hasRemaining()) {
                if (channel.read(value, offset + value.position()) < 0) {
                    throw new AssertionError("Unexpected EOF in signal gate control page");
                }
            }
            value.flip();
            return value.getInt();
        }

        void write(int field, int value) throws Exception {
            ByteBuffer bytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
            bytes.putInt(value).flip();
            int offset = field * Integer.BYTES;
            while (bytes.hasRemaining()) {
                channel.write(bytes, offset + bytes.position());
            }
        }

        @Override
        public void close() throws Exception {
            channel.close();
            file.close();
        }
    }

    private static void triggerAndAwait(TestProcess p, String fileId) throws Exception {
        Path path = Path.of(p.getFilePath(fileId));
        Files.writeString(path, "go");
        for (int i = 0; i < 500 && !Files.readString(path).equals("done"); i++) {
            Thread.sleep(10);
        }
        assert Files.readString(path).equals("done") : "trap trigger did not complete: " + fileId;
    }

    private static void verifyJfr(Path path, long epoch) throws Exception {
        List<RecordedEvent> events = RecordingFile.readAllEvents(path);
        int contexts = 0;
        int stats = 0;
        int executionSamples = 0;
        Set<Long> cookies = new HashSet<>();
        for (RecordedEvent event : events) {
            switch (event.getEventType().getName()) {
                case "profiler.SignalCapture":
                    contexts++;
                    assert event.getInt("schemaVersion") == 1;
                    assert SESSION_ID.equals(event.getString("sessionId"));
                    assert event.getLong("captureEpoch") == epoch;
                    assert "queued".equals(event.getString("signalDelivery"));
                    assert event.getLong("processId") > 0;
                    break;
                case "profiler.SignalSample":
                    cookies.add(event.getLong("correlationId"));
                    assert event.getThread("eventThread") != null;
                    assert event.getStackTrace() != null;
                    assert event.getLong("monotonicTimeNanos") > 0;
                    break;
                case "profiler.SignalCaptureStats":
                    stats++;
                    assert event.getLong("admittedSignals") == 6;
                    assert event.getLong("acceptedCookies") == 2;
                    assert event.getLong("submittedSamples") == 2;
                    break;
                case "jdk.ExecutionSample":
                    executionSamples++;
                    break;
                default:
                    break;
            }
        }
        Assert.isGreaterOrEqual(contexts, 2, "each JFR chunk should carry capture context");
        Assert.isEqual(stats, 1, "capture should have one terminal stats event");
        Assert.isGreaterOrEqual(executionSamples, 1,
                "combined recording should retain ordinary CPU execution samples");
        assert cookies.contains((epoch << 32) | 1) : cookies;
        assert cookies.contains((epoch << 32) | 2) : cookies;
    }

    private static void verifyAsyncProfilerReader(String path, long epoch) throws Exception {
        try (JfrReader reader = new JfrReader(path)) {
            List<SignalSample> samples = reader.readAllEvents(SignalSample.class);
            Assert.isEqual(samples.size(), 2, "AP JFR reader should expose signal samples separately");
            assert samples.get(0).correlationId == ((epoch << 32) | 1);
            assert samples.get(1).correlationId == ((epoch << 32) | 2);
        }
        try (JfrReader reader = new JfrReader(path)) {
            List<SignalCapture> contexts = reader.readAllEvents(SignalCapture.class);
            Assert.isGreaterOrEqual(contexts.size(), 2,
                    "AP JFR reader should expose per-chunk contexts");
            assert contexts.stream().allMatch(context -> "queued".equals(context.signalDelivery));
        }
        try (JfrReader reader = new JfrReader(path)) {
            List<SignalCaptureStats> stats = reader.readAllEvents(SignalCaptureStats.class);
            Assert.isEqual(stats.size(), 1, "AP JFR reader should expose terminal stats");
            assert stats.get(0).admittedSignals == 6;
        }
        try (JfrReader reader = new JfrReader(path)) {
            Assert.isGreaterOrEqual(reader.readAllEvents(ExecutionSample.class).size(), 1,
                    "combined recording should retain CPU samples separately");
        }
    }
}
