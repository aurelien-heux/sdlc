package dev.sdlc.agentbacklog.application;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.*;
import dev.sdlc.agentbacklog.domain.BacklogItemDraft;
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

/** FR-BL-1/2: APPROVED spec(+design) in, PROPOSED backlog items out via BacklogPort. */
public final class GenerateBacklogUseCase {
    static final String SYSTEM_PROMPT = """
            You are a backlog agent. Decompose the approved specification (and design) into an \
            epic/story/task breakdown with dependencies and T-shirt estimates. Every story should \
            name the Gherkin scenario it serves in "acceptanceHook" (null for epics). dependsOn \
            entries reference other item TITLES from this same answer. Respond with ONLY \
            {"items":[{"level":"epic|story|task","title","description","acceptanceHook", \
            "estimate":"XS|S|M|L|XL","dependsOn":[]}]}""";

    private final LanguageModelPort model;
    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort repo;
    private final BacklogPort backlog;
    private final EventPublisherPort events;
    private final RunTracePort trace;
    private final BacklogDraftParser parser;
    private final String agentVersion;
    private final Guardrails guardrails;

    public GenerateBacklogUseCase(LanguageModelPort model, TraceabilityGraphPort graph,
                                  ArtifactRepositoryPort repo, BacklogPort backlog,
                                  EventPublisherPort events, RunTracePort trace,
                                  BacklogDraftParser parser, String agentVersion,
                                  Guardrails guardrails) {
        this.model = model; this.graph = graph; this.repo = repo; this.backlog = backlog;
        this.events = events; this.trace = trace; this.parser = parser;
        this.agentVersion = agentVersion; this.guardrails = guardrails;
    }

    public List<ArtifactId> generate(ArtifactId specId, List<ArtifactId> designIds) {
        var spec = requireApproved(specId);
        var designs = designIds.stream().map(this::requireApproved).toList();

        String task = "# Specification\n" + repo.read(spec.repoPath()).orElse("(missing)")
                + designs.stream().map(d -> "\n\n# Design " + d.id() + "\n"
                        + repo.read(d.repoPath()).orElse("(missing)"))
                  .collect(Collectors.joining());
        var loop = new AgentLoop(model, new ToolRegistry(List.of()), trace, guardrails);
        var result = loop.run("backlog-" + UUID.randomUUID(), SYSTEM_PROMPT, task);

        var draft = parser.parse(result.finalText());
        var now = Instant.now();
        var upstream = new ArrayList<Node>();
        upstream.add(spec);
        upstream.addAll(designs);

        Map<String, ArtifactId> byTitle = new LinkedHashMap<>();
        var produced = new ArrayList<ArtifactId>();
        for (var item : draft.items()) {
            var id = nextId(switch (item.level()) {
                case "epic" -> "EPIC"; case "task" -> "TASK"; default -> "STORY";
            });
            writeItem(id, item, upstream, now);
            byTitle.put(item.title(), id);
            produced.add(id);
        }
        // dependency edges after all ids exist (titles validated by BacklogDraft)
        for (var item : draft.items())
            for (var dep : item.dependsOn())
                graph.link(Edge.current(EdgeType.DEPENDS_ON,
                        byTitle.get(item.title()), byTitle.get(dep),
                        graph.get(byTitle.get(dep)).map(Node::blobSha).orElse("unknown"),
                        agentVersion, now));
        return produced;
    }

    private Node requireApproved(ArtifactId id) {
        var node = graph.get(id).orElseThrow(() -> new IllegalStateException("unknown node: " + id));
        if (node.status() != NodeStatus.APPROVED)
            throw new IllegalStateException(id + " is " + node.status() + ", expected APPROVED");
        return node;
    }

    private void writeItem(ArtifactId id, BacklogItemDraft item, List<Node> upstream, Instant now) {
        var refs = upstream.stream().map(n -> n.id().value() + "@" + n.blobSha()).toList();
        var provenance = Provenance.generated(refs, agentVersion, 0.7,
                item.acceptanceHook() == null ? List.of("epic-level grouping, no single scenario")
                        : List.of());
        var derives = refs.stream().map(GenerateBacklogUseCase::yq)
                .collect(Collectors.joining(", ", "[", "]"));
        var hook = item.acceptanceHook() == null ? ""
                : "\nServes scenario: " + item.acceptanceHook() + "\n";
        String content = String.format(Locale.ROOT, """
                ---
                id: %s
                type: BacklogItem
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
                level: %s
                estimate: %s

                %s%s""", id.value(), yq(item.title()), derives,
                refs.stream().map(GenerateBacklogUseCase::yq).collect(Collectors.joining(", ", "[", "]")),
                yq(provenance.generatedBy()), provenance.confidence(),
                provenance.assumptions().stream().map(GenerateBacklogUseCase::yq)
                        .collect(Collectors.joining(", ", "[", "]")),
                item.level(), item.estimate(), item.description(), hook);
        backlog.upsert(id, item.level(), item.title(), content, item.estimate());
        var sha = FrontmatterParser.gitBlobSha(content);
        graph.upsert(new Node(id, NodeType.BACKLOG_ITEM, item.title(),
                "backlog/" + id.value() + ".md", sha, NodeStatus.PROPOSED, 1, provenance, now, now));
        for (var up : upstream)
            graph.link(Edge.current(EdgeType.DERIVES_FROM, id, up.id(), up.blobSha(),
                    agentVersion, now));
        events.publish(new ArtifactProposed(id));
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
