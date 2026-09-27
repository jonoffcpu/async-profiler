/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package test.jfr;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import one.jfr.JfrReader;
import one.jfr.event.ExecutionSample;
import one.profiler.test.Assert;
import one.profiler.test.Os;
import one.profiler.test.Output;
import one.profiler.test.Test;
import one.profiler.test.TestProcess;
import test.alloc.Hello;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class JfrTests {

    @Test(mainClass = CpuLoad.class, agentArgs = "start,event=cpu,file=%profile.jfr")
    public void cpuLoad(TestProcess p) throws Exception {
        p.waitForExit();
        assert p.exitCode() == 0;

        String jfrOutPath = p.getFilePath("%profile");
        String spikePattern = "test/jfr/CpuLoad.cpuSpike.*";
        String normalLoadPattern = "test/jfr/CpuLoad.normalCpuLoad.*";

        Output out = Output.convertJfrToCollapsed(jfrOutPath, "--to", "1500");
        assert !out.contains(spikePattern);
        assert out.contains(normalLoadPattern);

        out = Output.convertJfrToCollapsed(jfrOutPath, "--from", "1500", "--to", "3500");
        assert out.contains(spikePattern);
        assert out.contains(normalLoadPattern);

        out = Output.convertJfrToCollapsed(jfrOutPath, "--from", "3500");
        assert !out.contains(spikePattern);
        assert out.contains(normalLoadPattern);
    }

    @Test(mainClass = CpuLoad.class, agentArgs = "start,event=cpu,alluser,wall,record-cpu,file=%profile.jfr", os = Os.LINUX)
    public void recordCpuMultiEngine(TestProcess p) throws Exception {
        p.waitForExit();
        assert p.exitCode() == 0;

        Output out = Output.convertJfrToCollapsed(p.getFilePath("%profile"), "--wall");
        assert !out.contains("^CPU-\\d+;");

        out = Output.convertJfrToCollapsed(p.getFilePath("%profile"), "--cpu");
        assert out.contains("^CPU-\\d+;");
    }

    /**
     * Test to validate JDK APIs to parse Cpu profiling JFR output
     *
     * @param p The test process to profile with.
     * @throws Exception Any exception thrown during profiling JFR output parsing.
     */
    @Test(mainClass = JfrCpuProfiling.class)
    public void parseRecording(TestProcess p) throws Exception {
        p.profile("-d 3 -e cpu -f %f.jfr");
        StringBuilder builder = new StringBuilder();
        try (RecordingFile recordingFile = new RecordingFile(p.getFile("%f").toPath())) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                builder.append(event);
            }
        }

        String parsedOut = builder.toString();
        assert parsedOut.contains("jdk.ExecutionSample");
        assert parsedOut.contains("test.jfr.JfrCpuProfiling.method1()");
    }

    /**
     * Test to validate JDK APIs to parse Multimode profiling JFR output
     *
     * @param p The test process to profile with.
     * @throws Exception Any exception thrown during profiling JFR output parsing.
     */
    @Test(mainClass = JfrMultiModeProfiling.class, agentArgs = "start,event=cpu,alloc,lock=0,quiet,jfr,file=%f", output = true, jvmVer = {9, Integer.MAX_VALUE})
    @Test(mainClass = JfrMultiModeProfiling.class, agentArgs = "start,event=cpu,alloc,lock=0,quiet,jfr,file=%f", output = true, jvmVer = 8, jvmArgs = "-XX:+UseG1GC -XX:+UseCountedLoopSafepoints")
    public void parseMultiModeRecording(TestProcess p) throws Exception {
        Output output = p.waitForExit(TestProcess.STDOUT);
        assert p.exitCode() == 0;

        long totalLockDurationMillis = output.stream().mapToLong(Long::parseLong).sum();

        double jfrTotalLockDurationMillis = 0;
        Map<String, Integer> eventsCount = new HashMap<>();
        try (RecordingFile recordingFile = new RecordingFile(p.getFile("%f").toPath())) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                String eventName = event.getEventType().getName();
                if (eventName.equals("jdk.JavaMonitorEnter")) {
                    jfrTotalLockDurationMillis += event.getDuration().toNanos() / 1_000_000.0;
                }
                eventsCount.put(eventName, eventsCount.getOrDefault(eventName, 0) + 1);
            }
        }

        Assert.isGreater(eventsCount.get("jdk.ExecutionSample"), 50);
        Assert.isGreater(eventsCount.get("jdk.JavaMonitorEnter"), 10);
        Assert.isGreater(jfrTotalLockDurationMillis / totalLockDurationMillis, 0.80);
        Assert.isGreater(eventsCount.get("jdk.ObjectAllocationInNewTLAB"), 50);
    }

    /**
     * Test to validate profiling output with "--all" flag without event override.
     *
     * @param p The test process to profile with.
     * @throws Exception Any exception thrown during profiling JFR output parsing.
     */
    @Test(mainClass = JfrMultiModeProfiling.class, agentArgs = "start,all,file=%f.jfr", nameSuffix = "noOverride")
    @Test(mainClass = JfrMultiModeProfiling.class, agentArgs = "start,all,alloc=262143,file=%f.jfr", nameSuffix = "overrideAlloc")
    public void allModeNoEventOverride(TestProcess p) throws Exception {
        p.waitForExit();
        assert p.exitCode() == 0;
        Set<String> events = new HashSet<>();
        String vmSpecificationVersion = null;
        try (RecordingFile recordingFile = new RecordingFile(p.getFile("%f").toPath())) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                String eventName = event.getEventType().getName();

                if (eventName.equals("jdk.InitialSystemProperty") &&
                    event.getString("key").equals("java.vm.specification.version")) {
                    vmSpecificationVersion = event.getString("value");
                }

                events.add(eventName);
            }
        }
        if (p.currentOs() == Os.LINUX) { // macOS uses Wall Clock profiling engine
            assert events.contains("jdk.ExecutionSample"); // cpu profiling
        }
        assert events.contains("jdk.JavaMonitorEnter"); // lock profiling
        assert events.contains("jdk.ObjectAllocationInNewTLAB"); // alloc profiling
        assert events.contains("profiler.WallClockSample"); // wall clock profiling
        assert events.contains("profiler.LiveObject") || checkJdkVersionEarlierThan11(vmSpecificationVersion); // profiling of live objects
        assert events.contains("profiler.Malloc"); // nativemem profiling
        assert events.contains("profiler.Free"); // nativemem profiling
    }

    /**
     * Test to validate profiling output with "--all" flag with event override
     *
     * @param p The test process to profile with.
     * @throws Exception Any exception thrown during profiling JFR output parsing.
     */
    @Test(mainClass = JfrMultiModeProfiling.class, agentArgs = "start,all,event=java.util.Properties.getProperty,alloc=262143,file=%f.jfr")
    public void allModeEventOverride(TestProcess p) throws Exception {
        p.waitForExit();
        assert p.exitCode() == 0;
        Set<String> events = new HashSet<>();
        String vmSpecificationVersion = null;
        try (RecordingFile recordingFile = new RecordingFile(p.getFile("%f").toPath())) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                String eventName = event.getEventType().getName();

                if (eventName.equals("jdk.InitialSystemProperty") &&
                    event.getString("key").equals("java.vm.specification.version")) {
                    vmSpecificationVersion = event.getString("value");
                }

                events.add(eventName);
                if (eventName.equals("jdk.ExecutionSample")) {
                    // This means that only instrumented method was profiled and overall CPU profiling was skipped
                    assert event.getStackTrace().toString().contains("java.util.Properties.getProperty");
                }
            }
        }
        assert events.contains("jdk.JavaMonitorEnter"); // lock profiling
        assert events.contains("jdk.ObjectAllocationInNewTLAB"); // alloc profiling
        assert events.contains("profiler.WallClockSample"); // wall clock profiling
        assert events.contains("profiler.LiveObject") || checkJdkVersionEarlierThan11(vmSpecificationVersion); // profiling of live objects
        assert events.contains("profiler.Malloc"); // nativemem profiling
        assert events.contains("profiler.Free"); // nativemem profiling
    }

    // Simple smoke test, nothing in particular is tested
    @Test(mainClass = JfrCpuProfiling.class)
    public void jfrSyncSmoke(TestProcess p) throws Exception {
        Output out = p.profile("-d 1 --jfrsync default --jfropts 4 -f %f.jfr");

        Set<String> events = new HashSet<>();
        try (RecordingFile recordingFile = new RecordingFile(p.getFile("%f").toPath())) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                events.add(event.getEventType().getName());
            }
        }

        assert events.contains("jdk.OSInformation");
        assert events.contains("jdk.CPUInformation");
        assert events.contains("jdk.JVMInformation");
        assert events.contains("jdk.InitialSystemProperty");
        assert events.contains("jdk.NativeLibrary");
    }

    @Test(mainClass = ExceptionThrow.class, nameSuffix = "default",
            agentArgs = "start,event=cpu,jfrsync=+jdk.JavaExceptionThrow,file=%f.jfr")
    @Test(mainClass = ExceptionThrow.class, nameSuffix = "withStackTrace",
            agentArgs = "start,event=cpu,jfrsync=+jdk.JavaExceptionThrow#stackTrace=true+jdk.MethodTrace#filter,file=%f.jfr")
    public void jfrSyncEventSettings(TestProcess p) throws Exception {
        p.waitForExit();
        assert p.exitCode() == 0;

        boolean found = false;
        try (RecordingFile recordingFile = new RecordingFile(p.getFile("%f").toPath())) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                if (event.getEventType().getName().equals("jdk.JavaExceptionThrow")
                        && ExceptionThrow.MESSAGE.equals(event.getString("message"))) {
                    if (p.test().nameSuffix().equals("withStackTrace")) {
                        assert event.getStackTrace() != null;
                        assert event.getStackTrace().toString().contains("test.jfr.ExceptionThrow.throwException");
                    } else {
                        assert event.getStackTrace() == null;
                    }
                    found = true;
                    break;
                }
            }
        }
        assert found;
    }

    /**
     * Test to validate time to safepoint profiling
     *
     * @param p The test process to profile with.
     * @throws Exception Any exception thrown during profiling JFR output parsing.
     */
    @Test(mainClass = Ttsp.class, agentArgs = "start,event=cpu,ttsp,interval=1ms,jfr,file=%f")
    public void ttsp(TestProcess p) throws Exception {
        p.waitForExit();
        assert p.exitCode() == 0;
        assert !containsSamplesOutsideWindow(p) : "Expected no samples outside of ttsp window";

        Output out = Output.convertJfrToCollapsed(p.getFilePath("%f"));
        Assert.isGreaterOrEqual(out.samples("delaySafepoint"), 3);
    }

    /**
     * Test to validate time to safepoint profiling (recording the windows only, profiling starts immediately)
     *
     * @param p The test process to profile with.
     * @throws Exception Any exception thrown during profiling JFR output parsing.
     */
    @Test(mainClass = Ttsp.class, agentArgs = "start,event=cpu,ttsp,nostop,interval=1ms,jfr,file=%f")
    public void ttspNostop(TestProcess p) throws Exception {
        p.waitForExit();
        assert p.exitCode() == 0;
        assert containsSamplesOutsideWindow(p) : "Expected to find samples outside of ttsp window";
    }

    @Test(mainClass = Hello.class, agentArgs = "start,begin=write,end=write,file=%f.jfr", output = true)
    public void beginEnd(TestProcess p) throws Exception {
        Output out = p.waitForExit(TestProcess.STDOUT);
        assert p.exitCode() == 0;

        assert out.contains("begin and end symbols should not resolve to the same address");
    }

    /**
     * Async-profiler should timestamp its events with the same clock the JVM uses for the JFR.
     */
    @Test(mainClass = Hello.class, nameSuffix = "tsc",
            agentArgs = "start,event=cpu,file=%f.jfr,jfrsync")
    @Test(mainClass = Hello.class, nameSuffix = "monotonic",
            jvmArgs = "-XX:+UnlockExperimentalVMOptions -XX:-UseFastUnorderedTimeStamps",
            agentArgs = "start,event=cpu,file=%f.jfr,jfrsync")
    public void clockSource(TestProcess p) throws Exception {
        p.waitForExit();
        assert p.exitCode() == 0;

        try (JfrReader jfr = new JfrReader(p.getFilePath("%f"))) {
            jfr.stopAtNewChunk = true;
            // Read JDK chunk
            jfr.readAllEvents(ExecutionSample.class);
            long ticksPerSec1 = jfr.ticksPerSec;
            // Read Async-profiler chunk
            jfr.readAllEvents(ExecutionSample.class);
            long ticksPerSec2 = jfr.ticksPerSec;

            String clock = jfr.settings.get("clock");
            if (clock.equals("monotonic")) {
                // Monotonic clock has fixed 1GHz frequency
                Assert.isEqual(ticksPerSec1, 1_000_000_000);
                Assert.isEqual(ticksPerSec2, 1_000_000_000);
            }

            if (p.test().jvmArgs().contains("-UseFastUnorderedTimeStamps")) {
                assert clock.equals("monotonic") : clock;
            }

            // Clock frequency must be aligned
            Assert.isGreater(ticksPerSec1, ticksPerSec2 * 0.95);
            Assert.isLess(ticksPerSec1, ticksPerSec2 * 1.05);
        }
    }

    /**
     * With jfrsync, the profiler's chunk is appended to the JVM's recording, and JDK 22+ readers
     * convert every chunk of a file with the first chunk's clock origin. The profiler's events must
     * therefore fall within the recording when the whole file is read, which needs the profiler's clock
     * to have the JVM's origin: the time stamp counter when the JVM uses it, which it can only on x86,
     * and otherwise the monotonic clock counted from the JVM's start.
     */
    @Test(mainClass = CpuLoad.class, os = Os.LINUX, output = true, nameSuffix = "default",
            agentArgs = "start,event=cpu,interval=10ms,file=%f.jfr,jfrsync")
    @Test(mainClass = CpuLoad.class, os = Os.LINUX, output = true, nameSuffix = "noFastTimeStamps",
            jvmArgs = "-XX:+UnlockExperimentalVMOptions -XX:-UseFastUnorderedTimeStamps",
            agentArgs = "start,event=cpu,interval=10ms,file=%f.jfr,jfrsync")
    @Test(mainClass = CpuLoad.class, os = Os.LINUX, output = true, nameSuffix = "monotonic",
            agentArgs = "start,event=cpu,interval=10ms,clock=monotonic,file=%f.jfr,jfrsync")
    public void clockAlignment(TestProcess p) throws Exception {
        p.waitForExit();
        assert p.exitCode() == 0;

        // A JVM that counts JFR ticks with the time stamp counter, above 1 GHz, can't be matched by
        // clock=monotonic; the profiler then warns instead
        ByteBuffer jvmChunk = ByteBuffer.wrap(Files.readAllBytes(p.getFile("%f").toPath()), 0, 64);
        boolean jvmTsc = jvmChunk.getLong(56) != 1_000_000_000L;
        boolean aligned = !(jvmTsc && p.test().agentArgs().contains("clock=monotonic"));

        Instant[] recording = recordingInterval(p.getFile("%f").toPath());
        Instant from = recording[0].minusSeconds(1);
        Instant to = recording[1].plusSeconds(1);
        int samples = 0;
        int outside = 0;
        Instant firstOutside = null;
        try (RecordingFile recordingFile = new RecordingFile(p.getFile("%f").toPath())) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                if (event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    samples++;
                }
                Instant time = event.getStartTime();
                if (time.isBefore(from) || time.isAfter(to)) {
                    outside++;
                    if (firstOutside == null) {
                        firstOutside = time;
                    }
                }
            }
        }
        Assert.isGreater(samples, 0);
        if (aligned) {
            assert outside == 0 : outside + " event(s) outside the recording " + recording[0] + " - "
                    + recording[1] + ", the first at " + firstOutside;
        } else {
            assert outside > 0;
            assert p.readFile(TestProcess.STDOUT).contains("not aligned with the JVM's JFR clock");
        }
    }

    // The earliest start and the latest end in the chunk headers of a JFR file
    private static Instant[] recordingInterval(Path file) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (int chunk = 0; chunk + 64 <= buffer.limit(); ) {
            long size = buffer.getLong(chunk + 8);
            long startNanos = buffer.getLong(chunk + 32);
            long durationNanos = buffer.getLong(chunk + 40);
            start = Math.min(start, startNanos);
            end = Math.max(end, startNanos + durationNanos);
            assert size > 0 : "Invalid chunk size at " + chunk;
            chunk += (int) size;
        }
        return new Instant[]{Instant.ofEpochSecond(0, start), Instant.ofEpochSecond(0, end)};
    }

    private static void assertRateLimited(int actual, int limit, int duration) {
        Assert.isLessOrEqual(actual, limit * (duration + 2));
        Assert.isGreaterOrEqual(actual, limit * (duration - 2));
    }

    /**
     * Rate limit categories are capped independently: the flood of CPU, allocation
     * and span events largely exceeds the configured per-second limits.
     */
    @Test(mainClass = RateLimitApp.class, runIsolated = true)
    public void rateLimit(TestProcess p) throws Exception {
        p.profile("-e cpu -i 10ms --alloc 1k --ratelimit cpu:50,alloc:100,span:200 -d 4 -f %f.jfr");

        Map<String, Integer> counts = new HashMap<>();
        try (RecordingFile recordingFile = new RecordingFile(p.getFile("%f").toPath())) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                counts.merge(event.getEventType().getName(), 1, Integer::sum);
            }
        }

        int duration = 4;
        assertRateLimited(counts.getOrDefault("jdk.ExecutionSample", 0), 50, duration);
        assertRateLimited(counts.getOrDefault("jdk.ObjectAllocationInNewTLAB", 0) +
                          counts.getOrDefault("jdk.ObjectAllocationOutsideTLAB", 0), 100, duration);
        assertRateLimited(counts.getOrDefault("profiler.Span", 0), 200, duration);
    }

    private boolean containsSamplesOutsideWindow(TestProcess p) throws Exception {
        TreeMap<Instant, Instant> profilerWindows = new TreeMap<>();
        List<RecordedEvent> samples = new ArrayList<>();
        try (RecordingFile recordingFile = new RecordingFile(p.getFile("%f").toPath())) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                if (event.getEventType().getName().equals("profiler.Window")) {
                    profilerWindows.put(event.getStartTime(), event.getEndTime());
                } else if (event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    samples.add(event);
                }
            }
        }

        return samples.stream().anyMatch(event -> {
            Map.Entry<Instant, Instant> entry = profilerWindows.floorEntry(event.getStartTime().plus(10, ChronoUnit.MILLIS));
            Instant entryEnd = entry == null ? Instant.MIN : entry.getValue().plus(10, ChronoUnit.MILLIS);
            // check that the current sample takes place during a profiling window, allowing for a 10ms buffer at each end
            return entryEnd.isBefore(event.getStartTime());
        });
    }

    private static boolean checkJdkVersionEarlierThan11(String vmSpecificationVersion) {
        if (vmSpecificationVersion == null) {
            throw new IllegalArgumentException("vmSpecificationVersion should not be null");
        }
        return vmSpecificationVersion.startsWith("1.") || Integer.parseInt(vmSpecificationVersion.split("\\.")[0]) < 11;
    }
}
