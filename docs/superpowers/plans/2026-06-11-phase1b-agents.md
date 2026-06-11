# Phase 1B — Intent, Design, Backlog Agents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the upstream loop: inbox files become Goal/Requirement/UseCase artifacts (Intent agent, with `ask_human` clarification as a tool), approved specs become Design artifacts (ADRs with mandatory alternatives), spec+design become a file-based backlog — all human-gated, all in one traceability graph, with the full chain proven by a closed-loop E2E including restart-safe staleness.

**Architecture:** Three new agent modules cloning agent-spec's hexagonal shape (`domain`/`application`/`bootstrap` packages; adapters come from the shared `libs/adapter-*`). Spec: `docs/superpowers/specs/2026-06-10-phase1-design.md` §9–§12. Two carried items from the 1A final review are tasks 1–2.

**Tech Stack:** unchanged (Java 25, Gradle 9.5, Spring AI 2.0.0-M4 behind adapter-llm-spring, jakarta.json for model-output parsing, fakes via agent-core testFixtures).

**Conventions (established; follow them):** records + compact-constructor invariants; `yq()` single-quote YAML escaping and `String.format(Locale.ROOT, …)` in writers; pinned refs `ID@sha` in `derivesFrom`; provenance `sourceRefs` keep ORIGINAL grounding shas; artifacts written `PROPOSED`, never auto-approved; parser classes convert model JSON → domain records and throw IAE with context; TDD with fail-first evidence; one commit per task.

---

## File structure

```
agents/agent-intent/                 NEW — dev.sdlc.agentintent.{domain,application,bootstrap}
agents/agent-design/                 NEW — dev.sdlc.agentdesign.{domain,application,bootstrap}
agents/agent-backlog/                NEW — dev.sdlc.agentbacklog.{domain,application,bootstrap}
libs/agent-core/                     MOD — BacklogPort added (port only)
libs/adapter-git/                    MOD — proposal-branch scan support (branches/changedFiles)
agents/agent-spec/                   MOD — bootstrap wires rebuild events to the bus;
                                           E2E superseded by the closed-loop E2E (kept, extended)
workspace/inbox/payment-notes.md     NEW — demo seed
.github/workflows/ci.yml             unchanged (build covers new modules automatically)
README.md                            MOD — upstream-loop usage
```

ID prefixes (all satisfy `[A-Z]{2,}-\d{4}`): `GOAL- REQ- NFR- UC-` (intent), `DES- ADR- API-` (design), `EPIC- STORY- TASK-` (backlog).

---

### Task 1: Wire startup-rebuild staleness events to the bus (1A carry-over)

**Files:**
- Modify: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/bootstrap/AgentSpecApplication.java`
- Test: extend `agents/agent-spec/src/test/java/dev/sdlc/agentspec/bootstrap/EndToEndTest.java`

- [ ] **Step 1: Write the failing assertion**

In `EndToEndTest`, the step-4 rebuild block already collects `rebuildEvents`. Add a bus-level twin right after it, proving the BOOTSTRAP wiring shape (bus first, then rebuild publishing into it):

```java
        // --- 4b. the bootstrap wiring shape: rebuild publishes staleness INTO the bus ---
        var busAfterRestart = new InProcessEventPublisher();
        var graphAfterRestart = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser())
                .rebuild(workspace, graphAfterRestart, busAfterRestart::publish);
        assertThat(busAfterRestart.log())
                .anyMatch(e -> e instanceof RevalidationRequested r && r.subject().equals(specId));
```

This passes already at the library level (3-arg rebuild exists) — the failing part is the bootstrap: `AgentSpecApplication` still calls the 2-arg rebuild. There is no bootstrap-level test harness (the CommandLineRunner needs a ChatModel), so the enforcement is structural: change the bootstrap and let the E2E document the shape.

- [ ] **Step 2: Fix the bootstrap ordering**

In `AgentSpecApplication.demo(...)`: construct `bus` (and its `ArtifactChanged` subscription) BEFORE the rebuild, then call:
```java
            new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, graph, bus::publish);
```
(keep everything else identical; the bus variable just moves above the rebuild).

- [ ] **Step 3: Run** `./gradlew :agents:agent-spec:test` then `./gradlew build` — green.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "fix(agent-spec): startup rebuild publishes staleness events to the bus"
```

---

### Task 2: Proposal-branch visibility after restart (1A carry-over)

**Files:**
- Modify: `libs/agent-core/src/main/java/dev/sdlc/agent/port/GitPort.java`
- Modify: `libs/adapter-git/src/main/java/dev/sdlc/adapter/git/ProcessGitAdapter.java`
- Create: `libs/adapter-git/src/main/java/dev/sdlc/adapter/git/ProposalScanner.java`
- Test: extend `libs/adapter-git/src/test/java/dev/sdlc/adapter/git/GitArtifactRepositoryTest.java`

**Decision (resolves the 1A review question):** in git-approval mode, PROPOSED artifacts live only on `proposal/*` branches; a restart must re-project them. `ProposalScanner` lists proposal branches, reads their changed artifact files, and upserts the parsed nodes into the graph after the main rebuild.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void proposalScannerRestoresPendingProposalsAfterRestart(@TempDir Path dir) {
        var repo = repo(dir);
        String spec = """
                ---
                id: SPEC-0001
                type: Specification
                title: 'Pending spec'
                status: PROPOSED
                derivesFrom: ['REQ-0012@%s']
                provenance:
                  sourceRefs: ['REQ-0012@%s']
                  generatedBy: 'agent-spec@v1'
                  confidence: 0.80
                  assumptions: []
                  humanApproved: false
                ---
                body
                """.formatted("a".repeat(40), "a".repeat(40));
        repo.write("specs/SPEC-0001.md", spec); // lands on proposal/SPEC-0001, NOT main

        // simulate restart: fresh graph, main-tree rebuild finds nothing
        var graph = new dev.sdlc.trace.InMemoryTraceabilityGraph();
        new dev.sdlc.trace.ProjectionBuilder(new dev.sdlc.trace.FrontmatterParser()).rebuild(dir, graph);
        assertThat(graph.get(dev.sdlc.domain.ArtifactId.of("SPEC-0001"))).isEmpty();

        new ProposalScanner(repo.git(), new dev.sdlc.trace.FrontmatterParser()).scanInto(graph);

        var node = graph.get(dev.sdlc.domain.ArtifactId.of("SPEC-0001")).orElseThrow();
        assertThat(node.status()).isEqualTo(dev.sdlc.domain.NodeStatus.PROPOSED);
    }
```
(adapter-git's build file needs `implementation(project(":libs:traceability-graph"))` — it already has it.)

- [ ] **Step 2: Run to verify failure** — `./gradlew :libs:adapter-git:test` → FAIL (ProposalScanner missing; GitPort lacks listing).

- [ ] **Step 3: Implement**

`GitPort` gains two methods:
```java
    /** Branch names starting with the prefix, e.g. branches("proposal/"). */
    java.util.List<String> branches(String prefix);
    /** Paths changed on the branch relative to main (git diff main...branch --name-only). */
    java.util.List<String> changedFiles(String branch);
```
`ProcessGitAdapter` implementations:
```java
    @Override public List<String> branches(String prefix) {
        var out = run("branch", "--list", prefix + "*", "--format=%(refname:short)");
        return out.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Override public List<String> changedFiles(String branch) {
        var out = run("diff", "--name-only", "main..." + branch);
        return out.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
```
Task 14 of plan 1A added a `RecordingGit` fake in `ApproveArtifactUseCaseTest` — it must gain the two methods (return `List.of()`).

`ProposalScanner.java`:
```java
package dev.sdlc.adapter.git;

import dev.sdlc.agent.port.GitPort;
import dev.sdlc.trace.FrontmatterParser;
import dev.sdlc.trace.TraceabilityGraphPort;

/** Re-projects PROPOSED artifacts that exist only on proposal/* branches (restart recovery). */
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
```
(Edges for pending proposals are intentionally NOT recreated — approval re-runs persistence and the next rebuild after merge links them; a pending proposal needs only its node for `review()` to find. Note this in the class javadoc if you prefer.)

Bootstrap: in `AgentSpecApplication`, inside the git-approval branch, after the rebuild:
```java
                new ProposalScanner(gitPort, new FrontmatterParser()).scanInto(graph);
```

- [ ] **Step 4: Run** `./gradlew :libs:adapter-git:test && ./gradlew build` — green.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(adapter-git): restore pending proposals into the projection after restart"
```

### Task 3: agent-intent — module + domain model

**Files:**
- Create: `agents/agent-intent/build.gradle.kts`; modify `settings.gradle.kts` (include)
- Create in `agents/agent-intent/src/main/java/dev/sdlc/agentintent/domain/`: `IntentDraft.java`, `GoalDraft.java`, `RequirementDraft.java`, `UseCaseDraft.java`
- Test: `agents/agent-intent/src/test/java/dev/sdlc/agentintent/domain/IntentDraftTest.java`

- [ ] **Step 1: Module**

`agents/agent-intent/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
dependencies {
    implementation(project(":libs:domain-shared"))
    implementation(project(":libs:agent-core"))
    implementation(project(":libs:traceability-graph"))
    implementation(project(":libs:adapter-common"))
    implementation(libs.jakarta.json)
    runtimeOnly(libs.parsson)
    testImplementation(testFixtures(project(":libs:agent-core")))
}
```
`settings.gradle.kts`: add `include("agents:agent-intent")`.
(No Spring here yet — bootstrap arrives in Task 10 with its own deps.)

- [ ] **Step 2: Write the failing test**

```java
package dev.sdlc.agentintent.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class IntentDraftTest {
    GoalDraft goal = new GoalDraft("Faster checkout", "Reduce checkout time below 5s",
            List.of("we lose carts at checkout"), List.of());
    RequirementDraft req = new RequirementDraft("Apply regional tax", "Checkout applies shipping-region tax",
            "functional", "MUST", "Faster checkout",
            List.of("tax must match the shipping region"), List.of());
    UseCaseDraft uc = new UseCaseDraft("Customer checks out", "Customer",
            List.of("add items", "checkout", "pay"), List.of("payment fails -> retry"),
            "Apply regional tax", List.of("the flow described in the notes"), List.of());

    @Test
    void draftsRequireGroundingQuotesOrAssumptions() {
        // NFR-GROUND at the draft level: every item cites verbatim source quotes or declares assumptions
        assertThatThrownBy(() -> new GoalDraft("g", "d", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grounded");
        // assumptions alone suffice
        assertThat(new GoalDraft("g", "d", List.of(), List.of("assumed")).assumptions()).hasSize(1);
    }

    @Test
    void requirementKindAndMoscowAreConstrained() {
        assertThatThrownBy(() -> new RequirementDraft("t", "d", "weird", "MUST", null,
                List.of("q"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kind");
        assertThatThrownBy(() -> new RequirementDraft("t", "d", "functional", "MAYBE", null,
                List.of("q"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("moscow");
    }

    @Test
    void intentDraftRequiresAtLeastOneItem() {
        assertThatThrownBy(() -> new IntentDraft(List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
        var draft = new IntentDraft(List.of(goal), List.of(req), List.of(uc));
        assertThat(draft.goals()).hasSize(1);
    }

    @Test
    void useCaseRequiresActorAndMainFlow() {
        assertThatThrownBy(() -> new UseCaseDraft("t", " ", List.of("s"), List.of(), null,
                List.of("q"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actor");
        assertThatThrownBy(() -> new UseCaseDraft("t", "Actor", List.of(), List.of(), null,
                List.of("q"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mainFlow");
    }
}
```

- [ ] **Step 3: Run** `./gradlew :agents:agent-intent:test` — FAIL (classes missing).

- [ ] **Step 4: Implement**

Shared grounding check appears in each record (keep it local — three small records beat a hierarchy, YAGNI):

`GoalDraft.java`:
```java
package dev.sdlc.agentintent.domain;

import java.util.List;

/** A goal the model extracted; grounded in verbatim quotes from the source or explicit assumptions. */
public record GoalDraft(String title, String description,
                        List<String> sourceQuotes, List<String> assumptions) {
    public GoalDraft {
        sourceQuotes = List.copyOf(sourceQuotes);
        assumptions = List.copyOf(assumptions);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("goal title required");
        if (sourceQuotes.isEmpty() && assumptions.isEmpty())
            throw new IllegalArgumentException("draft must be grounded: sourceQuotes or assumptions");
    }
}
```

`RequirementDraft.java`:
```java
package dev.sdlc.agentintent.domain;

import java.util.List;
import java.util.Set;

/** kind: functional|nfr; moscow: MUST|SHOULD|COULD|WONT; goalTitle links to a GoalDraft (nullable). */
public record RequirementDraft(String title, String description, String kind, String moscow,
                               String goalTitle, List<String> sourceQuotes, List<String> assumptions) {
    private static final Set<String> KINDS = Set.of("functional", "nfr");
    private static final Set<String> MOSCOW = Set.of("MUST", "SHOULD", "COULD", "WONT");

    public RequirementDraft {
        sourceQuotes = List.copyOf(sourceQuotes);
        assumptions = List.copyOf(assumptions);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("requirement title required");
        if (!KINDS.contains(kind))
            throw new IllegalArgumentException("kind must be functional|nfr: " + kind);
        if (!MOSCOW.contains(moscow))
            throw new IllegalArgumentException("moscow must be MUST|SHOULD|COULD|WONT: " + moscow);
        if (sourceQuotes.isEmpty() && assumptions.isEmpty())
            throw new IllegalArgumentException("draft must be grounded: sourceQuotes or assumptions");
    }
}
```

`UseCaseDraft.java`:
```java
package dev.sdlc.agentintent.domain;

import java.util.List;

/** requirementTitle links to a RequirementDraft (nullable -> derives from a goal instead). */
public record UseCaseDraft(String title, String actor, List<String> mainFlow,
                           List<String> altFlows, String requirementTitle,
                           List<String> sourceQuotes, List<String> assumptions) {
    public UseCaseDraft {
        mainFlow = List.copyOf(mainFlow);
        altFlows = List.copyOf(altFlows);
        sourceQuotes = List.copyOf(sourceQuotes);
        assumptions = List.copyOf(assumptions);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("use case title required");
        if (actor == null || actor.isBlank())
            throw new IllegalArgumentException("use case actor required");
        if (mainFlow.isEmpty())
            throw new IllegalArgumentException("use case mainFlow required");
        if (sourceQuotes.isEmpty() && assumptions.isEmpty())
            throw new IllegalArgumentException("draft must be grounded: sourceQuotes or assumptions");
    }
}
```

`IntentDraft.java`:
```java
package dev.sdlc.agentintent.domain;

import java.util.List;

/** Everything the Intent agent extracted from one source document. */
public record IntentDraft(List<GoalDraft> goals, List<RequirementDraft> requirements,
                          List<UseCaseDraft> useCases) {
    public IntentDraft {
        goals = List.copyOf(goals);
        requirements = List.copyOf(requirements);
        useCases = List.copyOf(useCases);
        if (goals.isEmpty() && requirements.isEmpty() && useCases.isEmpty())
            throw new IllegalArgumentException("intent draft needs at least one item");
    }
}
```

- [ ] **Step 5: Run** — PASS — then `./gradlew build` (ArchUnit picks up the new module automatically via `dev.sdlc.agent*.domain..`). Commit:

```bash
git add -A && git commit -m "feat(agent-intent): grounded intent draft domain model"
```

---

### Task 4: agent-intent — IntentDraftParser

**Files:**
- Create: `agents/agent-intent/src/main/java/dev/sdlc/agentintent/application/IntentDraftParser.java`
- Test: `agents/agent-intent/src/test/java/dev/sdlc/agentintent/application/IntentDraftParserTest.java`

The model answers with one JSON object:
```json
{"goals": [{"title","description","sourceQuotes":[],"assumptions":[]}],
 "requirements": [{"title","description","kind","moscow","goalTitle","sourceQuotes":[],"assumptions":[]}],
 "useCases": [{"title","actor","mainFlow":[],"altFlows":[],"requirementTitle","sourceQuotes":[],"assumptions":[]}]}
```
`goalTitle`/`requirementTitle` are nullable (JSON null or absent).

- [ ] **Step 1: Write the failing test**

```java
package dev.sdlc.agentintent.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IntentDraftParserTest {
    static final String JSON = """
            {"goals": [{"title": "Faster checkout", "description": "Reduce checkout to 5s",
                        "sourceQuotes": ["we lose carts at checkout"], "assumptions": []}],
             "requirements": [{"title": "Apply regional tax", "description": "Tax by shipping region",
                        "kind": "functional", "moscow": "MUST", "goalTitle": "Faster checkout",
                        "sourceQuotes": ["tax must match the region"], "assumptions": []}],
             "useCases": [{"title": "Customer checks out", "actor": "Customer",
                        "mainFlow": ["add items", "pay"], "altFlows": [],
                        "requirementTitle": "Apply regional tax",
                        "sourceQuotes": [], "assumptions": ["flow assumed from context"]}]}
            """;

    @Test
    void parsesAllThreeKinds() {
        var draft = new IntentDraftParser().parse(JSON);
        assertThat(draft.goals()).hasSize(1);
        assertThat(draft.requirements().getFirst().moscow()).isEqualTo("MUST");
        assertThat(draft.useCases().getFirst().requirementTitle()).isEqualTo("Apply regional tax");
    }

    @Test
    void toleratesFenceAndAbsentNullableLinks() {
        var json = """
                {"goals": [{"title": "G", "description": "d", "sourceQuotes": ["q"], "assumptions": []}],
                 "requirements": [], "useCases": []}
                """;
        var draft = new IntentDraftParser().parse("```json\n" + json + "\n```");
        assertThat(draft.goals()).hasSize(1);
    }

    @Test
    void rejectsProseAndUngroundedItems() {
        assertThatThrownBy(() -> new IntentDraftParser().parse("sorry, no json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentDraftParser().parse("""
                {"goals": [{"title": "G", "description": "d", "sourceQuotes": [], "assumptions": []}],
                 "requirements": [], "useCases": []}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grounded");
    }
}
```

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement**

```java
package dev.sdlc.agentintent.application;

import dev.sdlc.agentintent.domain.*;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.List;

/** Parses the model's final JSON into an IntentDraft; tolerates a ```json fence. */
public final class IntentDraftParser {

    public IntentDraft parse(String modelOutput) {
        String json = modelOutput.strip();
        if (json.startsWith("```"))
            json = json.substring(json.indexOf('\n') + 1, json.lastIndexOf("```")).strip();
        JsonObject root;
        try (var reader = Json.createReader(new StringReader(json))) {
            root = reader.readObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("model output is not the expected JSON: " + e.getMessage(), e);
        }
        try {
            var goals = root.getJsonArray("goals").stream().map(JsonValue::asJsonObject)
                    .map(o -> new GoalDraft(o.getString("title"), o.getString("description", ""),
                            strings(o, "sourceQuotes"), strings(o, "assumptions")))
                    .toList();
            var requirements = root.getJsonArray("requirements").stream().map(JsonValue::asJsonObject)
                    .map(o -> new RequirementDraft(o.getString("title"), o.getString("description", ""),
                            o.getString("kind"), o.getString("moscow"),
                            o.getString("goalTitle", null),
                            strings(o, "sourceQuotes"), strings(o, "assumptions")))
                    .toList();
            var useCases = root.getJsonArray("useCases").stream().map(JsonValue::asJsonObject)
                    .map(o -> new UseCaseDraft(o.getString("title"), o.getString("actor"),
                            strings(o, "mainFlow"), strings(o, "altFlows"),
                            o.getString("requirementTitle", null),
                            strings(o, "sourceQuotes"), strings(o, "assumptions")))
                    .toList();
            return new IntentDraft(goals, requirements, useCases);
        } catch (NullPointerException | ClassCastException e) {
            throw new IllegalArgumentException("model JSON missing or mistyped field: " + e.getMessage(), e);
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        JsonArray arr = o.getJsonArray(key);
        if (arr == null) return List.of();
        return arr.stream().map(v -> ((JsonString) v).getString()).toList();
    }
}
```
(Note `o.getString("goalTitle", null)`: jakarta.json returns the default when the key is absent; an explicit JSON null throws ClassCastException → wrapped as IAE. The system prompt in Task 5 tells the model to omit the key instead of writing null.)

- [ ] **Step 4: Run** — PASS — `./gradlew build` green. **Step 5: Commit:**

```bash
git add -A && git commit -m "feat(agent-intent): parse model output into grounded intent drafts"
```

### Task 5: agent-intent — GenerateIntentUseCase (ask_human as a tool)

**Files:**
- Create: `agents/agent-intent/src/main/java/dev/sdlc/agentintent/application/GenerateIntentUseCase.java`, `AskHumanTool.java`
- Test: `agents/agent-intent/src/test/java/dev/sdlc/agentintent/application/GenerateIntentUseCaseTest.java`

**Flow (spec §9, FR-INT-1/3/4):** input = one source document (path + content + its blobSha). Run the AgentLoop with ONE registered tool, `ask_human` (wraps `HumanInTheLoopPort.askClarifyingQuestion`). Parse the final JSON into `IntentDraft`. Allocate ids (`GOAL-xxxx`, `REQ-xxxx`/`NFR-xxxx` by kind, `UC-xxxx`) via the next-free-id probe (same pattern as `GenerateSpecificationUseCase.nextSpecId` — copy the pattern per prefix). Write each artifact file (`goals/`, `requirements/`, `usecases/` directories), upsert PROPOSED nodes, link `DERIVES_FROM` edges (requirement→goal via goalTitle; useCase→requirement via requirementTitle, falling back to the goal when null and a single goal exists), publish `ArtifactProposed` per artifact. Clarification Q/A pairs are appended to each artifact body under `## Clarifications` for auditability (FR-HITL-2 / grounding).

- [ ] **Step 1: AskHumanTool (tiny, write it first — no test of its own; the use-case test covers it)**

```java
package dev.sdlc.agentintent.application;

import dev.sdlc.agent.port.HumanInTheLoopPort;
import dev.sdlc.agent.port.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** FR-INT-4 / FR-HITL-2: the model asks, the human answers; every exchange is recorded. */
public final class AskHumanTool implements Tool {
    private final HumanInTheLoopPort human;
    private final List<String> transcript = new ArrayList<>();

    public AskHumanTool(HumanInTheLoopPort human) { this.human = human; }

    @Override public String name() { return "ask_human"; }
    @Override public String description() {
        return "Ask the stakeholder ONE clarifying question when the source is materially ambiguous.";
    }
    @Override public Map<String, String> parameterSchema() { return Map.of("question", "string"); }

    @Override public String execute(Map<String, Object> args) {
        var question = String.valueOf(args.get("question"));
        var answer = human.askClarifyingQuestion(question);
        transcript.add("Q: " + question + "\nA: " + answer);
        return answer;
    }

    /** Q/A pairs asked during this run, in order. */
    public List<String> transcript() { return List.copyOf(transcript); }
}
```

- [ ] **Step 2: Write the failing use-case test**

```java
package dev.sdlc.agentintent.application;

import dev.sdlc.agent.*;
import dev.sdlc.agent.port.*;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.domain.event.SdlcEvent;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GenerateIntentUseCaseTest {
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
    HumanInTheLoopPort human = new HumanInTheLoopPort() {
        public String askClarifyingQuestion(String q) { return "under 5 seconds"; }
        public ApprovalDecision requestApproval(ArtifactId a, String s) {
            return new ApprovalDecision(false, "n/a", null);
        }
    };

    static final String SOURCE = "Meeting notes: we lose carts at checkout. Tax must match the region.";
    static final String MODEL_JSON = """
            {"goals": [{"title": "Faster checkout", "description": "Reduce checkout time",
                        "sourceQuotes": ["we lose carts at checkout"], "assumptions": []}],
             "requirements": [{"title": "Apply regional tax", "description": "Tax by shipping region",
                        "kind": "functional", "moscow": "MUST", "goalTitle": "Faster checkout",
                        "sourceQuotes": ["Tax must match the region"], "assumptions": []}],
             "useCases": []}
            """;

    GenerateIntentUseCase useCase(LanguageModelPort model) {
        return new GenerateIntentUseCase(
                model, human, graph, repo, published::add, noTrace,
                new IntentDraftParser(), "agent-intent@v1", new Guardrails(12, 1.0));
    }

    @Test
    void elicitsWithClarificationAndProducesGroundedArtifacts() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("ask_human", Map.of("question", "how fast must checkout be?")),
                FakeLanguageModel.finalText(MODEL_JSON));

        var ids = useCase(model).generate("inbox/notes.md", SOURCE);

        assertThat(ids).hasSize(2);
        var goalId = ids.stream().filter(i -> i.prefix().equals("GOAL")).findFirst().orElseThrow();
        var reqId = ids.stream().filter(i -> i.prefix().equals("REQ")).findFirst().orElseThrow();

        // nodes PROPOSED with grounded provenance pointing at the source doc
        var goal = graph.get(goalId).orElseThrow();
        assertThat(goal.status()).isEqualTo(NodeStatus.PROPOSED);
        assertThat(goal.provenance().sourceRefs())
                .contains("inbox/notes.md@" + FrontmatterParser.gitBlobSha(SOURCE));
        // requirement derives from the goal
        assertThat(graph.downstreamOf(goalId)).extracting(Node::id).containsExactly(reqId);
        // clarification recorded in the artifact body
        assertThat(files.get(goal.repoPath()))
                .contains("## Clarifications")
                .contains("how fast must checkout be?")
                .contains("under 5 seconds");
        // events per artifact
        assertThat(published).containsExactlyInAnyOrder(
                new ArtifactProposed(goalId), new ArtifactProposed(reqId));
        // round-trip: the written goal file parses and pins the source
        var reparsed = new FrontmatterParser().parse(files.get(goal.repoPath()), goal.repoPath());
        assertThat(reparsed.node().status()).isEqualTo(NodeStatus.PROPOSED);
    }

    @Test
    void nfrKindGetsNfrPrefix() {
        var nfrJson = """
                {"goals": [], "useCases": [],
                 "requirements": [{"title": "Checkout under 5s p95", "description": "latency target",
                        "kind": "nfr", "moscow": "MUST",
                        "sourceQuotes": ["under 5 seconds"], "assumptions": []}]}
                """;
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(nfrJson));

        var ids = useCase(model).generate("inbox/notes.md", SOURCE);

        assertThat(ids).singleElement().extracting(ArtifactId::prefix).isEqualTo("NFR");
    }
}
```

- [ ] **Step 3: Run** — FAIL (use case missing). **Step 4: Implement**

```java
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
        var provenance = Provenance.generated(refs, agentVersion, 0.7,
                assumptions.isEmpty() && quotes.isEmpty() ? List.of("ungrounded — should be unreachable")
                        : assumptions);

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
```
Note for the implementer: the `"ungrounded — should be unreachable"` placeholder assumption can never fire (domain records enforce grounding) but keeps `Provenance.generated` total — if you find it distasteful, prove unreachability and inline `assumptions` directly; either is acceptable, say which you chose. The `pascal()` helper must produce exactly the names FrontmatterParser's `nodeType()` reverses (Goal, Requirement, NFR, UseCase) — the round-trip test in Step 2 pins one of them; check `NodeType.NFR` maps to literal `NFR` both ways.

- [ ] **Step 5: Run** — PASS — `./gradlew build` green. **Step 6: Commit:**

```bash
git add -A && git commit -m "feat(agent-intent): elicitation use case with ask_human tool (UC-0001)"
```

---

### Task 6: agent-intent — duplicate/conflict pass + inbox ingestion

**Files:**
- Create: `agents/agent-intent/src/main/java/dev/sdlc/agentintent/application/ReviewFlagsUseCase.java`, `IngestInboxUseCase.java`
- Test: `agents/agent-intent/src/test/java/dev/sdlc/agentintent/application/ReviewFlagsUseCaseTest.java`, `IngestInboxUseCaseTest.java`

- [ ] **Step 1: Failing tests**

`ReviewFlagsUseCaseTest.java`:
```java
package dev.sdlc.agentintent.application;

import dev.sdlc.agent.FakeLanguageModel;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.RunTracePort;
import dev.sdlc.domain.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewFlagsUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();
    RunTracePort noTrace = new RunTracePort() {
        public void step(String r, String k, String d, long i, long o, double c) {}
        public void finish(String r, String o) {}
    };

    Node node(String id, NodeType type, String title) {
        var prov = Provenance.generated(List.of("x"), "h", 1.0, List.of()).approve("a", T0);
        return new Node(ArtifactId.of(id), type, title, id + ".md", "s1",
                NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    @Test
    void recordsDuplicateAndConflictEdgesFromModelVerdict() {
        graph.upsert(node("REQ-0001", NodeType.REQUIREMENT, "Apply regional tax"));
        graph.upsert(node("REQ-0002", NodeType.REQUIREMENT, "Apply regional tax at checkout"));
        var verdict = """
                {"flags": [{"newId": "REQ-0002", "existingId": "REQ-0001",
                            "relation": "DUPLICATES", "reason": "same tax requirement"}]}
                """;
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(verdict));

        var flags = new ReviewFlagsUseCase(model, graph, noTrace, new Guardrails(3, 0.5), "agent-intent@v1")
                .reviewAgainstExisting(List.of(ArtifactId.of("REQ-0002")));

        assertThat(flags).singleElement().satisfies(f -> {
            assertThat(f.relation()).isEqualTo(EdgeType.DUPLICATES);
            assertThat(f.reason()).contains("same tax");
        });
        // edge recorded in the graph
        assertThat(graph.downstreamOf(ArtifactId.of("REQ-0001"), EdgeType.DUPLICATES))
                .extracting(n -> n.id().value()).containsExactly("REQ-0002");
    }

    @Test
    void noExistingArtifactsMeansNoModelCallAndNoFlags() {
        graph.upsert(node("REQ-0002", NodeType.REQUIREMENT, "anything"));
        var model = new FakeLanguageModel(); // would throw if called

        var flags = new ReviewFlagsUseCase(model, graph, noTrace, new Guardrails(3, 0.5), "agent-intent@v1")
                .reviewAgainstExisting(List.of(ArtifactId.of("REQ-0002")));

        assertThat(flags).isEmpty();
        assertThat(model.requests).isEmpty();
    }
}
```

`IngestInboxUseCaseTest.java`:
```java
package dev.sdlc.agentintent.application;

import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class IngestInboxUseCaseTest {
    @Test
    void processesEachInboxFileOnceAndMovesIt(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("inbox"));
        Files.writeString(workspace.resolve("inbox/notes.md"), "raw stakeholder text");
        var processed = new java.util.ArrayList<String>();

        var useCase = new IngestInboxUseCase(workspace,
                (path, content) -> { processed.add(path + "::" + content); return List.of(); });
        useCase.ingest();
        useCase.ingest(); // second run: nothing left

        assertThat(processed).containsExactly("inbox/notes.md::raw stakeholder text");
        assertThat(workspace.resolve("inbox/notes.md")).doesNotExist();
        assertThat(workspace.resolve("inbox/processed/notes.md")).exists();
    }

    @Test
    void failureLeavesTheFileInPlace(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("inbox"));
        Files.writeString(workspace.resolve("inbox/bad.md"), "text");

        var useCase = new IngestInboxUseCase(workspace,
                (path, content) -> { throw new IllegalStateException("model exploded"); });

        assertThatThrownBy(useCase::ingest).isInstanceOf(IllegalStateException.class);
        assertThat(workspace.resolve("inbox/bad.md")).exists(); // not moved
    }
}
```

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement**

`ReviewFlagsUseCase.java`:
```java
package dev.sdlc.agentintent.application;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.Guardrails;
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
    private final RunTracePort trace;
    private final Guardrails guardrails;
    private final String agentVersion;

    public ReviewFlagsUseCase(LanguageModelPort model, TraceabilityGraphPort graph,
                              RunTracePort trace, Guardrails guardrails, String agentVersion) {
        this.model = model; this.graph = graph; this.trace = trace;
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
        var loop = new AgentLoop(model, new ToolRegistry(List.of()), trace, guardrails);
        var result = loop.run("intent-review-" + UUID.randomUUID(), SYSTEM_PROMPT, task);

        var flags = parse(result.finalText());
        var now = Instant.now();
        for (var f : flags)
            graph.link(Edge.current(f.relation(), f.newId(), f.existingId(),
                    graph.get(f.existingId()).map(Node::blobSha).orElse("unknown"), agentVersion, now));
        return flags;
    }

    private List<Node> allIntentNodes() {
        // Phase 1B: the port has no listing by type; enumerate via the id probe used for allocation
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
```
Implementer note on `allIntentNodes()`: the contiguous-id probe relies on ids being allocated densely from 1 (true: `nextId` always takes the first gap). It breaks if an id is ever hard-deleted — acceptable Phase 1B (nothing deletes nodes); a `listByType` port method is the clean Phase 2 fix. Put this exact caveat in a comment.

`IngestInboxUseCase.java`:
```java
package dev.sdlc.agentintent.application;

import dev.sdlc.domain.ArtifactId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** FR-INT-1: each inbox file is processed once; success moves it to inbox/processed/. */
public final class IngestInboxUseCase {
    /** (workspace-relative path, content) -> produced artifact ids. */
    @FunctionalInterface
    public interface SourceProcessor {
        List<ArtifactId> process(String path, String content);
    }

    private final Path workspace;
    private final SourceProcessor processor;

    public IngestInboxUseCase(Path workspace, SourceProcessor processor) {
        this.workspace = workspace; this.processor = processor;
    }

    /** Returns ids produced across all newly processed files. */
    public List<ArtifactId> ingest() {
        var inbox = workspace.resolve("inbox");
        if (!Files.isDirectory(inbox)) return List.of();
        var produced = new ArrayList<ArtifactId>();
        try (Stream<Path> files = Files.list(inbox)) {
            for (var file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .sorted().toList()) {
                var rel = workspace.relativize(file).toString().replace('\\', '/');
                var content = Files.readString(file);
                produced.addAll(processor.process(rel, content)); // throws -> file stays put
                var processedDir = inbox.resolve("processed");
                Files.createDirectories(processedDir);
                Files.move(file, processedDir.resolve(file.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return produced;
    }
}
```

- [ ] **Step 4: Run** module + `./gradlew build` — green. **Step 5: Commit:**

```bash
git add -A && git commit -m "feat(agent-intent): duplicate/conflict review flags and inbox ingestion (FR-INT-1/2)"
```

### Task 7: agent-design — module, domain, parser

**Files:**
- Create: `agents/agent-design/build.gradle.kts` (same shape as agent-intent's — copy it verbatim, it is identical); modify `settings.gradle.kts` (include `agents:agent-design`)
- Create in `agents/agent-design/src/main/java/dev/sdlc/agentdesign/domain/`: `DesignDraft.java`, `AdrDraft.java`, `DesignElementDraft.java`, `ApiContractDraft.java`
- Create: `agents/agent-design/src/main/java/dev/sdlc/agentdesign/application/DesignDraftParser.java`
- Test: `agents/agent-design/src/test/java/dev/sdlc/agentdesign/domain/DesignDraftTest.java`, `.../application/DesignDraftParserTest.java`

- [ ] **Step 1: Failing domain test**

```java
package dev.sdlc.agentdesign.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DesignDraftTest {
    AdrDraft adr = new AdrDraft("Use outbox pattern", "events must not be lost",
            "Use a transactional outbox",
            List.of(new AdrDraft.Alternative("Direct publish", "simpler but loses events on crash"),
                    new AdrDraft.Alternative("CDC", "robust but heavy infra")),
            List.of("extra table and relay process"));

    @Test
    void adrRequiresAtLeastTwoConsideredAlternatives() {
        // FR-DES-3 structurally: no silent decisions
        assertThatThrownBy(() -> new AdrDraft("t", "c", "d",
                List.of(new AdrDraft.Alternative("only one", "x")), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alternatives");
    }

    @Test
    void draftNeedsAtLeastOneArtifact() {
        assertThatThrownBy(() -> new DesignDraft(List.of(), List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
        assertThat(new DesignDraft(List.of(), List.of(adr), List.of()).adrs()).hasSize(1);
    }

    @Test
    void elementAndContractRequireTitleAndBody() {
        assertThatThrownBy(() -> new DesignElementDraft(" ", "desc"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiContractDraft("Checkout API", " "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement the records**

`AdrDraft.java`:
```java
package dev.sdlc.agentdesign.domain;

import java.util.List;

/** FR-DES-3: a decision is only recordable alongside the alternatives it beat. */
public record AdrDraft(String title, String context, String decision,
                       List<Alternative> alternatives, List<String> consequences) {
    public record Alternative(String option, String tradeoff) {
        public Alternative {
            if (option == null || option.isBlank())
                throw new IllegalArgumentException("alternative option required");
        }
    }

    public AdrDraft {
        alternatives = List.copyOf(alternatives);
        consequences = List.copyOf(consequences);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("adr title required");
        if (decision == null || decision.isBlank())
            throw new IllegalArgumentException("adr decision required");
        if (alternatives.size() < 2)
            throw new IllegalArgumentException("adr needs >= 2 considered alternatives, got "
                    + alternatives.size());
    }
}
```

`DesignElementDraft.java`:
```java
package dev.sdlc.agentdesign.domain;

/** A component/module/aggregate description. */
public record DesignElementDraft(String title, String description) {
    public DesignElementDraft {
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("design element title required");
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("design element description required");
    }
}
```

`ApiContractDraft.java`:
```java
package dev.sdlc.agentdesign.domain;

/** An endpoint/message contract, body in markdown. */
public record ApiContractDraft(String title, String contract) {
    public ApiContractDraft {
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("api contract title required");
        if (contract == null || contract.isBlank())
            throw new IllegalArgumentException("api contract body required");
    }
}
```

`DesignDraft.java`:
```java
package dev.sdlc.agentdesign.domain;

import java.util.List;

public record DesignDraft(List<DesignElementDraft> elements, List<AdrDraft> adrs,
                          List<ApiContractDraft> apiContracts) {
    public DesignDraft {
        elements = List.copyOf(elements);
        adrs = List.copyOf(adrs);
        apiContracts = List.copyOf(apiContracts);
        if (elements.isEmpty() && adrs.isEmpty() && apiContracts.isEmpty())
            throw new IllegalArgumentException("design draft needs at least one artifact");
    }
}
```

- [ ] **Step 4: Failing parser test**

```java
package dev.sdlc.agentdesign.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DesignDraftParserTest {
    static final String JSON = """
            {"elements": [{"title": "TaxCalculator", "description": "Pure domain service computing regional tax"}],
             "adrs": [{"title": "Use outbox pattern", "context": "events must not be lost",
                       "decision": "transactional outbox",
                       "alternatives": [{"option": "direct publish", "tradeoff": "loses events on crash"},
                                        {"option": "CDC", "tradeoff": "heavy infra"}],
                       "consequences": ["extra relay process"]}],
             "apiContracts": [{"title": "Checkout API", "contract": "POST /checkout -> 200 {total, tax}"}]}
            """;

    @Test
    void parsesAllThreeKinds() {
        var draft = new DesignDraftParser().parse(JSON);
        assertThat(draft.elements()).hasSize(1);
        assertThat(draft.adrs().getFirst().alternatives()).hasSize(2);
        assertThat(draft.apiContracts().getFirst().title()).isEqualTo("Checkout API");
    }

    @Test
    void rejectsSingleAlternativeAdr() {
        assertThatThrownBy(() -> new DesignDraftParser().parse("""
                {"elements": [], "apiContracts": [],
                 "adrs": [{"title": "t", "context": "c", "decision": "d",
                           "alternatives": [{"option": "only", "tradeoff": "x"}], "consequences": []}]}
                """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alternatives");
    }
}
```

- [ ] **Step 5: Implement the parser** (same shape as IntentDraftParser — fence strip, jakarta.json, NPE/CCE→IAE):

```java
package dev.sdlc.agentdesign.application;

import dev.sdlc.agentdesign.domain.*;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.List;

/** Parses the model's final JSON into a DesignDraft; tolerates a ```json fence. */
public final class DesignDraftParser {

    public DesignDraft parse(String modelOutput) {
        String json = modelOutput.strip();
        if (json.startsWith("```"))
            json = json.substring(json.indexOf('\n') + 1, json.lastIndexOf("```")).strip();
        JsonObject root;
        try (var reader = Json.createReader(new StringReader(json))) {
            root = reader.readObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("model output is not the expected JSON: " + e.getMessage(), e);
        }
        try {
            var elements = root.getJsonArray("elements").stream().map(JsonValue::asJsonObject)
                    .map(o -> new DesignElementDraft(o.getString("title"), o.getString("description")))
                    .toList();
            var adrs = root.getJsonArray("adrs").stream().map(JsonValue::asJsonObject)
                    .map(o -> new AdrDraft(o.getString("title"), o.getString("context", ""),
                            o.getString("decision"),
                            o.getJsonArray("alternatives").stream().map(JsonValue::asJsonObject)
                                    .map(a -> new AdrDraft.Alternative(a.getString("option"),
                                            a.getString("tradeoff", "")))
                                    .toList(),
                            strings(o, "consequences")))
                    .toList();
            var contracts = root.getJsonArray("apiContracts").stream().map(JsonValue::asJsonObject)
                    .map(o -> new ApiContractDraft(o.getString("title"), o.getString("contract")))
                    .toList();
            return new DesignDraft(elements, adrs, contracts);
        } catch (NullPointerException | ClassCastException e) {
            throw new IllegalArgumentException("model JSON missing or mistyped field: " + e.getMessage(), e);
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        JsonArray arr = o.getJsonArray(key);
        if (arr == null) return List.of();
        return arr.stream().map(v -> ((JsonString) v).getString()).toList();
    }
}
```

- [ ] **Step 6: Run** module + `./gradlew build` — green. **Step 7: Commit:**

```bash
git add -A && git commit -m "feat(agent-design): design draft domain (ADRs require alternatives) and parser"
```

---

### Task 8: agent-design — GenerateDesignUseCase

**Files:**
- Create: `agents/agent-design/src/main/java/dev/sdlc/agentdesign/application/GenerateDesignUseCase.java`
- Test: `agents/agent-design/src/test/java/dev/sdlc/agentdesign/application/GenerateDesignUseCaseTest.java`

**Flow (spec §10):** input = one APPROVED Specification id. Context = spec body (via repo.read) + bodies of its upstream requirement/use-case files + one-line summaries (id, type, title) of ALL existing APPROVED DES/ADR/API nodes (FR-DES-2, graph-only — record the "graph summaries, not source code" assumption in provenance). Run the loop (no tools). Parse → write `designs/DES-xxxx.md`, `adrs/ADR-xxxx.md`, `apis/API-xxxx.md`, all PROPOSED, `DERIVES_FROM` the spec (pinned), `ArtifactProposed` each. ADR body renders Context/Decision/Alternatives/Consequences sections; the assumption `'design grounded in graph summaries, not source code (Phase 1B scope)'` goes into every artifact's provenance assumptions.

- [ ] **Step 1: Write the failing test**

```java
package dev.sdlc.agentdesign.application;

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

class GenerateDesignUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    static final String SPEC_SHA = "c".repeat(40);

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

    Node approvedSpec() {
        var prov = Provenance.generated(List.of("REQ-0001@" + "a".repeat(40)), "agent-spec@v1",
                0.8, List.of()).approve("a.dupont", T0);
        return new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "Checkout tax",
                "specs/SPEC-0001.md", SPEC_SHA, NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    static final String MODEL_JSON = """
            {"elements": [{"title": "TaxCalculator", "description": "Pure domain service"}],
             "adrs": [{"title": "Outbox pattern", "context": "c", "decision": "outbox",
                       "alternatives": [{"option": "direct", "tradeoff": "lossy"},
                                        {"option": "CDC", "tradeoff": "heavy"}],
                       "consequences": ["relay"]}],
             "apiContracts": []}
            """;

    @Test
    void producesProposedDesignArtifactsDerivingFromTheSpec() {
        graph.upsert(approvedSpec());
        files.put("specs/SPEC-0001.md", "---\nid: SPEC-0001\n---\nspec body text");
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(MODEL_JSON));

        var ids = new GenerateDesignUseCase(model, graph, repo, published::add, noTrace,
                new DesignDraftParser(), "agent-design@v1", new Guardrails(5, 1.0))
                .generate(ArtifactId.of("SPEC-0001"));

        assertThat(ids).hasSize(2);
        var adrId = ids.stream().filter(i -> i.prefix().equals("ADR")).findFirst().orElseThrow();
        var adr = graph.get(adrId).orElseThrow();
        assertThat(adr.status()).isEqualTo(NodeStatus.PROPOSED);
        // derives from the spec, pinned
        assertThat(files.get(adr.repoPath())).contains("SPEC-0001@" + SPEC_SHA);
        // alternatives rendered
        assertThat(files.get(adr.repoPath())).contains("## Alternatives").contains("CDC");
        // graph-scope assumption recorded
        assertThat(adr.provenance().assumptions())
                .anyMatch(a -> a.contains("graph summaries"));
        // prompt contained the spec body
        assertThat(model.requests.getFirst().messages().getFirst().content())
                .contains("spec body text");
        assertThat(published).contains(new ArtifactProposed(adrId));
        // round-trip
        var reparsed = new FrontmatterParser().parse(files.get(adr.repoPath()), adr.repoPath());
        assertThat(reparsed.edgeTargets().get(EdgeType.DERIVES_FROM))
                .containsExactly(new EdgeTarget(ArtifactId.of("SPEC-0001"), SPEC_SHA));
    }

    @Test
    void refusesUnapprovedSpec() {
        var prov = Provenance.generated(List.of("x"), "h", 1.0, List.of());
        graph.upsert(new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "t",
                "specs/SPEC-0001.md", SPEC_SHA, NodeStatus.PROPOSED, 1, prov, T0, T0));
        var model = new FakeLanguageModel();

        assertThatThrownBy(() -> new GenerateDesignUseCase(model, graph, repo, published::add,
                noTrace, new DesignDraftParser(), "agent-design@v1", new Guardrails(5, 1.0))
                .generate(ArtifactId.of("SPEC-0001")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APPROVED");
        assertThat(model.requests).isEmpty();
    }
}
```

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement**

```java
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
```
(Note: spec §10 also lists upstream requirement/use-case bodies in the context; including them needs only `graph.downstreamOf` inversion — the spec's upstreams are reachable via its frontmatter, but the node API has no upstreamOf. Phase 1B keeps the spec body + design summaries; the spec file's own body already embeds the Gherkin derived from those requirements. Record this as a one-line comment in `generate()`: `// upstream req/uc bodies omitted: the spec body already embeds their acceptance criteria (1B scope)`.)

- [ ] **Step 4: Run** module + `./gradlew build` — green. **Step 5: Commit:**

```bash
git add -A && git commit -m "feat(agent-design): generate-design use case with mandatory ADR alternatives (FR-DES-1..3)"
```

### Task 9: agent-backlog — BacklogPort, domain, use case

**Files:**
- Create: `libs/agent-core/src/main/java/dev/sdlc/agent/port/BacklogPort.java`
- Create: `agents/agent-backlog/build.gradle.kts` (identical shape to agent-intent's); modify `settings.gradle.kts`
- Create in `agents/agent-backlog/src/main/java/dev/sdlc/agentbacklog/domain/`: `BacklogDraft.java`, `BacklogItemDraft.java`
- Create in `agents/agent-backlog/src/main/java/dev/sdlc/agentbacklog/application/`: `BacklogDraftParser.java`, `GenerateBacklogUseCase.java`
- Tests: `.../domain/BacklogDraftTest.java`, `.../application/BacklogDraftParserTest.java`, `.../application/GenerateBacklogUseCaseTest.java`

- [ ] **Step 1: BacklogPort (spec §11 — reserved externalRef for vendor adapters)**

```java
package dev.sdlc.agent.port;

import dev.sdlc.domain.ArtifactId;

import java.util.List;
import java.util.Optional;

/** Outbound port to the backlog tool. Phase 1B: file-based; Jira/Linear/ADO later. */
public interface BacklogPort {
    /** level: epic|story|task. estimate: XS|S|M|L|XL. Returns the externalRef (file path for now). */
    String upsert(ArtifactId id, String level, String title, String body, String estimate);
    Optional<String> find(ArtifactId id);
    List<ArtifactId> listOpen();
}
```

- [ ] **Step 2: Failing domain + parser tests**

`BacklogDraftTest.java`:
```java
package dev.sdlc.agentbacklog.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class BacklogDraftTest {
    BacklogItemDraft story = new BacklogItemDraft("story", "Tax at checkout",
            "implement regional tax", "FR VAT", "M", List.of());

    @Test
    void levelAndEstimateAreConstrained() {
        assertThatThrownBy(() -> new BacklogItemDraft("saga", "t", "d", null, "M", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("level");
        assertThatThrownBy(() -> new BacklogItemDraft("story", "t", "d", null, "XXL", List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("estimate");
    }

    @Test
    void draftNeedsItemsAndValidDependencyTitles() {
        assertThatThrownBy(() -> new BacklogDraft(List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
        var dep = new BacklogItemDraft("story", "Depends on tax", "d", null, "S",
                List.of("Tax at checkout"));
        assertThat(new BacklogDraft(List.of(story, dep)).items()).hasSize(2);
        // dependency on an unknown title is invalid
        assertThatThrownBy(() -> new BacklogDraft(List.of(
                new BacklogItemDraft("story", "x", "d", null, "S", List.of("No such item")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown dependency");
    }
}
```

`BacklogDraftParserTest.java`:
```java
package dev.sdlc.agentbacklog.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BacklogDraftParserTest {
    @Test
    void parsesItemsWithDependencies() {
        var draft = new BacklogDraftParser().parse("""
                {"items": [
                  {"level": "epic", "title": "Checkout tax", "description": "everything tax",
                   "acceptanceHook": null, "estimate": "L", "dependsOn": []},
                  {"level": "story", "title": "Regional rate lookup", "description": "lookup by region",
                   "acceptanceHook": "FR VAT", "estimate": "M", "dependsOn": ["Checkout tax"]}
                ]}
                """);
        assertThat(draft.items()).hasSize(2);
        assertThat(draft.items().get(1).dependsOn()).containsExactly("Checkout tax");
    }

    @Test
    void rejectsProse() {
        assertThatThrownBy(() -> new BacklogDraftParser().parse("no json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: Implement domain + parser**

`BacklogItemDraft.java`:
```java
package dev.sdlc.agentbacklog.domain;

import java.util.List;
import java.util.Set;

/** level: epic|story|task; estimate: XS..XL; acceptanceHook names the Gherkin scenario served (nullable). */
public record BacklogItemDraft(String level, String title, String description,
                               String acceptanceHook, String estimate, List<String> dependsOn) {
    private static final Set<String> LEVELS = Set.of("epic", "story", "task");
    private static final Set<String> ESTIMATES = Set.of("XS", "S", "M", "L", "XL");

    public BacklogItemDraft {
        dependsOn = List.copyOf(dependsOn);
        if (!LEVELS.contains(level))
            throw new IllegalArgumentException("level must be epic|story|task: " + level);
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("backlog item title required");
        if (!ESTIMATES.contains(estimate))
            throw new IllegalArgumentException("estimate must be XS|S|M|L|XL: " + estimate);
    }
}
```

`BacklogDraft.java`:
```java
package dev.sdlc.agentbacklog.domain;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record BacklogDraft(List<BacklogItemDraft> items) {
    public BacklogDraft {
        items = List.copyOf(items);
        if (items.isEmpty())
            throw new IllegalArgumentException("backlog draft needs at least one item");
        Set<String> titles = items.stream().map(BacklogItemDraft::title).collect(Collectors.toSet());
        for (var item : items)
            for (var dep : item.dependsOn())
                if (!titles.contains(dep))
                    throw new IllegalArgumentException("unknown dependency '" + dep
                            + "' on item '" + item.title() + "'");
    }
}
```

`BacklogDraftParser.java`:
```java
package dev.sdlc.agentbacklog.application;

import dev.sdlc.agentbacklog.domain.BacklogDraft;
import dev.sdlc.agentbacklog.domain.BacklogItemDraft;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.List;

/** Parses the model's final JSON into a BacklogDraft; tolerates a ```json fence. */
public final class BacklogDraftParser {

    public BacklogDraft parse(String modelOutput) {
        String json = modelOutput.strip();
        if (json.startsWith("```"))
            json = json.substring(json.indexOf('\n') + 1, json.lastIndexOf("```")).strip();
        JsonObject root;
        try (var reader = Json.createReader(new StringReader(json))) {
            root = reader.readObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("model output is not the expected JSON: " + e.getMessage(), e);
        }
        try {
            var items = root.getJsonArray("items").stream().map(JsonValue::asJsonObject)
                    .map(o -> new BacklogItemDraft(o.getString("level"), o.getString("title"),
                            o.getString("description", ""),
                            o.isNull("acceptanceHook") ? null : o.getString("acceptanceHook", null),
                            o.getString("estimate"), strings(o, "dependsOn")))
                    .toList();
            return new BacklogDraft(items);
        } catch (NullPointerException | ClassCastException e) {
            throw new IllegalArgumentException("model JSON missing or mistyped field: " + e.getMessage(), e);
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        JsonArray arr = o.getJsonArray(key);
        if (arr == null) return List.of();
        return arr.stream().map(v -> ((JsonString) v).getString()).toList();
    }
}
```
(`o.isNull(key)` requires the key to exist — guard: `o.containsKey("acceptanceHook") && o.isNull("acceptanceHook") ? null : o.getString("acceptanceHook", null)`. Use the guarded form; the test's explicit `"acceptanceHook": null` pins it.)

- [ ] **Step 4: Failing use-case test**

```java
package dev.sdlc.agentbacklog.application;

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

class GenerateBacklogUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    static final String SPEC_SHA = "c".repeat(40);
    static final String ADR_SHA = "d".repeat(40);

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

    Node approved(String id, NodeType type, String sha) {
        var prov = Provenance.generated(List.of("x"), "h", 0.8, List.of()).approve("a.dupont", T0);
        return new Node(ArtifactId.of(id), type, id, id + ".md", sha,
                NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    static final String MODEL_JSON = """
            {"items": [
              {"level": "epic", "title": "Checkout tax", "description": "all tax work",
               "acceptanceHook": null, "estimate": "L", "dependsOn": []},
              {"level": "story", "title": "Regional rate lookup", "description": "lookup by region",
               "acceptanceHook": "FR VAT", "estimate": "M", "dependsOn": ["Checkout tax"]}
            ]}
            """;

    @Test
    void producesBacklogItemsWithDependencyEdgesAndFiles() {
        graph.upsert(approved("SPEC-0001", NodeType.SPECIFICATION, SPEC_SHA));
        graph.upsert(approved("ADR-0001", NodeType.ADR, ADR_SHA));
        files.put("SPEC-0001.md", "---\nid: SPEC-0001\n---\nGherkin here");
        files.put("ADR-0001.md", "---\nid: ADR-0001\n---\noutbox decision");
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(MODEL_JSON));
        var backlog = new dev.sdlc.adapter.common.FileBacklogAdapter(repo); // Task 10 creates this — see note below

        var ids = new GenerateBacklogUseCase(model, graph, repo, backlog, published::add, noTrace,
                new BacklogDraftParser(), "agent-backlog@v1", new Guardrails(5, 1.0))
                .generate(ArtifactId.of("SPEC-0001"), List.of(ArtifactId.of("ADR-0001")));

        assertThat(ids).hasSize(2);
        var epicId = ids.getFirst();   // EPIC-0001
        var storyId = ids.get(1);      // STORY-0001
        assertThat(epicId.prefix()).isEqualTo("EPIC");
        assertThat(storyId.prefix()).isEqualTo("STORY");
        // both derive from the spec (pinned) and the design artifact
        var storyFile = files.get(graph.get(storyId).orElseThrow().repoPath());
        assertThat(storyFile).contains("SPEC-0001@" + SPEC_SHA).contains("ADR-0001@" + ADR_SHA);
        // dependency edge story -> epic
        assertThat(graph.downstreamOf(epicId, EdgeType.DEPENDS_ON))
                .extracting(Node::id).containsExactly(storyId);
        // estimate + level + acceptance hook in frontmatter-adjacent body
        assertThat(storyFile).contains("level: story").contains("estimate: M").contains("FR VAT");
        assertThat(published).contains(new ArtifactProposed(epicId), new ArtifactProposed(storyId));
    }

    @Test
    void refusesUnapprovedInputs() {
        var prov = Provenance.generated(List.of("x"), "h", 0.8, List.of());
        graph.upsert(new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "t", "p",
                SPEC_SHA, NodeStatus.PROPOSED, 1, prov, T0, T0));
        var model = new FakeLanguageModel();
        var backlog = new dev.sdlc.adapter.common.FileBacklogAdapter(repo);

        assertThatThrownBy(() -> new GenerateBacklogUseCase(model, graph, repo, backlog,
                published::add, noTrace, new BacklogDraftParser(), "agent-backlog@v1",
                new Guardrails(5, 1.0)).generate(ArtifactId.of("SPEC-0001"), List.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APPROVED");
        assertThat(model.requests).isEmpty();
    }
}
```
**Ordering note:** this test references `FileBacklogAdapter` from Task 10. To keep tasks self-contained, Task 9 creates BOTH the use case AND the adapter is deferred — that breaks the test. Resolution: Task 9's build file adds `implementation(project(":libs:adapter-common"))` (already in the standard agent build block) and Task 9 ALSO creates `FileBacklogAdapter` in adapter-common (it is 30 lines and inseparable from the use-case test). Task 10 then only wires bootstraps. The adapter:

`libs/adapter-common/src/main/java/dev/sdlc/adapter/common/FileBacklogAdapter.java`:
```java
package dev.sdlc.adapter.common;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.BacklogPort;
import dev.sdlc.domain.ArtifactId;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Spec §11: backlog items ARE graph artifacts; this port impl just owns their file layout. */
public final class FileBacklogAdapter implements BacklogPort {
    private final ArtifactRepositoryPort repo;
    private final List<ArtifactId> open = new ArrayList<>();

    public FileBacklogAdapter(ArtifactRepositoryPort repo) { this.repo = repo; }

    @Override public String upsert(ArtifactId id, String level, String title, String body, String estimate) {
        var path = "backlog/" + id.value() + ".md";
        // body arrives as the FULL artifact file content (frontmatter included) — the use case
        // renders it; this adapter owns only placement. externalRef = the path (vendor id later).
        repo.write(path, body);
        if (!open.contains(id)) open.add(id);
        return path;
    }

    @Override public Optional<String> find(ArtifactId id) {
        return repo.read("backlog/" + id.value() + ".md").map(c -> "backlog/" + id.value() + ".md");
    }

    @Override public List<ArtifactId> listOpen() { return List.copyOf(open); }
}
```
Wait — if `body` is the full file content, `upsert`'s `level`/`title`/`estimate` params are redundant; they exist for the VENDOR adapters (Jira needs fields, not files). Keep the signature (port is vendor-shaped), document that the file adapter ignores the structured params except for placement. Add one test in adapter-common:

`libs/adapter-common/src/test/java/dev/sdlc/adapter/common/FileBacklogAdapterTest.java`:
```java
package dev.sdlc.adapter.common;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class FileBacklogAdapterTest {
    Map<String, String> files = new HashMap<>();
    ArtifactRepositoryPort repo = new ArtifactRepositoryPort() {
        public String write(String path, String content) { files.put(path, content); return "sha"; }
        public Optional<String> read(String path) { return Optional.ofNullable(files.get(path)); }
    };

    @Test
    void upsertWritesUnderBacklogAndTracksOpenItems() {
        var adapter = new FileBacklogAdapter(repo);
        var ref = adapter.upsert(ArtifactId.of("STORY-0001"), "story", "t", "full file content", "M");

        assertThat(ref).isEqualTo("backlog/STORY-0001.md");
        assertThat(files).containsKey("backlog/STORY-0001.md");
        assertThat(adapter.find(ArtifactId.of("STORY-0001"))).contains("backlog/STORY-0001.md");
        assertThat(adapter.listOpen()).containsExactly(ArtifactId.of("STORY-0001"));
        assertThat(adapter.find(ArtifactId.of("STORY-0099"))).isEmpty();
    }
}
```

- [ ] **Step 5: Implement GenerateBacklogUseCase**

```java
package dev.sdlc.agentbacklog.application;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.*;
import dev.sdlc.agentbacklog.domain.BacklogDraft;
import dev.sdlc.agentbacklog.domain.BacklogItemDraft;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.ArtifactProposed;
import dev.sdlc.trace.Edge;
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
        var sha = dev.sdlc.trace.FrontmatterParser.gitBlobSha(content);
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
```

- [ ] **Step 6: Run** all three modules + `./gradlew build` — green (ArchUnit auto-covers `agentbacklog`). **Step 7: Commit:**

```bash
git add -A && git commit -m "feat(agent-backlog): backlog decomposition with dependency edges (FR-BL-1/2)"
```

---

### Task 10: Bootstraps for the three agents + orphan report

**Files:**
- Create: `agents/agent-intent/src/main/java/dev/sdlc/agentintent/bootstrap/AgentIntentApplication.java` (+ `application.yaml`)
- Create: `agents/agent-design/src/main/java/dev/sdlc/agentdesign/bootstrap/AgentDesignApplication.java` (+ `application.yaml`)
- Create: `agents/agent-backlog/src/main/java/dev/sdlc/agentbacklog/bootstrap/AgentBacklogApplication.java` (+ `application.yaml`)
- Modify: the three agent build files (spring-boot plugin + starter + adapter-llm-spring, mirroring agent-spec's)

Each bootstrap mirrors `AgentSpecApplication`'s shape exactly (read it first): rebuild graph from `workspace` (3-arg, publishing to the bus), profile selection (postgres/git-approval/otel — copy the blocks verbatim), Spring AI ChatModel injection, CommandLineRunner with empty-args usage guard, console HITL approval gate after generation:
- **agent-intent runner:** no args needed — runs `IngestInboxUseCase` over `workspace/inbox/`, wiring `GenerateIntentUseCase` as the processor; then `ReviewFlagsUseCase` over the produced ids; then a per-artifact approval loop. The approval use case currently lives in agent-spec's application package and agents must NOT import each other. **Sub-step 10a (do first):** create a tiny new module `libs/governance` (package `dev.sdlc.governance`) depending on `libs:agent-core` + `libs:traceability-graph`, and `git mv` `ApproveArtifactUseCase` + `RevalidateArtifactUseCase` there with their tests (package line + imports only). Rationale, decided at plan time: moving them INTO agent-core would force an agent-core→traceability-graph dependency that inverts the layering; a separate module keeps both dependency directions clean. All four agent modules depend on `libs:governance`. ArchUnit: add `dev.sdlc.governance..` to rule 3's framework-free selector list (it is application-layer code).
- **agent-design runner:** args = SPEC ids; per spec id, `GenerateDesignUseCase.generate` then the approval loop per produced artifact.
- **agent-backlog runner:** args = one SPEC id (+ optional design ids); `GenerateBacklogUseCase` then approvals; afterwards print the orphan/stale report (FR-BL-3): `graph.staleNodes()` filtered to `BACKLOG_ITEM` plus any backlog node whose inbound DERIVES_FROM edge has `linkStatus == ORPHANED` — the port has no edge-listing API; report stale only and add the comment `// ORPHANED listing needs an edge query API — Phase 2` (honest scope cut).

application.yaml per agent: copy agent-spec's, changing nothing but the (commented) name.

Tests: bootstrap mains are wiring (no unit tests, same as agent-spec's); the governance move keeps its tests green from the new home; `./gradlew build` green incl. ArchUnit.

- [ ] Steps: 10a governance module move (own commit: `refactor: extract approval/revalidation governance into libs/governance`); 10b the three bootstraps + build wiring (commit: `feat(agents): intent/design/backlog bootstraps with shared governance gate`).

### Task 11: Closed-loop E2E (spec §12)

**Files:**
- Create: `agents/agent-backlog/src/test/java/dev/sdlc/agentbacklog/bootstrap/UpstreamLoopEndToEndTest.java`
- Modify: `agents/agent-backlog/build.gradle.kts` — the test needs the other agents' application classes: add `testImplementation(project(":agents:agent-intent"))` and `testImplementation(project(":agents:agent-design"))` and `testImplementation(project(":libs:governance"))` (test-scope cross-agent dependency is acceptable for the loop test ONLY; production code never crosses agents — note this in a comment in the build file)
- Create: `workspace/inbox/payment-notes.md` (demo seed, also used by the CLI demo)

The single test proving UC-0001→UC-0004 + propagation + restart parity. All agents run with scripted FakeLanguageModels; everything else is real (files in @TempDir, InMemoryTraceabilityGraph, InProcessEventPublisher, ConsoleHumanInTheLoop over StringReader, governance gate). Choreography:

1. **Intent:** inbox file with payment notes → `IngestInboxUseCase`+`GenerateIntentUseCase` (scripted: one `ask_human` call, then goal+requirement JSON) → approve both via the governance gate (scripted console "y").
2. **Spec:** `GenerateSpecificationUseCase` (from agent-spec — add `testImplementation(project(":agents:agent-spec"))` too) on the approved REQ → approve.
3. **Design:** `GenerateDesignUseCase` → ADR with two alternatives → approve.
4. **Backlog:** `GenerateBacklogUseCase` → epic + dependent story → approve all.
5. **Propagation:** edit the REQ file content (repo.write + `ArtifactChanged` on the bus) → assert the FULL chain (SPEC, ADR/DES, EPIC, STORY) is NEEDS_REVALIDATION, with `RevalidationRequested` events for each.
6. **Restart parity:** fresh graph + 3-arg rebuild from the workspace → assert `staleNodes()` ids identical to step 5's set, and every approval survived (each node APPROVED-before-flagging is restorable: spot-check the SPEC's approvedBy in the reparsed frontmatter).

Implementation notes for the engineer:
- Build each use case exactly as its own module's tests do (constructor signatures are in Tasks 5/8/9; governance gate from `dev.sdlc.governance`).
- Scripted model JSONs: reuse the MODEL_JSON constants from the per-module tests (copy them in — self-containment beats DRY in tests).
- The intent JSON's requirement must title-match what the spec step's scripted JSON derives from — the chain is by ids, not titles, so just collect returned ids and feed them forward.
- Approvals happen through ONE shared `InMemoryTraceabilityGraph` + ONE file map/`@TempDir`-backed repo — the agents share the workspace exactly as in production.
- Step 5's full-chain assertion is the headline: `assertThat(graph.staleNodes()).extracting(n -> n.id().value()).containsExactlyInAnyOrder(specId, adrOrDesId..., epicId, storyId)` (the GOAL stays APPROVED — it is upstream of the change; the edited REQ itself also stays APPROVED by design).
- Keep it ONE test method (`upstreamLoopClosesAndSurvivesRestart`) — readability via section comments, like agent-spec's `EndToEndTest`.

Steps: failing test (compile error on missing testImplementation wiring) → wire build → green → full `./gradlew build` → commit `test: closed-loop upstream E2E — intent to backlog with restart-safe propagation (Phase 1B DoD)`.

---

### Task 12: README + demo seed + DoD checklist

**Files:**
- Modify: `README.md` — "The upstream loop" section: run order (`agent-intent` → approve → `agent-spec REQ-xxxx` → approve → `agent-design SPEC-xxxx` → approve → `agent-backlog SPEC-xxxx ADR-xxxx` → approve), bootRun commands per agent module, the inbox convention, profiles recap.
- Verify `workspace/inbox/payment-notes.md` exists (Task 11 created it) with content matching the README's described demo.
- Run the FULL suite fresh: `./gradlew build --rerun-tasks` — everything green.
- Commit: `docs: upstream-loop usage and demo seed`.

---

## Phase 1B definition-of-done checklist (spec §9–§12)

- [ ] 1A carry-overs closed: rebuild→bus events (T1), proposal-branch restart visibility (T2)
- [ ] §9 Intent agent: inbox + ask_human elicitation + grounded drafts + dup/conflict flags (T3–T6)
- [ ] §10 Design agent: DES/ADR/API artifacts, ≥2 alternatives enforced, graph-context consistency (T7–T8)
- [ ] §11 Backlog agent: BacklogPort + file adapter, epics/stories/tasks with DEPENDS_ON + estimates (T9)
- [ ] Shared governance gate + three bootstraps (T10)
- [ ] §12 closed loop proven incl. restart parity (T11); docs (T12)

## Deferred (unchanged from spec §14)

Real bus, vendor PR/backlog adapters, code-reading design agent, spec-tacular import, eval harness. Plus from this plan: edge-listing port API (ORPHANED report), listByType port API (replacing the id-probe enumeration).

