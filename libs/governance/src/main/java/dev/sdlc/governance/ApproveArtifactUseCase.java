package dev.sdlc.governance;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.GitPort;
import dev.sdlc.agent.port.HumanInTheLoopPort;
import dev.sdlc.agent.port.HumanInTheLoopPort.ApprovalDecision;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.NodeStatus;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.TraceabilityGraphPort;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * FR-HITL-1 / UC-0005: no node reaches APPROVED without a recorded human approver.
 * The decision is persisted to the artifact file's frontmatter (canonical store) AND the
 * graph; the file rewrite intentionally does not trigger change propagation — approval
 * is a metadata change, not a content change for dependents.
 * When a GitPort is wired (git-approval profile), approval also merges the proposal branch
 * into main, publishing the artifact atomically.
 */
public final class ApproveArtifactUseCase {
    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort repo;
    private final HumanInTheLoopPort human;
    private final Supplier<Instant> clock;
    private final GitPort git; // nullable: plain-file mode

    public ApproveArtifactUseCase(TraceabilityGraphPort graph, ArtifactRepositoryPort repo,
                                  HumanInTheLoopPort human, Supplier<Instant> clock) {
        this(graph, repo, human, clock, null);
    }

    public ApproveArtifactUseCase(TraceabilityGraphPort graph, ArtifactRepositoryPort repo,
                                  HumanInTheLoopPort human, Supplier<Instant> clock,
                                  GitPort git) {
        this.graph = graph; this.repo = repo; this.human = human; this.clock = clock; this.git = git;
    }

    public ApprovalDecision review(ArtifactId id) {
        var node = graph.get(id).orElseThrow(() -> new IllegalStateException("unknown node " + id));
        if (node.status() != NodeStatus.PROPOSED)
            throw new IllegalStateException(id + " is " + node.status() + ", expected PROPOSED");

        var decision = human.requestApproval(id, node.title());
        var now = clock.get();
        Node updated;
        if (decision.approved()) {
            updated = node.withStatus(NodeStatus.APPROVED,
                    node.provenance().approve(decision.reviewer(), now), now);
        } else {
            updated = node.withStatus(NodeStatus.DRAFT, node.provenance(), now);
        }
        graph.upsert(persistFrontmatter(updated, decision, now));
        if (decision.approved() && git != null)
            git.merge("proposal/" + id.value(), "approval: " + id.value() + " by " + decision.reviewer());
        return decision;
    }

    /** Rewrites status/humanApproved/approvedBy in the file's frontmatter; returns the node with the new sha. */
    private Node persistFrontmatter(Node node, ApprovalDecision decision, Instant now) {
        var content = repo.read(node.repoPath()).orElseThrow(() -> new IllegalStateException(
                "canonical artifact file missing: " + node.repoPath()));

        var updated = content.replaceFirst("(?m)^status: .*$", "status: " + node.status());
        if (decision.approved()) {
            updated = updated.replaceFirst("(?m)^(\\s*)humanApproved: false$",
                    "$1humanApproved: true\n$1approvedBy: " + java.util.regex.Matcher.quoteReplacement(
                            "'" + decision.reviewer().replace("'", "''") + "'"));
        }
        var sha = repo.write(node.repoPath(), updated);
        return node.withContentChange(sha, now);
    }
}
