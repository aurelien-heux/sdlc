package dev.sdlc.agentspec.adapters.outbound;

import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.StringReader;
import static org.assertj.core.api.Assertions.assertThat;

class ConsoleHumanInTheLoopTest {
    private HumanResult approvalFor(String input) {
        var hitl = new ConsoleHumanInTheLoop(new BufferedReader(new StringReader(input)), "a.dupont");
        var d = hitl.requestApproval(ArtifactId.of("SPEC-0001"), "title");
        return new HumanResult(d.approved(), d.feedback());
    }
    private record HumanResult(boolean approved, String feedback) {}

    @Test
    void parsesShortAndFullWordAnswers() {
        assertThat(approvalFor("y\n")).isEqualTo(new HumanResult(true, null));
        assertThat(approvalFor("yes\n")).isEqualTo(new HumanResult(true, null));
        assertThat(approvalFor("y ship it\n")).isEqualTo(new HumanResult(true, "ship it"));
        assertThat(approvalFor("yes looks good\n")).isEqualTo(new HumanResult(true, "looks good"));
        assertThat(approvalFor("n too vague\n")).isEqualTo(new HumanResult(false, "too vague"));
        assertThat(approvalFor("no\n")).isEqualTo(new HumanResult(false, null));
    }

    @Test
    void eofMeansRejectionWithoutFeedback() {
        assertThat(approvalFor("")).isEqualTo(new HumanResult(false, null));
    }

    @Test
    void clarifyingQuestionReturnsTheTypedAnswer() {
        var hitl = new ConsoleHumanInTheLoop(new BufferedReader(new StringReader("42 days\n")), "a.dupont");
        assertThat(hitl.askClarifyingQuestion("how long?")).isEqualTo("42 days");
    }
}
