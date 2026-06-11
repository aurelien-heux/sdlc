package dev.sdlc.agent.port;

/** Outbound port for executing the configured test command against a target repo (FR-TEST-4). */
public interface TestRunnerPort {
    /** Runs the CONFIGURED command in the target repo; returns pass/fail + tail of output. */
    RunResult run();
    record RunResult(boolean passed, String outputTail) {}
}
