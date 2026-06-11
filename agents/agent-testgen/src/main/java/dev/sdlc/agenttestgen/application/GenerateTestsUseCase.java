package dev.sdlc.agenttestgen.application;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.*;
import dev.sdlc.agenttestgen.domain.FeatureDraft;
import dev.sdlc.agenttestgen.domain.ScenarioSpec;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.trace.Edge;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.TraceabilityGraphPort;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** FR-TEST-1/2/3/5: APPROVED spec in, PROPOSED feature + step-skeleton TEST artifacts out. */
public final class GenerateTestsUseCase {
    static final String SKELETON_ASSUMPTION = "skeletons are review drafts, not compile-verified";
    static final String SYSTEM_PROMPT = """
            You are a test-generation agent. Produce step-definition skeletons for the given \
            Cucumber feature in the requested language, grounded in the design context provided. \
            Respond with ONLY a JSON object: {"language", "content"}""";

    private final LanguageModelPort model;
    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort repo;
    private final EventPublisherPort events;
    private final RunTracePort trace;
    private final StepSkeletonParser parser;
    private final String agentVersion;
    private final Guardrails guardrails;
    private final String language;

    public GenerateTestsUseCase(LanguageModelPort model, TraceabilityGraphPort graph,
                                ArtifactRepositoryPort repo, EventPublisherPort events,
                                RunTracePort trace, StepSkeletonParser parser,
                                String agentVersion, Guardrails guardrails, String language) {
        this.model = model; this.graph = graph; this.repo = repo; this.events = events;
        this.trace = trace; this.parser = parser; this.agentVersion = agentVersion;
        this.guardrails = guardrails; this.language = language;
    }

    public List<ArtifactId> generate(ArtifactId specId) {
        var spec = graph.get(specId).orElseThrow(() ->
                new IllegalStateException("unknown specification: " + specId));
        if (spec.status() != NodeStatus.APPROVED)
            throw new IllegalStateException(specId + " is " + spec.status() + ", expected APPROVED");

        var specFile = new FrontmatterParser().parse(
                repo.read(spec.repoPath()).orElseThrow(() ->
                        new IllegalStateException("spec file missing: " + spec.repoPath())),
                spec.repoPath());
        var scenarios = new AcceptanceCriteriaExtractor().extract(specFile.body(), spec.id().value());
        var now = Instant.now();

        // feature half: pure transcription of approved criteria, zero LLM (spec §4.2);
        // title from the FILE's frontmatter — canonical source of truth (brief §12.1)
        var specTitle = specFile.node().title();
        var featureText = new FeatureRenderer().render(new FeatureDraft(specTitle, scenarios));
        var featureId = write(nextId("TEST"), specTitle + " — feature", "feature",
                featureText, 0.90, List.of(), spec, now);
        linkStoriesVerifiedBy(featureId, scenarios, now);

        // skeleton half: LLM-generated binding glue, grounded in design summaries (FR-TEST-3)
        String task = featureText + "\n\n# Design context\n" + designContext()
                + "\n# Language\n" + language;
        var loop = new AgentLoop(model, new ToolRegistry(List.of()), trace, guardrails);
        var draft = parser.parse(loop.run("testgen-" + UUID.randomUUID(), SYSTEM_PROMPT, task)
                .finalText());
        var skeletonBody = "```" + draft.language() + "\n" + draft.content() + "\n```";
        var stepsId = write(nextId("TEST"), specTitle + " — step skeletons", "steps",
                skeletonBody, 0.70, List.of(SKELETON_ASSUMPTION), spec, now);

        return List.of(featureId, stepsId);
    }

    private String designContext() {
        var out = new StringBuilder();
        for (var n : graph.listByType(NodeType.DESIGN_ELEMENT, NodeType.ADR, NodeType.API_CONTRACT))
            if (n.status() == NodeStatus.APPROVED)
                out.append("- ").append(n.id()).append(" (").append(n.type()).append("): ")
                   .append(n.title()).append('\n');
        return out.isEmpty() ? "(none yet)" : out.toString();
    }

    /** FR-TEST-2: feature VERIFIES every story whose acceptanceHook names one of its scenarios. */
    private void linkStoriesVerifiedBy(ArtifactId featureId, List<ScenarioSpec> scenarios,
                                       Instant now) {
        Set<String> scenarioNames = scenarios.stream().map(ScenarioSpec::name)
                .collect(Collectors.toSet());
        for (var story : graph.listByType(NodeType.BACKLOG_ITEM)) {
            // proposal-branch cases: story node may exist while its file isn't readable from
            // this workspace — skip silently rather than fail the whole generation
            var content = repo.read(story.repoPath());
            if (content.isEmpty()) continue;
            // the key is ABSENT for epics, so the lookup is null for them — null-safe compare
            Object hook = new FrontmatterParser().parse(content.get(), story.repoPath())
                    .rawFrontmatter().get("acceptanceHook");
            if (hook != null && scenarioNames.contains(hook.toString()))
                graph.link(Edge.current(EdgeType.VERIFIES, featureId, story.id(),
                        story.blobSha(), agentVersion, now));
        }
    }

    private ArtifactId write(ArtifactId id, String title, String kind, String bodyText,
                             double confidence, List<String> assumptions, Node spec, Instant now) {
        var provenance = Provenance.generated(
                List.of(spec.id().value() + "@" + spec.blobSha()),
                agentVersion, confidence, assumptions);
        String repoPath = "tests/" + id.value() + "." + kind + ".md";
        String content = String.format(Locale.ROOT, """
                ---
                id: %s
                type: Test
                title: %s
                status: PROPOSED
                derivesFrom: [%s]
                provenance:
                  sourceRefs: [%s]
                  generatedBy: %s
                  confidence: %.2f
                  assumptions: %s
                  humanApproved: false
                ---
                %s
                """, id.value(), yq(title),
                yq(spec.id().value() + "@" + spec.blobSha()),
                yq(spec.id().value() + "@" + spec.blobSha()),
                yq(provenance.generatedBy()), provenance.confidence(),
                assumptions.stream().map(GenerateTestsUseCase::yq)
                        .collect(Collectors.joining(", ", "[", "]")),
                bodyText);
        var sha = repo.write(repoPath, content);
        graph.upsert(new Node(id, NodeType.TEST, title, repoPath, sha, NodeStatus.PROPOSED, 1,
                provenance, now, now));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, id, spec.id(), spec.blobSha(),
                agentVersion, now));
        graph.link(Edge.current(EdgeType.VERIFIES, id, spec.id(), spec.blobSha(),
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
