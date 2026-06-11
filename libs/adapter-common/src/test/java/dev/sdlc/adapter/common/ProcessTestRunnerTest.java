package dev.sdlc.adapter.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessTestRunnerTest {
    @Test
    void zeroExitIsPassNonZeroIsFail(@TempDir Path repo) {
        assertThat(new ProcessTestRunner(repo, List.of("sh", "-c", "echo ok; exit 0")).run().passed()).isTrue();
        var failed = new ProcessTestRunner(repo, List.of("sh", "-c", "echo boom; exit 1")).run();
        assertThat(failed.passed()).isFalse();
        assertThat(failed.outputTail()).contains("boom");
    }
}
