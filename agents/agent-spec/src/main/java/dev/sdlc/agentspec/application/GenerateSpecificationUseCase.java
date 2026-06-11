package dev.sdlc.agentspec.application;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agentspec.domain.SpecificationDraft;
import dev.sdlc.agentspec.domain.TestabilityReport;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.EventPublisherPort;
import dev.sdlc.trace.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** UC-0002: approved requirements in, PROPOSED specification out. Never auto-approves. */
public final class GenerateSpecificationUseCase {
    static final String SYSTEM_PROMPT = """
            You are a specification agent. From the requirement and use-case documents \
            provided, produce ONE testable specification. Respond with ONLY a JSON object: \
            {"title", "criteria":[{"scenario","steps"}], "constraints":[], "assumptions":[], \
            "untestable":[{"id","reason"}]}. Steps are Gherkin (Given/When/Then). \
            Ground every criterion in the provided documents; put anything you had to assume \
            into "assumptions"; never invent requirements.""";

    private final AgentLoop loop;
    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort repo;
    private final EventPublisherPort events;
    private final SpecDraftParser parser;
    private final String agentVersion;

    public GenerateSpecificationUseCase(AgentLoop loop, TraceabilityGraphPort graph,
                                        ArtifactRepositoryPort repo, EventPublisherPort events,
                                        SpecDraftParser parser, String agentVersion) {
        this.loop = loop; this.graph = graph; this.repo = repo;
        this.events = events; this.parser = parser; this.agentVersion = agentVersion;
    }

    public ArtifactId generate(List<ArtifactId> requirementIds) {
        var sources = requirementIds.stream()
                .map(id -> graph.get(id).orElseThrow(() ->
                        new IllegalStateException("unknown requirement: " + id)))
                .toList();
        for (var node : sources)
            if (node.status() != NodeStatus.APPROVED)
                throw new IllegalStateException(node.id() + " is " + node.status() + ", expected APPROVED");

        String task = sources.stream()
                .map(n -> "# " + n.id().value() + " — " + n.title() + "\n"
                        + repo.read(n.repoPath()).orElse("(file missing)"))
                .collect(Collectors.joining("\n\n"));

        var result = loop.run("specgen-" + UUID.randomUUID(), SYSTEM_PROMPT, task);

        var specId = nextSpecId();
        var parsed = parser.parse(result.finalText(), specId, requirementIds);
        var draft = parsed.draft();

        var provenance = Provenance.generated(
                sources.stream().map(n -> n.id().value() + "@" + n.blobSha()).toList(),
                agentVersion, 0.8, parsed.assumptions());

        String repoPath = "specs/" + specId.value() + ".md";
        String content = renderFile(draft, provenance, parsed.report(), sources);
        String blobSha = repo.write(repoPath, content);

        var now = Instant.now();
        var node = new Node(specId, NodeType.SPECIFICATION, draft.title(), repoPath,
                blobSha, NodeStatus.PROPOSED, 1, provenance, now, now);
        graph.upsert(node);
        for (var src : sources)
            graph.link(Edge.current(EdgeType.DERIVES_FROM, specId, src.id(),
                    src.blobSha(), agentVersion, now));
        events.publish(new ArtifactProposed(specId));
        return specId;
    }

    private ArtifactId nextSpecId() {
        // Phase 0: derive next free SPEC id from the graph; replace with a sequence later
        for (int i = 1; i < 10_000; i++) {
            var candidate = ArtifactId.of(String.format("SPEC-%04d", i));
            if (graph.get(candidate).isEmpty()) return candidate;
        }
        throw new IllegalStateException("SPEC id space exhausted");
    }

    /** YAML single-quoted scalar: the only escape is '' ; newlines are flattened. */
    private static String yq(String s) {
        return "'" + s.replaceAll("[\\r\\n]+", " ").replace("'", "''") + "'";
    }

    private String renderFile(SpecificationDraft draft, Provenance prov, TestabilityReport report,
                              List<Node> sources) {
        // pins == draft.derivesFrom() by construction; sources carry the shas
        String derives = sources.stream()
                .map(src -> yq(src.id().value() + "@" + src.blobSha()))
                .collect(Collectors.joining(", ", "[", "]"));
        String refs = prov.sourceRefs().stream().map(GenerateSpecificationUseCase::yq)
                .collect(Collectors.joining(", ", "[", "]"));
        String assumptions = prov.assumptions().stream().map(GenerateSpecificationUseCase::yq)
                .collect(Collectors.joining(", ", "[", "]"));
        String flags = report.clean() ? "" : "\n## Testability flags\n\n" + report.flags().stream()
                .map(f -> "- " + f.requirement().value() + ": " + f.reason())
                .collect(Collectors.joining("\n")) + "\n";
        return String.format(java.util.Locale.ROOT, """
                ---
                id: %s
                type: Specification
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
                %s%s""", draft.id().value(), yq(draft.title()), derives, refs,
                yq(prov.generatedBy()), prov.confidence(), assumptions, draft.renderBody(), flags);
    }
}
