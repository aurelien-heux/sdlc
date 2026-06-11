package dev.sdlc.agentintent.application;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.LanguageModelPort;
import dev.sdlc.agent.port.RunTracePort;
import dev.sdlc.agent.port.ToolRegistry;
import dev.sdlc.domain.*;
import dev.sdlc.trace.Edge;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.TraceabilityGraphPort;
import jakarta.json.Json;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** FR-INT-2: compare new intent artifacts against existing ones; record advisory edges. */
public final class ReviewFlagsUseCase {
    public record Flag(ArtifactId newId, ArtifactId existingId, EdgeType relation, String reason) {}

    static final String SYSTEM_PROMPT = """
            You compare NEW requirement/goal/use-case artifacts against EXISTING ones. \
            Report only real overlaps or contradictions. Respond with ONLY \
            {"flags":[{"newId","existingId","relation":"DUPLICATES|CONFLICTS_WITH","reason"}]} \
            — an empty flags array when nothing overlaps.""";

    private static final EnumSet<NodeType> INTENT_TYPES =
            EnumSet.of(NodeType.GOAL, NodeType.REQUIREMENT, NodeType.NFR, NodeType.USE_CASE);

    private final LanguageModelPort model;
    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort repo;
    private final RunTracePort trace;
    private final Guardrails guardrails;
    private final String agentVersion;

    public ReviewFlagsUseCase(LanguageModelPort model, TraceabilityGraphPort graph,
                              ArtifactRepositoryPort repo, RunTracePort trace,
                              Guardrails guardrails, String agentVersion) {
        this.model = model; this.graph = graph; this.repo = repo; this.trace = trace;
        this.guardrails = guardrails; this.agentVersion = agentVersion;
    }

    public List<Flag> reviewAgainstExisting(List<ArtifactId> newIds) {
        var newNodes = newIds.stream().map(id -> graph.get(id).orElseThrow()).toList();
        var existing = allIntentNodes().stream()
                .filter(n -> !newIds.contains(n.id()))
                .filter(n -> n.status() != NodeStatus.DEPRECATED)
                .toList();
        if (existing.isEmpty()) return List.of();

        String task = "NEW:\n" + describe(newNodes) + "\nEXISTING:\n" + describe(existing);
        List<Flag> flags;
        try {
            var loop = new AgentLoop(model, new ToolRegistry(List.of()), trace, guardrails);
            var result = loop.run("intent-review-" + UUID.randomUUID(), SYSTEM_PROMPT, task);
            flags = parse(result.finalText());
        } catch (RuntimeException e) {
            // the review is advisory (FR-INT-2): a failed pass must never poison
            // the already-written artifacts — degrade, don't die.
            System.err.println("[intent-review] advisory pass failed: " + e.getMessage());
            return List.of();
        }
        var now = Instant.now();
        for (var f : flags)
            graph.link(Edge.current(f.relation(), f.newId(), f.existingId(),
                    graph.get(f.existingId()).map(Node::blobSha).orElse("unknown"), agentVersion, now));
        surfaceInFiles(flags, now);
        return flags;
    }

    /** Spec §9 step 4: the human gate reviews FILES, so flags must be visible there,
     *  not only as graph edges. Rewriting the from-node is safe: its own derivesFrom
     *  edges pin upstream shas, so nothing propagates. */
    private void surfaceInFiles(List<Flag> flags, Instant now) {
        var byNewId = flags.stream().collect(Collectors.groupingBy(Flag::newId,
                java.util.LinkedHashMap::new, Collectors.toList()));
        for (var entry : byNewId.entrySet()) {
            var node = graph.get(entry.getKey()).orElseThrow();
            var content = repo.read(node.repoPath()).orElse("");
            var section = new StringBuilder(content);
            if (!content.isEmpty() && !content.endsWith("\n")) section.append('\n');
            section.append("\n## Review flags\n\n");
            for (var f : entry.getValue())
                section.append("- ").append(f.relation()).append(' ')
                        .append(f.existingId()).append(": ").append(f.reason()).append('\n');
            var newSha = repo.write(node.repoPath(), section.toString());
            graph.upsert(node.withContentChange(newSha, now));
        }
    }

    private List<Node> allIntentNodes() {
        // Phase 1B: the port has no listing by type; enumerate via the id probe used for allocation.
        // This relies on ids being allocated densely from 1 (true: nextId always takes the first gap).
        // It breaks if an id is ever hard-deleted — acceptable Phase 1B (nothing deletes nodes);
        // a listByType port method is the clean Phase 2 fix.
        var out = new ArrayList<Node>();
        for (var prefix : List.of("GOAL", "REQ", "NFR", "UC"))
            for (int i = 1; i < 10_000; i++) {
                var candidate = graph.get(ArtifactId.of(String.format("%s-%04d", prefix, i)));
                if (candidate.isEmpty()) break;
                if (INTENT_TYPES.contains(candidate.get().type())) out.add(candidate.get());
            }
        return out;
    }

    private static String describe(List<Node> nodes) {
        return nodes.stream().map(n -> "- " + n.id() + " (" + n.type() + "): " + n.title())
                .collect(Collectors.joining("\n"));
    }

    private List<Flag> parse(String output) {
        String json = output.strip();
        if (json.startsWith("```"))
            json = json.substring(json.indexOf('\n') + 1, json.lastIndexOf("```")).strip();
        try (var reader = Json.createReader(new StringReader(json))) {
            return reader.readObject().getJsonArray("flags").stream()
                    .map(JsonValue::asJsonObject)
                    .map(o -> new Flag(ArtifactId.of(o.getString("newId")),
                            ArtifactId.of(o.getString("existingId")),
                            EdgeType.valueOf(o.getString("relation")),
                            o.getString("reason")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalArgumentException("review verdict is not the expected JSON: " + e.getMessage(), e);
        }
    }
}
