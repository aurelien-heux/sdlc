package dev.sdlc.agentdesign.application;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.*;
import dev.sdlc.agentdesign.domain.*;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.trace.Edge;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.TraceabilityGraphPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/** FR-DES-1..3: APPROVED spec in, PROPOSED design artifacts out (alternatives mandatory). */
public final class GenerateDesignUseCase {
    static final String GRAPH_SCOPE_ASSUMPTION =
            "design grounded in graph summaries, not source code (Phase 1B scope)";
    static final String SYSTEM_PROMPT = """
            You are a design agent. From the approved specification (and its upstream context), \
            produce design elements, ADRs and API contracts. Every ADR must list at least TWO \
            considered alternatives with trade-offs — never decide silently. Stay consistent with \
            the EXISTING DESIGN summaries provided. Respond with ONLY a JSON object: \
            {"elements":[{"title","description"}], \
             "adrs":[{"title","context","decision","alternatives":[{"option","tradeoff"}],"consequences":[]}], \
             "apiContracts":[{"title","contract"}]}""";

    private final LanguageModelPort model;
    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort repo;
    private final EventPublisherPort events;
    private final RunTracePort trace;
    private final DesignDraftParser parser;
    private final String agentVersion;
    private final Guardrails guardrails;

    public GenerateDesignUseCase(LanguageModelPort model, TraceabilityGraphPort graph,
                                 ArtifactRepositoryPort repo, EventPublisherPort events,
                                 RunTracePort trace, DesignDraftParser parser,
                                 String agentVersion, Guardrails guardrails) {
        this.model = model; this.graph = graph; this.repo = repo; this.events = events;
        this.trace = trace; this.parser = parser; this.agentVersion = agentVersion;
        this.guardrails = guardrails;
    }

    public List<ArtifactId> generate(ArtifactId specId) {
        var spec = graph.get(specId).orElseThrow(() ->
                new IllegalStateException("unknown specification: " + specId));
        if (spec.status() != NodeStatus.APPROVED)
            throw new IllegalStateException(specId + " is " + spec.status() + ", expected APPROVED");

        // upstream req/uc bodies omitted: the spec body already embeds their acceptance criteria (1B scope)
        String task = "# Specification " + specId + " — " + spec.title() + "\n"
                + repo.read(spec.repoPath()).orElse("(file missing)")
                + "\n\n# Existing design summaries\n" + existingDesignSummaries();
        var loop = new AgentLoop(model, new ToolRegistry(List.of()), trace, guardrails);
        var result = loop.run("design-" + UUID.randomUUID(), SYSTEM_PROMPT, task);

        var draft = parser.parse(result.finalText());
        var now = Instant.now();
        var produced = new ArrayList<ArtifactId>();
        for (var e : draft.elements())
            produced.add(write(nextId("DES"), NodeType.DESIGN_ELEMENT, "DesignElement", e.title(),
                    e.description(), spec, now));
        for (var a : draft.adrs())
            produced.add(write(nextId("ADR"), NodeType.ADR, "ADR", a.title(), adrBody(a), spec, now));
        for (var c : draft.apiContracts())
            produced.add(write(nextId("API"), NodeType.API_CONTRACT, "ApiContract", c.title(),
                    c.contract(), spec, now));
        return produced;
    }

    private String existingDesignSummaries() {
        var out = new StringBuilder();
        for (var prefix : List.of("DES", "ADR", "API"))
            for (int i = 1; i < 10_000; i++) {
                var candidate = graph.get(ArtifactId.of(String.format(Locale.ROOT, "%s-%04d", prefix, i)));
                if (candidate.isEmpty()) break;
                var n = candidate.get();
                if (n.status() == NodeStatus.APPROVED)
                    out.append("- ").append(n.id()).append(" (").append(n.type()).append("): ")
                       .append(n.title()).append('\n');
            }
        return out.isEmpty() ? "(none yet)" : out.toString();
    }

    private static String adrBody(AdrDraft a) {
        return "## Context\n\n" + a.context() + "\n\n## Decision\n\n" + a.decision()
                + "\n\n## Alternatives\n\n" + a.alternatives().stream()
                        .map(alt -> "- **" + alt.option() + "** — " + alt.tradeoff())
                        .collect(Collectors.joining("\n"))
                + (a.consequences().isEmpty() ? "" : "\n\n## Consequences\n\n" + a.consequences().stream()
                        .map(c -> "- " + c).collect(Collectors.joining("\n")));
    }

    private ArtifactId write(ArtifactId id, NodeType type, String typeName, String title,
                             String bodyText, Node spec, Instant now) {
        var provenance = Provenance.generated(
                List.of(spec.id().value() + "@" + spec.blobSha()),
                agentVersion, 0.75, List.of(GRAPH_SCOPE_ASSUMPTION));
        String dir = switch (type) {
            case DESIGN_ELEMENT -> "designs"; case ADR -> "adrs"; case API_CONTRACT -> "apis";
            default -> throw new IllegalStateException("unexpected type " + type);
        };
        String repoPath = dir + "/" + id.value() + ".md";
        String content = String.format(Locale.ROOT, """
                ---
                id: %s
                type: %s
                title: %s
                status: PROPOSED
                derivesFrom: [%s]
                provenance:
                  sourceRefs: [%s]
                  generatedBy: %s
                  confidence: %.2f
                  assumptions: [%s]
                  humanApproved: false
                ---
                %s
                """, id.value(), typeName, yq(title),
                yq(spec.id().value() + "@" + spec.blobSha()),
                yq(spec.id().value() + "@" + spec.blobSha()),
                yq(provenance.generatedBy()), provenance.confidence(),
                yq(GRAPH_SCOPE_ASSUMPTION), bodyText);
        var sha = repo.write(repoPath, content);
        graph.upsert(new Node(id, type, title, repoPath, sha, NodeStatus.PROPOSED, 1,
                provenance, now, now));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, id, spec.id(), spec.blobSha(),
                agentVersion, now));
        events.publish(new ArtifactProposed(id));
        return id;
    }

    private ArtifactId nextId(String prefix) {
        for (int i = 1; i < 10_000; i++) {
            var candidate = ArtifactId.of(String.format(Locale.ROOT, "%s-%04d", prefix, i));
            if (graph.get(candidate).isEmpty()) return candidate;
        }
        throw new IllegalStateException(prefix + " id space exhausted");
    }

    private static String yq(String s) {
        return "'" + s.replaceAll("[\\r\\n]+", " ").replace("'", "''") + "'";
    }
}
