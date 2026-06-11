package dev.sdlc.governance;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.GitPort;
import dev.sdlc.agent.port.HumanInTheLoopPort;
import dev.sdlc.agent.port.HumanInTheLoopPort.ApprovalDecision;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.EdgeType;
import dev.sdlc.domain.NodeStatus;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.TraceabilityGraphPort;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        if (decision.approved()) {
            restampDependents(id, new HashSet<>());
            if (git != null)
                git.merge("proposal/" + id.value(), "approval: " + id.value() + " by " + decision.reviewer());
        }
        return decision;
    }

    /** Approval rewrites this file (new sha); dependents pinning the old sha would look stale
     *  at rebuild though nothing semantic changed. Re-stamp their pins to the new sha —
     *  line-scoped to derivesFrom (provenance keeps ORIGINAL grounding shas) — and cascade,
     *  since re-stamping changes the dependent's own sha too. DAG + visited set: terminates.
     *  Dependents linked by SATISFIES would carry their pin in a different frontmatter key;
     *  Phase 1B writes none, so the derivesFrom-scoped rewrite is correct for now. */
    private void restampDependents(ArtifactId approvedId, Set<ArtifactId> visited) {
        var approved = graph.get(approvedId).orElseThrow();
        for (var dependent : graph.downstreamOf(approvedId, EdgeType.DERIVES_FROM, EdgeType.SATISFIES)) {
            // visited-by-NODE: in a diamond (D derives from B and C) D is re-stamped only via the
            // first-reached parent; unreachable today (downstream artifacts are generated only after
            // upstream approval) — revisit if multi-parent batches arrive (Phase 2)
            if (!visited.add(dependent.id())) continue;
            var content = repo.read(dependent.repoPath()).orElse(null);
            if (content == null) continue; // proposal-branch-only dependents: next scan re-projects
            var pinRegex = Pattern.quote(approvedId.value()) + "@[0-9a-f]{40}";
            var newPin = Matcher.quoteReplacement(approvedId.value() + "@" + approved.blobSha());
            var rewritten = Pattern.compile("(?m)^(derivesFrom:.*)$").matcher(content)
                    .replaceAll(m -> m.group(1).replaceAll(pinRegex, newPin));
            if (rewritten.equals(content)) continue;
            var sha = repo.write(dependent.repoPath(), rewritten);
            graph.revalidate(dependent.id().value() + "->" + approvedId.value() + ":DERIVES_FROM",
                    "approval-restamp");
            graph.upsert(graph.get(dependent.id()).orElseThrow()
                    .withContentChange(sha, clock.get()));
            restampDependents(dependent.id(), visited);
        }
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
