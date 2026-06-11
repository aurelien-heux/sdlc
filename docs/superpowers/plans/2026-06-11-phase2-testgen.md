# Phase 2 (Slice 1) — Test-Generation Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Approved specs become executable test artifacts: deterministic Cucumber `.feature` files + LLM step-skeletons as `TEST-xxxx` graph artifacts with `VERIFIES` edges, optionally placed into a configured target repo and run via an allow-listed command — plus the `listByType` port API retiring the id-probe debt.

**Architecture:** Spec: `docs/superpowers/specs/2026-06-11-phase2-testgen-design.md`. New `agents/agent-testgen` in the standard shape. The feature-file half is zero-LLM (§4.2); the runner command is config-fixed, never model-chosen (§4.5). TEST artifacts ride the existing `DERIVES_FROM` propagation spine.

**Tech Stack:** unchanged. **Conventions:** as Phase 1B's plan header — records+invariants, yq, pinned refs, PROPOSED-never-auto-approved, fence-tolerant parsers, TDD, commit per task.

---

## File structure

```
libs/traceability-graph/      MOD — listByType (port + in-memory + contract test); ArtifactFile.rawFrontmatter
libs/adapter-graph-postgres/  MOD — listByType SQL
libs/agent-core/              MOD — TestRunnerPort
libs/adapter-common/          MOD — ProcessTestRunner
agents/agent-intent/          MOD — ReviewFlagsUseCase migrates to listByType
agents/agent-design/          MOD — GenerateDesignUseCase migrates to listByType
agents/agent-testgen/         NEW — domain, extractor/renderer, parser, use cases, bootstrap
agents/agent-backlog/         MOD — E2E step 4c + ArchUnit classpath gains agent-testgen
README.md                     MOD
```

---

### Task 1: `listByType` port API + migrations (FR-PORT-2)

**Files:**
- Modify: `libs/traceability-graph/src/main/java/dev/sdlc/trace/TraceabilityGraphPort.java`, `InMemoryTraceabilityGraph.java`
- Modify: `libs/traceability-graph/src/testFixtures/java/dev/sdlc/trace/TraceabilityGraphContract.java` (new contract test)
- Modify: `libs/adapter-graph-postgres/src/main/java/dev/sdlc/adapter/graph/PostgresTraceabilityGraph.java`
- Modify: `agents/agent-intent/src/main/java/dev/sdlc/agentintent/application/ReviewFlagsUseCase.java`
- Modify: `agents/agent-design/src/main/java/dev/sdlc/agentdesign/application/GenerateDesignUseCase.java`

- [ ] **Step 1: Contract test first** (runs against BOTH adapters):

```java
    @Test
    void listByTypeFiltersAndEmptyMeansAll() {
        assertThat(graph.listByType(NodeType.SPECIFICATION))
                .extracting(n -> n.id().value()).containsExactly("SPEC-0007");
        assertThat(graph.listByType(NodeType.GOAL, NodeType.REQUIREMENT))
                .extracting(n -> n.id().value())
                .containsExactlyInAnyOrder("GOAL-0001", "REQ-0012");
        assertThat(graph.listByType()).hasSize(4); // empty varargs = all (downstreamOf convention)
    }
```

Run both `:libs:traceability-graph:test` and `:libs:adapter-graph-postgres:test` — FAIL (method missing).

- [ ] **Step 2: Implement**

Port javadoc + signature: `/** Nodes of the given types; empty = all (ordering unspecified). */ List<Node> listByType(NodeType... types);`

In-memory:
```java
    @Override
    public List<Node> listByType(NodeType... types) {
        var wanted = types.length == 0 ? EnumSet.allOf(NodeType.class) : EnumSet.of(types[0], types);
        return nodes.values().stream().filter(n -> wanted.contains(n.type())).toList();
    }
```
Postgres:
```java
    @Override
    public List<Node> listByType(NodeType... types) {
        var wanted = types.length == 0
                ? Arrays.stream(NodeType.values()).map(Enum::name).toList()
                : Arrays.stream(types).map(Enum::name).toList();
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("SELECT * FROM nodes WHERE type = ANY (?)")) {
            ps.setArray(1, c.createArrayOf("varchar", wanted.toArray()));
            return readNodes(ps);
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }
```

- [ ] **Step 3: Migrate the two id-probe call sites** (their module tests stay green — they assert edges/prompt content, not enumeration order):

`ReviewFlagsUseCase.allIntentNodes` becomes:
```java
    private List<Node> allIntentNodes() {
        return graph.listByType(NodeType.GOAL, NodeType.REQUIREMENT, NodeType.NFR, NodeType.USE_CASE);
    }
```
(delete the probe loop, the `INTENT_TYPES` EnumSet if now unused, and the 4-line contiguity caveat comment; the caller's `!DEPRECATED` filter stays).

`GenerateDesignUseCase.existingDesignSummaries` becomes:
```java
    private String existingDesignSummaries() {
        var out = new StringBuilder();
        for (var n : graph.listByType(NodeType.DESIGN_ELEMENT, NodeType.ADR, NodeType.API_CONTRACT))
            if (n.status() == NodeStatus.APPROVED)
                out.append("- ").append(n.id()).append(" (").append(n.type()).append("): ")
                   .append(n.title()).append('\n');
        return out.isEmpty() ? "(none yet)" : out.toString();
    }
```
(delete its probe + caveat comment).

- [ ] **Step 4:** `./gradlew build` green (Docker needed for the Postgres side). **Step 5: Commit:** `feat(traceability-graph): listByType port api; retire id-probe enumeration (FR-PORT-2)`

---

### Task 2: `rawFrontmatter` passthrough on ArtifactFile

**Files:**
- Modify: `libs/traceability-graph/src/main/java/dev/sdlc/trace/ArtifactFile.java`, `FrontmatterParser.java`
- Test: extend `FrontmatterParserTest.java`

- [ ] **Step 1: Failing test**

```java
    @Test
    void rawFrontmatterExposesPerTypeExtras() {
        var artifact = new FrontmatterParser().parse("""
                ---
                id: STORY-0001
                type: BacklogItem
                title: 'Regional rate lookup'
                status: PROPOSED
                level: story
                estimate: M
                acceptanceHook: 'FR VAT'
                derivesFrom: []
                provenance:
                  sourceRefs: [x]
                  generatedBy: h
                  confidence: 0.7
                  assumptions: []
                  humanApproved: false
                ---
                body
                """, "backlog/STORY-0001.md");
        assertThat(artifact.rawFrontmatter().get("acceptanceHook")).isEqualTo("FR VAT");
        assertThat(artifact.rawFrontmatter().get("level")).isEqualTo("story");
        assertThatThrownBy(() -> artifact.rawFrontmatter().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
```

- [ ] **Step 2: Implement** — `ArtifactFile` becomes `record ArtifactFile(Node node, Map<EdgeType, List<EdgeTarget>> edgeTargets, String body, Map<String, Object> rawFrontmatter)` (javadoc: read-only passthrough of the parsed YAML map for per-type extras; consumers must not treat it as schema). In `FrontmatterParser.toArtifact`, pass `java.util.Collections.unmodifiableMap(fm)`. Fix the parser's single construction site; no other code constructs ArtifactFile (verify with a grep).

- [ ] **Step 3:** module tests + `./gradlew build` green. **Step 4: Commit:** `feat(traceability-graph): read-only rawFrontmatter passthrough for per-type extras`

### Task 3: agent-testgen — module, domain, extractor, renderer

**Files:**
- Create: `agents/agent-testgen/build.gradle.kts` (copy agent-intent's shape; ADD `testImplementation(project(":agents:agent-spec"))` with comment `// test-only: round-trip property against the real spec generator's body format`); modify `settings.gradle.kts`
- Create in `agents/agent-testgen/src/main/java/dev/sdlc/agenttestgen/domain/`: `ScenarioSpec.java`, `FeatureDraft.java`, `StepSkeletonDraft.java`
- Create in `.../application/`: `AcceptanceCriteriaExtractor.java`, `FeatureRenderer.java`
- Tests: `.../domain/FeatureDraftTest.java`, `.../application/AcceptanceCriteriaExtractorTest.java`

- [ ] **Step 1: Failing domain test**

```java
package dev.sdlc.agenttestgen.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FeatureDraftTest {
    ScenarioSpec scenario = new ScenarioSpec("FR VAT",
            "Given a FR cart\nWhen checkout\nThen VAT added");

    @Test
    void scenarioRequiresNameAndGherkinSteps() {
        assertThatThrownBy(() -> new ScenarioSpec(" ", "Given x\nWhen y\nThen z"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> new ScenarioSpec("n", "just prose"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Given/When/Then");
    }

    @Test
    void featureNeedsAtLeastOneScenario() {
        assertThatThrownBy(() -> new FeatureDraft("Checkout tax", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scenario");
        assertThat(new FeatureDraft("Checkout tax", List.of(scenario)).scenarios()).hasSize(1);
    }

    @Test
    void skeletonLanguageIsAllowListed() {
        assertThatThrownBy(() -> new StepSkeletonDraft("cobol", "content"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("language");
        assertThat(new StepSkeletonDraft("java", "class Steps {}").language()).isEqualTo("java");
    }
}
```

- [ ] **Step 2: Implement the records**

```java
package dev.sdlc.agenttestgen.domain;

/** One Gherkin scenario lifted verbatim from a spec's acceptance criteria. */
public record ScenarioSpec(String name, String steps) {
    public ScenarioSpec {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("scenario name required");
        if (steps == null || !steps.contains("Given") || !steps.contains("When") || !steps.contains("Then"))
            throw new IllegalArgumentException("steps must contain Given/When/Then");
    }
}
```
```java
package dev.sdlc.agenttestgen.domain;

import java.util.List;

public record FeatureDraft(String title, List<ScenarioSpec> scenarios) {
    public FeatureDraft {
        scenarios = List.copyOf(scenarios);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("feature title required");
        if (scenarios.isEmpty())
            throw new IllegalArgumentException("feature needs at least one scenario");
    }
}
```
```java
package dev.sdlc.agenttestgen.domain;

import java.util.Set;

/** LLM-generated binding glue; a review draft, never promised-compiling code. */
public record StepSkeletonDraft(String language, String content) {
    private static final Set<String> LANGUAGES = Set.of("java", "kotlin", "typescript", "python");

    public StepSkeletonDraft {
        if (!LANGUAGES.contains(language))
            throw new IllegalArgumentException("language must be one of " + LANGUAGES + ": " + language);
        if (content == null || content.isBlank())
            throw new IllegalArgumentException("skeleton content required");
    }
}
```

- [ ] **Step 3: Failing extractor test** (incl. the round-trip property against the REAL generator format):

```java
package dev.sdlc.agenttestgen.application;

import dev.sdlc.agentspec.domain.AcceptanceCriterion;
import dev.sdlc.agentspec.domain.SpecificationDraft;
import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AcceptanceCriteriaExtractorTest {

    @Test
    void roundTripsTheRealSpecGeneratorBodyFormat() {
        var draft = new SpecificationDraft(ArtifactId.of("SPEC-0001"), "Checkout tax",
                List.of(ArtifactId.of("REQ-0001")),
                List.of(new AcceptanceCriterion("FR VAT",
                                "Given a FR cart\nWhen checkout\nThen VAT added"),
                        new AcceptanceCriterion("Rounding",
                                "Given a total of 10.005\nWhen tax is applied\nThen the total rounds per jurisdiction")),
                List.of("rounding follows jurisdiction rules"));

        var scenarios = new AcceptanceCriteriaExtractor().extract(draft.renderBody(), "SPEC-0001");

        assertThat(scenarios).hasSize(2);
        assertThat(scenarios.getFirst().name()).isEqualTo("FR VAT");
        assertThat(scenarios.getFirst().steps())
                .isEqualTo("Given a FR cart\nWhen checkout\nThen VAT added");
        assertThat(scenarios.get(1).name()).isEqualTo("Rounding");
    }

    @Test
    void missingCriteriaSectionFailsNamingTheSpec() {
        assertThatThrownBy(() -> new AcceptanceCriteriaExtractor().extract("## Notes\nno criteria", "SPEC-0009"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPEC-0009");
    }

    @Test
    void rendererProducesValidCucumberText() {
        var feature = new FeatureRenderer().render(new dev.sdlc.agenttestgen.domain.FeatureDraft(
                "Checkout tax", List.of(new dev.sdlc.agenttestgen.domain.ScenarioSpec(
                        "FR VAT", "Given a FR cart\nWhen checkout\nThen VAT added"))));
        assertThat(feature).startsWith("Feature: Checkout tax")
                .contains("  Scenario: FR VAT")
                .contains("    Given a FR cart")
                .contains("    Then VAT added");
    }
}
```
(clean the FQNs into imports)

- [ ] **Step 4: Implement extractor + renderer**

```java
package dev.sdlc.agenttestgen.application;

import dev.sdlc.agenttestgen.domain.ScenarioSpec;

import java.util.ArrayList;
import java.util.List;

/** Deterministically lifts scenarios from a spec body's "## Acceptance criteria" section
 *  (the exact format SpecificationDraft.renderBody writes — round-trip tested against it). */
public final class AcceptanceCriteriaExtractor {

    public List<ScenarioSpec> extract(String specBody, String specContext) {
        int start = specBody.indexOf("## Acceptance criteria");
        if (start < 0)
            throw new IllegalArgumentException("no acceptance criteria section in " + specContext);
        int nextSection = specBody.indexOf("\n## ", start + 1);
        String section = nextSection < 0 ? specBody.substring(start) : specBody.substring(start, nextSection);

        var scenarios = new ArrayList<ScenarioSpec>();
        String name = null;
        var steps = new StringBuilder();
        for (var line : section.lines().toList()) {
            if (line.startsWith("Scenario: ")) {
                flush(scenarios, name, steps);
                name = line.substring("Scenario: ".length()).strip();
                steps.setLength(0);
            } else if (name != null && !line.isBlank()) {
                if (!steps.isEmpty()) steps.append('\n');
                steps.append(line.strip());
            } else if (name != null && line.isBlank() && !steps.isEmpty()) {
                flush(scenarios, name, steps);
                name = null;
                steps.setLength(0);
            }
        }
        flush(scenarios, name, steps);
        if (scenarios.isEmpty())
            throw new IllegalArgumentException("acceptance criteria section has no scenarios in " + specContext);
        return scenarios;
    }

    private static void flush(List<ScenarioSpec> out, String name, StringBuilder steps) {
        if (name != null && !steps.isEmpty()) out.add(new ScenarioSpec(name, steps.toString()));
    }
}
```
```java
package dev.sdlc.agenttestgen.application;

import dev.sdlc.agenttestgen.domain.FeatureDraft;

import java.util.stream.Collectors;

/** FeatureDraft -> Cucumber feature text. Pure string assembly, zero LLM. */
public final class FeatureRenderer {

    public String render(FeatureDraft draft) {
        return "Feature: " + draft.title() + "\n\n" + draft.scenarios().stream()
                .map(s -> "  Scenario: " + s.name() + "\n" + s.steps().lines()
                        .map(l -> "    " + l).collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n\n")) + "\n";
    }
}
```

- [ ] **Step 5:** module + `./gradlew build` green. **Step 6: Commit:** `feat(agent-testgen): deterministic gherkin extraction and feature rendering (FR-TEST-1)`

### Task 4: GenerateTestsUseCase (FR-TEST-1/2/3/5)

**Files:**
- Create: `agents/agent-testgen/src/main/java/dev/sdlc/agenttestgen/application/StepSkeletonParser.java`, `GenerateTestsUseCase.java`
- Tests: `.../application/StepSkeletonParserTest.java`, `GenerateTestsUseCaseTest.java`

- [ ] **Step 1: Failing parser test** (house shape — fence strip, NPE/CCE→IAE):

```java
class StepSkeletonParserTest {
    @Test
    void parsesLanguageAndContent() {
        var draft = new StepSkeletonParser().parse("""
                {"language": "java", "content": "public class CheckoutSteps { /* ... */ }"}
                """);
        assertThat(draft.language()).isEqualTo("java");
        assertThat(draft.content()).contains("CheckoutSteps");
    }

    @Test
    void rejectsProseAndUnknownLanguage() {
        assertThatThrownBy(() -> new StepSkeletonParser().parse("here you go!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StepSkeletonParser().parse(
                "{\"language\": \"cobol\", \"content\": \"x\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("language");
    }
}
```
Implementation mirrors `BacklogDraftParser` (jakarta.json reader, fence strip, wrap to IAE) returning `new StepSkeletonDraft(root.getString("language"), root.getString("content"))`.

- [ ] **Step 2: Failing use-case test**

```java
package dev.sdlc.agenttestgen.application;

import dev.sdlc.agent.*;
import dev.sdlc.agent.port.*;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.domain.event.SdlcEvent;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GenerateTestsUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    static final String SPEC_SHA = "e".repeat(40);

    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();
    List<SdlcEvent> published = new ArrayList<>();
    Map<String, String> files = new HashMap<>();
    ArtifactRepositoryPort repo = new ArtifactRepositoryPort() {
        public String write(String path, String content) {
            files.put(path, content);
            return FrontmatterParser.gitBlobSha(content);
        }
        public Optional<String> read(String path) { return Optional.ofNullable(files.get(path)); }
    };
    RunTracePort noTrace = new RunTracePort() {
        public void step(String r, String k, String d, long i, long o, double c) {}
        public void finish(String r, String o) {}
    };

    static final String SPEC_FILE = """
            ---
            id: SPEC-0001
            type: Specification
            title: 'Checkout tax'
            status: APPROVED
            derivesFrom: ['REQ-0001@%s']
            provenance:
              sourceRefs: ['REQ-0001@%s']
              generatedBy: 'agent-spec@v1'
              confidence: 0.80
              assumptions: []
              humanApproved: true
              approvedBy: 'a.dupont'
            ---
            ## Acceptance criteria

            Scenario: FR VAT
            Given a FR cart
            When checkout
            Then VAT added
            """.formatted("a".repeat(40), "a".repeat(40));

    static final String STORY_FILE = """
            ---
            id: STORY-0001
            type: BacklogItem
            title: 'Rate lookup'
            status: APPROVED
            level: story
            estimate: M
            acceptanceHook: 'FR VAT'
            derivesFrom: ['SPEC-0001@%s']
            provenance:
              sourceRefs: ['SPEC-0001@%s']
              generatedBy: 'agent-backlog@v1'
              confidence: 0.7
              assumptions: []
              humanApproved: true
              approvedBy: 'a.dupont'
            ---
            level: story
            """.formatted(SPEC_SHA, SPEC_SHA);

    Node approved(String id, NodeType type, String path, String sha) {
        var prov = Provenance.generated(List.of("x"), "h", 0.8, List.of()).approve("a.dupont", T0);
        return new Node(ArtifactId.of(id), type, id, path, sha, NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    GenerateTestsUseCase useCase(LanguageModelPort model) {
        graph.upsert(approved("SPEC-0001", NodeType.SPECIFICATION, "specs/SPEC-0001.md", SPEC_SHA));
        graph.upsert(approved("STORY-0001", NodeType.BACKLOG_ITEM, "backlog/STORY-0001.md", "f".repeat(40)));
        files.put("specs/SPEC-0001.md", SPEC_FILE);
        files.put("backlog/STORY-0001.md", STORY_FILE);
        return new GenerateTestsUseCase(model, graph, repo, published::add, noTrace,
                new StepSkeletonParser(), "agent-testgen@v1", new Guardrails(5, 1.0), "java");
    }

    static final String SKELETON_JSON = """
            {"language": "java", "content": "public class CheckoutTaxSteps { /* @Given ... */ }"}
            """;

    @Test
    void producesFeatureAndSkeletonArtifactsWithVerifiesEdges() {
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(SKELETON_JSON));

        var ids = useCase(model).generate(ArtifactId.of("SPEC-0001"));

        assertThat(ids).hasSize(2);
        var featureId = ids.getFirst();
        var stepsId = ids.get(1);
        // feature artifact: deterministic content, no model involvement for it
        var featureFile = files.get(graph.get(featureId).orElseThrow().repoPath());
        assertThat(featureFile).contains("Feature: Checkout tax")
                .contains("Scenario: FR VAT").contains("derivesFrom: ['SPEC-0001@" + SPEC_SHA + "']");
        // skeleton artifact from the model
        var stepsFile = files.get(graph.get(stepsId).orElseThrow().repoPath());
        assertThat(stepsFile).contains("CheckoutTaxSteps");
        // VERIFIES edges: both artifacts -> spec; feature -> story via acceptanceHook match
        assertThat(graph.downstreamOf(ArtifactId.of("SPEC-0001"), EdgeType.VERIFIES))
                .extracting(Node::id).containsExactlyInAnyOrder(featureId, stepsId);
        assertThat(graph.downstreamOf(ArtifactId.of("STORY-0001"), EdgeType.VERIFIES))
                .extracting(Node::id).containsExactly(featureId);
        // propagation spine: both DERIVE_FROM the spec
        assertThat(graph.downstreamOf(ArtifactId.of("SPEC-0001"), EdgeType.DERIVES_FROM))
                .extracting(Node::id).contains(featureId, stepsId);
        // events + status
        assertThat(published).contains(new ArtifactProposed(featureId), new ArtifactProposed(stepsId));
        assertThat(graph.get(featureId).orElseThrow().status()).isEqualTo(NodeStatus.PROPOSED);
        // the model prompt contained the feature text and design context marker
        assertThat(model.requests.getFirst().messages().getFirst().content())
                .contains("Scenario: FR VAT");
    }

    @Test
    void refusesUnapprovedSpec() {
        var prov = Provenance.generated(List.of("x"), "h", 0.8, List.of());
        graph.upsert(new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "t",
                "specs/SPEC-0001.md", SPEC_SHA, NodeStatus.PROPOSED, 1, prov, T0, T0));
        var model = new FakeLanguageModel();

        assertThatThrownBy(() -> new GenerateTestsUseCase(model, graph, repo, published::add,
                noTrace, new StepSkeletonParser(), "agent-testgen@v1", new Guardrails(5, 1.0), "java")
                .generate(ArtifactId.of("SPEC-0001")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APPROVED");
        assertThat(model.requests).isEmpty();
    }
}
```

- [ ] **Step 3: Implement `GenerateTestsUseCase`** — follow the house use-case shape (read GenerateDesignUseCase first). Key mechanics:
- gate: APPROVED spec or `IllegalStateException`.
- `var scenarios = new AcceptanceCriteriaExtractor().extract(parser-of-spec-file-body, spec.id().value())` — get the spec BODY via `new FrontmatterParser().parse(repo.read(spec.repoPath()).orElseThrow(...), spec.repoPath()).body()`.
- feature: `new FeatureRenderer().render(new FeatureDraft(spec.title(), scenarios))`; artifact `tests/<id>.feature.md`, type `Test` (pascal of `NodeType.TEST`), body = the feature text, provenance sourceRefs `[SPEC@sha]`, assumption list empty; PROPOSED; `DERIVES_FROM` spec (pinned) + `VERIFIES` spec edges; `ArtifactProposed`.
- skeleton: LLM run (no tools) — SYSTEM_PROMPT: produce step-definition skeletons for the given Cucumber feature in the requested language, grounded in the design context; respond ONLY `{"language","content"}`. Task = feature text + `# Design context` from `listByType(DESIGN_ELEMENT, ADR, API_CONTRACT)` APPROVED summaries (reuse the one-liner format) + `# Language\n<configured>`. Parse → artifact `tests/<id>.steps.md`, body = fenced code block ```` ```<language>\n<content>\n``` ````, provenance assumption `"skeletons are review drafts, not compile-verified"`; same edges + event.
- VERIFIES→stories: for each `graph.listByType(NodeType.BACKLOG_ITEM)`, read its file via repo, `parse(...).rawFrontmatter().get("acceptanceHook")` — if it equals any scenario name → `VERIFIES` edge from the FEATURE artifact to the story (pinned to the story's blobSha). Stories whose file is unreadable are skipped silently (proposal-branch cases — comment it).
- `nextId("TEST")` per artifact (house pattern); `yq` for all frontmatter strings.

- [ ] **Step 4:** module + `./gradlew build` green. **Step 5: Commit:** `feat(agent-testgen): generate-tests use case with verifies edges (FR-TEST-1/2/3/5)`

---

### Task 5: TestRunnerPort + ProcessTestRunner + PlaceAndRunUseCase (FR-TEST-4)

**Files:**
- Create: `libs/agent-core/src/main/java/dev/sdlc/agent/port/TestRunnerPort.java` (exactly the spec §4.5 interface)
- Create: `libs/adapter-common/src/main/java/dev/sdlc/adapter/common/ProcessTestRunner.java`
- Create: `agents/agent-testgen/src/main/java/dev/sdlc/agenttestgen/application/PlaceAndRunUseCase.java`
- Tests: `libs/adapter-common/src/test/java/dev/sdlc/adapter/common/ProcessTestRunnerTest.java`, `agents/agent-testgen/src/test/java/dev/sdlc/agenttestgen/application/PlaceAndRunUseCaseTest.java`

- [ ] **Step 1: Failing runner test**

```java
class ProcessTestRunnerTest {
    @Test
    void zeroExitIsPassNonZeroIsFail(@TempDir Path repo) {
        assertThat(new ProcessTestRunner(repo, List.of("sh", "-c", "echo ok; exit 0")).run().passed()).isTrue();
        var failed = new ProcessTestRunner(repo, List.of("sh", "-c", "echo boom; exit 1")).run();
        assertThat(failed.passed()).isFalse();
        assertThat(failed.outputTail()).contains("boom");
    }
}
```
Implementation: ProcessBuilder(command) in `targetRepo`, redirectErrorStream, `waitFor(10, TimeUnit.MINUTES)` (timeout ⇒ destroyForcibly + failed result with "timed out"), outputTail = last 4000 chars. Javadoc: **command comes from configuration; the model never chooses or composes it (brief §9 guardrail).**

- [ ] **Step 2: Failing PlaceAndRun test**

```java
class PlaceAndRunUseCaseTest {
    // fixture: @TempDir target repo; @TempDir-backed FileArtifactRepository workspace holding the
    // two TEST artifacts produced exactly like Task 4's expected output (write the file contents
    // inline, frontmatter included); graph with the two TEST nodes (PROPOSED, correct repoPaths/shas).
    @Test
    void placesPayloadsRunsAndRecordsResultInFrontmatter() {
        // configured: featuresDir=src/test/resources/features, stepsDir=src/test/java/generated,
        // runner = ProcessTestRunner(target, List.of("sh", "-c", "exit 0"))
        // useCase.placeAndRun(featureId, stepsId)
        // assert: target files exist — features/<featureId>.feature contains "Feature:",
        //         generated/<stepsId>.java contains the skeleton content (code fence STRIPPED);
        //         both workspace artifacts' frontmatter now has lastRun: passed + lastRunAt:;
        //         graph nodes' blobSha match the rewritten files; second run with exit 1 flips
        //         lastRun to failed WITHOUT duplicating the lastRun lines.
    }
}
```
(Write the real fixture/assertions following the comment contract — the engineer builds it from Task 4's literal output format.)

- [ ] **Step 3: Implement `PlaceAndRunUseCase`**

Constructor: `(TraceabilityGraphPort graph, ArtifactRepositoryPort workspace, Path targetRepo, String featuresDir, String stepsDir, TestRunnerPort runner, Supplier<Instant> clock)`. `placeAndRun(featureId, stepsId)`:
1. Read both artifacts; `FrontmatterParser.parse(...).body()`; feature body goes verbatim to `<targetRepo>/<featuresDir>/<featureId>.feature`; skeleton body has its code fence stripped (first/last fence lines) and goes to `<stepsDir>/<stepsId>.<ext>` (ext by language line of the fence: java/kt/ts/py).
2. `runner.run()`.
3. For each artifact: remove existing `lastRun:`/`lastRunAt:` lines, then insert after the `status:` line:
```java
        var cleaned = content.replaceAll("(?m)^lastRun(At)?: .*\\n", "");
        var stamped = cleaned.replaceFirst("(?m)^(status: .*)$",
                "$1\nlastRun: " + (result.passed() ? "passed" : "failed")
                + "\nlastRunAt: '" + clock.get() + "'");
```
write back, `graph.upsert(node.withContentChange(newSha, now))` — metadata change, no propagation (same rule as approvals; comment it).
4. Return the `RunResult`.

- [ ] **Step 4:** both modules + full build green. **Step 5: Commit:** `feat(agent-testgen): allow-listed test execution with lastRun metadata (FR-TEST-4)`

---

### Task 6: Bootstrap, E2E step 4c, ArchUnit classpath, README, final review

**Files:**
- Create: `agents/agent-testgen/src/main/java/dev/sdlc/agenttestgen/bootstrap/AgentTestgenApplication.java` + `application.yaml` + `application-openai.yaml` (copy a sibling's; build file gains the spring/boot deps mirroring agent-design's)
- Modify: `agents/agent-backlog/build.gradle.kts` — `testImplementation(project(":agents:agent-testgen"))` (serves BOTH the E2E step 4c AND ArchUnit coverage of the new agent — extend the existing loop-test-only comment)
- Modify: `agents/agent-backlog/src/test/java/dev/sdlc/agentbacklog/bootstrap/UpstreamLoopEndToEndTest.java`
- Modify: `README.md`

- [ ] **Step 1: Bootstrap** — mirror `AgentDesignApplication` (args = SPEC ids; standard profile blocks). Additional `target-repo` profile: when active, read `SDLC_TARGET_REPO` (required), `SDLC_TEST_CMD` (default `./gradlew test` — split on spaces), `SDLC_FEATURES_DIR`/`SDLC_STEPS_DIR` (defaults per spec §4.5); after the approval loop, run `PlaceAndRunUseCase` per generated pair and print what was written where BEFORE running (spec §6 risk note), then the pass/fail + output tail.

- [ ] **Step 2: E2E step 4c** — after the backlog step: scripted skeleton JSON model → `GenerateTestsUseCase` on the approved spec → approve both TEST artifacts → assert VERIFIES edges (feature→story via the E2E's real acceptanceHook value — check what the backlog step's scripted JSON uses and match scenario naming so the hook resolves; adjust the intent/spec scripted JSONs if needed so the chain hook-matches end to end). Step-5 propagation set gains the two TEST ids; step-6 restart parity inherits (rebuild includes tests/ files). ArchUnit: verify `HexagonalArchitectureTest` now analyzes `dev.sdlc.agenttestgen` (planted-violation step: a domain→application reference in agent-testgen, expect `agentDomainsAreInnermost FAILED`, remove).

- [ ] **Step 3: README** — upstream loop gains step 5 (`:agents:agent-testgen:bootRun --args="SPEC-0001"`), the `target-repo` profile row in the Profiles table, env vars documented.

- [ ] **Step 4:** `./gradlew build --rerun-tasks` fresh green — report exact totals. **Step 5: Commit:** `feat(agent-testgen): bootstrap, closed-loop E2E step 4c, docs (Phase 2 slice 1 DoD)`

---

## DoD checklist (spec §2)

- [ ] FR-TEST-1 deterministic features — T3/T4
- [ ] FR-TEST-2 VERIFIES edges (spec + acceptanceHook stories) — T4
- [ ] FR-TEST-3 LLM skeletons w/ design grounding + review-draft assumption — T4
- [ ] FR-TEST-4 allow-listed run + lastRun metadata — T5
- [ ] FR-TEST-5 propagation reaches TESTs (E2E step-5 set) — T6
- [ ] FR-PORT-2 listByType + id-probe retirement — T1
- [ ] rawFrontmatter passthrough — T2

## Deferred

Full sandboxed execution loop; CI-triggered test-gen; CODE nodes/coverage; SPEC-0000 revision absorbing FR-TEST-*; non-Java skeleton compile verification.

