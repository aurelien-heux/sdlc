package dev.sdlc.agenttestgen.application;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.TestRunnerPort;
import dev.sdlc.agent.port.TestRunnerPort.RunResult;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.TraceabilityGraphPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * FR-TEST-4: places the feature + skeleton payloads into the configured target repo,
 * runs the CONFIGURED test command (never model-chosen — spec §4.5), and records
 * `lastRun`/`lastRunAt` in both TEST artifacts' frontmatter.
 */
public final class PlaceAndRunUseCase {
    private static final Map<String, String> EXTENSIONS = Map.of(
            "java", "java", "kotlin", "kt", "typescript", "ts", "python", "py");

    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort workspace;
    private final Path targetRepo;
    private final String featuresDir;
    private final String stepsDir;
    private final TestRunnerPort runner;
    private final Supplier<Instant> clock;

    public PlaceAndRunUseCase(TraceabilityGraphPort graph, ArtifactRepositoryPort workspace,
                              Path targetRepo, String featuresDir, String stepsDir,
                              TestRunnerPort runner, Supplier<Instant> clock) {
        this.graph = graph; this.workspace = workspace; this.targetRepo = targetRepo;
        this.featuresDir = featuresDir; this.stepsDir = stepsDir;
        this.runner = runner; this.clock = clock;
    }

    public RunResult placeAndRun(ArtifactId featureId, ArtifactId stepsId) {
        // feature body goes verbatim; skeleton body has its code fence stripped,
        // extension chosen by the fence's language line
        var featureBody = body(featureId);
        place(featuresDir, featureId.value() + ".feature", featureBody);
        var skeleton = stripFence(body(stepsId), stepsId);
        place(stepsDir, stepsId.value() + "." + skeleton.extension(), skeleton.content());

        var result = runner.run();

        stamp(featureId, result);
        stamp(stepsId, result);
        return result;
    }

    private String body(ArtifactId id) {
        var node = graph.get(id).orElseThrow(() -> new IllegalStateException("unknown TEST node: " + id));
        var content = workspace.read(node.repoPath()).orElseThrow(() ->
                new IllegalStateException("artifact file missing: " + node.repoPath()));
        return new FrontmatterParser().parse(content, node.repoPath()).body();
    }

    private void place(String dir, String fileName, String payload) {
        try {
            Path target = targetRepo.resolve(dir).resolve(fileName);
            Files.createDirectories(target.getParent());
            Files.writeString(target, payload);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private record Skeleton(String extension, String content) {}

    private static Skeleton stripFence(String skeletonBody, ArtifactId id) {
        List<String> lines = skeletonBody.strip().lines().toList();
        if (lines.size() < 2 || !lines.getFirst().startsWith("```") || !lines.getLast().equals("```"))
            throw new IllegalStateException(id + " skeleton body is not a fenced code block");
        var ext = EXTENSIONS.get(lines.getFirst().substring(3).trim());
        if (ext == null)
            throw new IllegalStateException(id + " skeleton fence has unsupported language: "
                    + lines.getFirst());
        return new Skeleton(ext, String.join("\n", lines.subList(1, lines.size() - 1)) + "\n");
    }

    /** Replaces any previous lastRun/lastRunAt stamp, then inserts the new one after status:. */
    private void stamp(ArtifactId id, RunResult result) {
        var node = graph.get(id).orElseThrow();
        var content = workspace.read(node.repoPath()).orElseThrow();
        var cleaned = content.replaceAll("(?m)^lastRun(At)?: .*\\n", "");
        var stamped = cleaned.replaceFirst("(?m)^(status: .*)$",
                "$1\nlastRun: " + (result.passed() ? "passed" : "failed")
                + "\nlastRunAt: '" + clock.get() + "'");
        var sha = workspace.write(node.repoPath(), stamped);
        // metadata change, no propagation — same rule as approvals (status untouched,
        // dependents are not flagged; only the node's sha is synced to the file)
        graph.upsert(node.withContentChange(sha, clock.get()));
    }
}
