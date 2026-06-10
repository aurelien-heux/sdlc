package dev.sdlc.agent.port;

import dev.sdlc.domain.ArtifactId;

/** Clarification and approval are the loop, not an afterthought (brief §6). */
public interface HumanInTheLoopPort {
    String askClarifyingQuestion(String question);
    ApprovalDecision requestApproval(ArtifactId artifact, String summary);

    record ApprovalDecision(boolean approved, String reviewer, String feedback) {}
}
