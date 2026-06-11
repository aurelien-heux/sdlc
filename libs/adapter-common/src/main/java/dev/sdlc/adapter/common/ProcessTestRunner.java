package dev.sdlc.adapter.common;

import dev.sdlc.agent.port.TestRunnerPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Allow-listed test execution (FR-TEST-4): the command comes from CONFIGURATION
 * (bootstrap env, e.g. SDLC_TEST_CMD); the model never chooses or composes it
 * (brief §9 guardrail in minimal form). Non-zero exit = failed; 10-minute hard timeout.
 */
public final class ProcessTestRunner implements TestRunnerPort {
    private static final int TAIL_CHARS = 4000;

    private final Path targetRepo;
    private final List<String> command;

    public ProcessTestRunner(Path targetRepo, List<String> command) {
        this.targetRepo = targetRepo;
        this.command = List.copyOf(command);
    }

    @Override public RunResult run() {
        try {
            var process = new ProcessBuilder(command)
                    .directory(targetRepo.toFile())
                    .redirectErrorStream(true)
                    .start();
            // drain on a separate thread so a chatty process can't deadlock on a full pipe
            var output = new ByteArrayOutputStream();
            var drainer = Thread.ofVirtual().start(() -> {
                try { process.getInputStream().transferTo(output); } catch (IOException ignored) {}
            });
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                // let the drainer flush what the dying process already wrote (bounded —
                // the kill closes the pipe, but don't hang forever if it doesn't)
                drainer.join(java.time.Duration.ofSeconds(2));
                return new RunResult(false,
                        tail(output.toString(StandardCharsets.UTF_8) + "\n[timed out after 10 minutes]"));
            }
            drainer.join();
            return new RunResult(process.exitValue() == 0, tail(output.toString(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running test command", e);
        }
    }

    private static String tail(String s) {
        return s.length() <= TAIL_CHARS ? s : s.substring(s.length() - TAIL_CHARS);
    }
}
