package dev.sdlc.agentintent.application;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.AgentRunResult;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.*;
import dev.sdlc.agentintent.domain.*;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.trace.Edge;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.TraceabilityGraphPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** UC-0001: one source document in, PROPOSED goal/requirement/use-case artifacts out. */
public final class GenerateIntentUseCase {
    static final String SYSTEM_PROMPT = """
            You are an intent agent. From the stakeholder document provided, extract goals, \
            requirements and use cases. NEVER invent: every item must carry verbatim \
            "sourceQuotes" from the document, or explicit "assumptions". When the document is \
            materially ambiguous, use the ask_human tool (one focused question per call) before \
            finalizing. Respond at the end with ONLY a JSON object: \
            {"goals":[{"title","description","sourceQuotes":[],"assumptions":[]}], \
             "requirements":[{"title","description","kind":"functional|nfr", \
               "moscow":"MUST|SHOULD|COULD|WONT","goalTitle","sourceQuotes":[],"assumptions":[]}], \
             "useCases":[{"title","actor","mainFlow":[],"altFlows":[],"requirementTitle", \
               "sourceQuotes":[],"assumptions":[]}]} \
            Omit goalTitle/requirementTitle entirely when there is no link (never write null).""";

    private final LanguageModelPort model;
    private final HumanInTheLoopPort human;
    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort repo;
    private final EventPublisherPort events;
    private final RunTracePort trace;
    private final IntentDraftParser parser;
    private final String agentVersion;
    private final Guardrails guardrails;

    public GenerateIntentUseCase(LanguageModelPort model, HumanInTheLoopPort human,
                                 TraceabilityGraphPort graph, ArtifactRepositoryPort repo,
                                 EventPublisherPort events, RunTracePort trace,
                                 IntentDraftParser parser, String agentVersion, Guardrails guardrails) {
        this.model = model; this.human = human; this.graph = graph; this.repo = repo;
        this.events = events; this.trace = trace; this.parser = parser;
        this.agentVersion = agentVersion; this.guardrails = guardrails;
    }

    /** Returns the ids of all artifacts produced from this source document. */
    public List<ArtifactId> generate(String sourcePath, String sourceContent) {
        var askHuman = new AskHumanTool(human);
        var loop = new AgentLoop(model, new ToolRegistry(List.of(askHuman)), trace, guardrails);
        AgentRunResult result = loop.run("intent-" + UUID.randomUUID(), SYSTEM_PROMPT,
                "# Source: " + sourcePath + "\n\n" + sourceContent);

        var draft = parser.parse(result.finalText());
        var sourceRef = sourcePath + "@" + FrontmatterParser.gitBlobSha(sourceContent);
        var clarifications = askHuman.transcript();
        var now = Instant.now();
        var produced = new ArrayList<ArtifactId>();

        // goals first (requirements link to them by title)
        Map<String, ArtifactId> goalIds = new LinkedHashMap<>();
        for (var g : draft.goals()) {
            var id = nextId("GOAL");
            writeArtifact(id, NodeType.GOAL, g.title(), "goals", body(g.description(), null, null),
                    sourceRef, clarifications, g.sourceQuotes(), g.assumptions(), List.of(), now);
            goalIds.put(g.title(), id);
            produced.add(id);
        }
        Map<String, ArtifactId> reqIds = new LinkedHashMap<>();
        for (var r : draft.requirements()) {
            var id = nextId(r.kind().equals("nfr") ? "NFR" : "REQ");
            var upstream = r.goalTitle() == null ? null : goalIds.get(r.goalTitle());
            writeArtifact(id, r.kind().equals("nfr") ? NodeType.NFR : NodeType.REQUIREMENT,
                    r.title(), "requirements",
                    body(r.description(), "MoSCoW: " + r.moscow(), null),
                    sourceRef, clarifications, r.sourceQuotes(), r.assumptions(),
                    upstream == null ? List.of() : List.of(upstream), now);
            reqIds.put(r.title(), id);
            produced.add(id);
        }
        for (var u : draft.useCases()) {
            var id = nextId("UC");
            var upstream = u.requirementTitle() != null ? reqIds.get(u.requirementTitle())
                    : (goalIds.size() == 1 ? goalIds.values().iterator().next() : null);
            var flow = "### Main flow\n" + u.mainFlow().stream()
                    .map(s -> "1. " + s).collect(Collectors.joining("\n"))
                    + (u.altFlows().isEmpty() ? "" : "\n\n### Alternate flows\n" + u.altFlows().stream()
                    .map(s -> "- " + s).collect(Collectors.joining("\n")));
            writeArtifact(id, NodeType.USE_CASE, u.title(), "usecases",
                    body("Actor: " + u.actor(), null, flow),
                    sourceRef, clarifications, u.sourceQuotes(), u.assumptions(),
                    upstream == null ? List.of() : List.of(upstream), now);
            produced.add(id);
        }
        return produced;
    }

    private static String body(String description, String extra, String flow) {
        var sb = new StringBuilder(description == null ? "" : description);
        if (extra != null) sb.append("\n\n").append(extra);
        if (flow != null) sb.append("\n\n").append(flow);
        return sb.toString();
    }

    private void writeArtifact(ArtifactId id, NodeType type, String title, String dir, String bodyText,
                               String sourceRef, List<String> clarifications,
                               List<String> quotes, List<String> assumptions,
                               List<ArtifactId> derivesFrom, Instant now) {
        var refs = new ArrayList<String>();
        refs.add(sourceRef);
        for (int i = 0; i < clarifications.size(); i++) refs.add("clarification:" + (i + 1));
        // grounding is enforced upstream: every draft record rejects empty quotes+assumptions,
        // and refs always carries the source ref — Provenance.generated cannot reject this
        var provenance = Provenance.generated(refs, agentVersion, 0.7, assumptions);

        var derivesYaml = derivesFrom.stream()
                .map(up -> yq(up.value() + "@" + graph.get(up).map(Node::blobSha).orElse("unknown")))
                .collect(Collectors.joining(", ", "[", "]"));
        var quotesSection = quotes.isEmpty() ? "" : "\n## Source quotes\n\n" + quotes.stream()
                .map(q -> "> " + q).collect(Collectors.joining("\n")) + "\n";
        var clarSection = clarifications.isEmpty() ? "" : "\n## Clarifications\n\n" + clarifications.stream()
                .map(c -> c + "\n").collect(Collectors.joining("\n"));

        String repoPath = dir + "/" + id.value() + ".md";
        String content = String.format(Locale.ROOT, """
                ---
                id: %s
                type: %s
                title: %s
                status: PROPOSED
                derivesFrom: %s
                provenance:
                  sourceRefs: %s
                  generatedBy: %s
                  confidence: %.2f
                  assumptions: %s
                  humanApproved: false
                ---
                %s
                %s%s""", id.value(), pascal(type), yq(title), derivesYaml,
                provenance.sourceRefs().stream().map(GenerateIntentUseCase::yq)
                        .collect(Collectors.joining(", ", "[", "]")),
                yq(provenance.generatedBy()), provenance.confidence(),
                provenance.assumptions().stream().map(GenerateIntentUseCase::yq)
                        .collect(Collectors.joining(", ", "[", "]")),
                bodyText, quotesSection, clarSection);

        var sha = repo.write(repoPath, content);
        graph.upsert(new Node(id, type, title, repoPath, sha, NodeStatus.PROPOSED, 1,
                provenance, now, now));
        for (var up : derivesFrom)
            graph.link(Edge.current(EdgeType.DERIVES_FROM, id, up,
                    graph.get(up).map(Node::blobSha).orElse("unknown"), agentVersion, now));
        events.publish(new ArtifactProposed(id));
    }

    /** frontmatter type names are PascalCase (UseCase), enum is UPPER_SNAKE. */
    private static String pascal(NodeType type) {
        var parts = type.name().toLowerCase(Locale.ROOT).split("_");
        var sb = new StringBuilder();
        for (var p : parts) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        if (type == NodeType.NFR) return "NFR";
        return sb.toString();
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
