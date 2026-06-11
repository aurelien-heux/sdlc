package dev.sdlc.adapter.git;

import dev.sdlc.agent.port.GitPort;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.TraceabilityGraphPort;

/**
 * Re-projects PROPOSED artifacts that exist only on proposal/* branches (restart recovery).
 *
 * <p>Edges for pending proposals are intentionally NOT recreated — approval re-runs persistence
 * and the next rebuild after merge links them; a pending proposal needs only its node for
 * {@code review()} to find.
 *
 * <p>After a successful merge (any strategy), {@code git diff main...branch} is empty, so merged
 * branches produce no upserts and APPROVED nodes are never clobbered.
 */
public final class ProposalScanner {
    private final GitPort git;
    private final FrontmatterParser parser;

    public ProposalScanner(GitPort git, FrontmatterParser parser) {
        this.git = git; this.parser = parser;
    }

    public void scanInto(TraceabilityGraphPort graph) {
        for (var branch : git.branches("proposal/"))
            for (var path : git.changedFiles(branch))
                if (path.endsWith(".md"))
                    git.showFile(branch, path).ifPresent(content ->
                            graph.upsert(parser.parse(content, path).node()));
    }
}
