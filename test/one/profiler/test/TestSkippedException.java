/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package one.profiler.test;

/**
 * Thrown by a test to report that a prerequisite discovered at run time is
 * missing, e.g. the profiler library lacks a test-only hook. The test is
 * counted as skipped instead of failed.
 */
public class TestSkippedException extends RuntimeException {

    public TestSkippedException(String reason) {
        super(reason);
    }
}
