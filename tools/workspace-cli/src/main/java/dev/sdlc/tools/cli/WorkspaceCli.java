package dev.sdlc.tools.cli;

import dev.sdlc.adapter.common.FileArtifactRepository;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.governance.RevalidateArtifactUseCase;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.InMemoryTraceabilityGraph;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.ProjectionBuilder;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.NoSuchElementException;

/**
 * LLM-free operator CLI over the artifact workspace. The graph is a projection rebuilt
 * from the files on every invocation, so this needs no daemon and no shared state:
 * `stale` reports every NEEDS_REVALIDATION node, `revalidate` lets a human re-bless a
 * downstream artifact against a deliberately changed upstream (re-stamps the file pin).
 */
public final class WorkspaceCli {
    static final int OK = 0;
    static final int USAGE = 2;
    static final int FAILED = 3;

    private final Path workspace;
    private final PrintStream out;
    private final String validatedBy;

    public WorkspaceCli(Path workspace, PrintStream out, String validatedBy) {
        this.workspace = workspace;
        this.out = out;
        this.validatedBy = validatedBy;
    }

    public int run(String... args) {
        if (args.length == 1 && args[0].equals("stale")) return stale();
        if (args.length == 3 && args[0].equals("revalidate")) return revalidate(args[1], args[2]);
        out.println("usage: workspace-cli stale");
        out.println("       workspace-cli revalidate <DOWNSTREAM-ID> <UPSTREAM-ID>");
        out.println("workspace root: -Dworkspace=<dir> (default: ./workspace)");
        return USAGE;
    }

    private InMemoryTraceabilityGraph rebuild() {
        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, graph);
        return graph;
    }

    private int stale() {
        var stale = rebuild().staleNodes().stream()
                .sorted(Comparator.comparing(n -> n.id().value()))
                .toList();
        if (stale.isEmpty()) {
            out.println("nothing needs revalidation");
            return OK;
        }
        for (Node n : stale)
            out.println(n.id().value() + " (" + n.type() + ") — " + n.title());
        return OK;
    }

    private int revalidate(String downstream, String upstream) {
        var graph = rebuild();
        try {
            var down = ArtifactId.of(downstream);
            var up = ArtifactId.of(upstream);
            new RevalidateArtifactUseCase(graph, new FileArtifactRepository(workspace), Instant::now)
                    .revalidate(down, up, validatedBy);
            out.println(downstream + " revalidated against " + upstream
                    + " (now " + graph.get(down).orElseThrow().status() + ") by " + validatedBy);
            return OK;
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException e) {
            out.println(e.getMessage());
            return FAILED;
        }
    }
}
