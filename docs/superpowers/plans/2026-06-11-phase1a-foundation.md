# Phase 1A — Foundation Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the Phase 0 foundation for multi-agent operation: shared adapter libraries, restart-safe staleness via sha-pinned frontmatter edges, a Postgres projection behind the existing port, OpenTelemetry tracing with real cost, and git-branch-based approval (FR-SAC-2, local-first).

**Architecture:** Pure refactor-and-extend on the existing hexagonal monorepo. New `libs/adapter-*` modules implement existing `agent-core`/`traceability-graph` ports; agents keep depending on ports only. The traceability-graph library gains pinned-edge semantics but must NOT gain new dependencies (esp. not on agent-core — events flow out via `java.util.function.Consumer`). Spec: `docs/superpowers/specs/2026-06-10-phase1-design.md` §3–§8.

**Tech Stack:** Existing (Java 25, Gradle 9.5, Spring Boot 4.0.6 plugin, Spring AI 2.0.0-M4, JUnit5+AssertJ, ArchUnit 1.4.2) plus: PostgreSQL JDBC + Testcontainers, OpenTelemetry SDK, git CLI via ProcessBuilder (no JGit).

**Conventions you must follow (established in Phase 0):** records with compact-constructor invariants; fakes not mocks; `FrontmatterParser.gitBlobSha` for content shas; `yq()` single-quote YAML escaping; `String.format(Locale.ROOT, …)` for numbers in YAML; TDD with fail-first evidence; one commit per task.

---

## File structure

```
libs/adapter-common/                       NEW — FileArtifactRepository, ConsoleHumanInTheLoop,
                                                 InProcessEventPublisher, LoggingRunTrace (moved)
libs/adapter-llm-spring/                   NEW — SpringAiLanguageModel (moved), ModelPricing + pricing.yaml
libs/adapter-graph-postgres/               NEW — schema.sql, PostgresTraceabilityGraph
libs/adapter-otel/                         NEW — OtelRunTrace
libs/adapter-git/                          NEW — ProcessGitAdapter, GitArtifactRepository
libs/agent-core/                           MOD — GitPort added; RunContext (ScopedValue) added
libs/traceability-graph/                   MOD — EdgeTarget, pinned-ref parsing, rebuild staleness,
                                                 TraceabilityGraphContract (testFixtures)
agents/agent-spec/                         MOD — slimmed (adapters moved out), RevalidateArtifactUseCase,
                                                 pinned-ref writer, profile wiring, ArchUnit generalized
gradle/libs.versions.toml                  MOD — postgres, testcontainers, otel entries
README.md                                  MOD — profiles section
```

---

### Task 1: Extract `libs/adapter-common`

**Files:**
- Create: `libs/adapter-common/build.gradle.kts`
- Modify: `settings.gradle.kts` (add include)
- Move (git mv, then fix package line): from `agents/agent-spec/src/main/java/dev/sdlc/agentspec/adapters/outbound/` to `libs/adapter-common/src/main/java/dev/sdlc/adapter/common/`: `FileArtifactRepository.java`, `ConsoleHumanInTheLoop.java`, `InProcessEventPublisher.java`, `LoggingRunTrace.java`
- Move tests likewise: `FileArtifactRepositoryTest.java`, `ConsoleHumanInTheLoopTest.java` → `libs/adapter-common/src/test/java/dev/sdlc/adapter/common/`
- Modify: `agents/agent-spec/build.gradle.kts` (depend on the new lib), all agent-spec sources importing the moved classes (`EndToEndTest`, `AgentSpecApplication`)

- [ ] **Step 1: Create the module**

`libs/adapter-common/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
dependencies {
    api(project(":libs:agent-core"))           // implements its ports
    implementation(project(":libs:traceability-graph")) // gitBlobSha
}
```
In `settings.gradle.kts` add `include("libs:adapter-common")` after the agent-core include.

- [ ] **Step 2: Move the four adapters + two tests**

```bash
mkdir -p libs/adapter-common/src/main/java/dev/sdlc/adapter/common \
         libs/adapter-common/src/test/java/dev/sdlc/adapter/common
for f in FileArtifactRepository ConsoleHumanInTheLoop InProcessEventPublisher LoggingRunTrace; do
  git mv agents/agent-spec/src/main/java/dev/sdlc/agentspec/adapters/outbound/$f.java \
         libs/adapter-common/src/main/java/dev/sdlc/adapter/common/$f.java
done
for f in FileArtifactRepositoryTest ConsoleHumanInTheLoopTest; do
  git mv agents/agent-spec/src/test/java/dev/sdlc/agentspec/adapters/outbound/$f.java \
         libs/adapter-common/src/test/java/dev/sdlc/adapter/common/$f.java
done
```
In each moved file change the package line to `package dev.sdlc.adapter.common;`. Content otherwise byte-identical — do not "improve" anything in this task.

- [ ] **Step 3: Re-point agent-spec**

`agents/agent-spec/build.gradle.kts` dependencies gain:
```kotlin
    implementation(project(":libs:adapter-common"))
```
Fix imports in `agents/agent-spec/src/main/java/dev/sdlc/agentspec/bootstrap/AgentSpecApplication.java` and `agents/agent-spec/src/test/java/dev/sdlc/agentspec/bootstrap/EndToEndTest.java`: replace `dev.sdlc.agentspec.adapters.outbound.X` with `dev.sdlc.adapter.common.X` for the four moved classes (SpringAiLanguageModel import stays as-is until Task 2).

- [ ] **Step 4: Run everything**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; same 60 tests total, now split across modules (adapter-common runs FileArtifactRepositoryTest + ConsoleHumanInTheLoopTest).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: extract shared adapters into libs/adapter-common"
```

---

### Task 2: Extract `libs/adapter-llm-spring`

**Files:**
- Create: `libs/adapter-llm-spring/build.gradle.kts`
- Modify: `settings.gradle.kts` (add include)
- Move: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/adapters/outbound/SpringAiLanguageModel.java` → `libs/adapter-llm-spring/src/main/java/dev/sdlc/adapter/llm/SpringAiLanguageModel.java`
- Modify: `agents/agent-spec/build.gradle.kts` (spring-ai deps and milestone repo move OUT; depend on new lib), `gradle/libs.versions.toml` untouched
- Modify imports: `AgentSpecApplication.java`, `OpenAiCompatIntegrationTest.java`

- [ ] **Step 1: Create the module**

`libs/adapter-llm-spring/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
repositories {
    mavenCentral()
    maven("https://repo.spring.io/milestone") { // Spring AI 2.0 milestones; drop once 2.0 GA is out
        content { includeGroupByRegex("org\\.springframework\\.ai.*") }
    }
}
dependencies {
    api(project(":libs:agent-core"))
    implementation(libs.spring.ai.anthropic)
    implementation(libs.spring.ai.openai)
    constraints {
        // anthropic-java pulls httpclient5:5.3.1; Boot 4.1's HttpComponents request factory
        // (used by the OpenAI RestClient) needs 5.4+ (TlsSocketStrategy). Align with Boot's BOM.
        implementation(libs.httpclient5)
    }
}
```
In `settings.gradle.kts` add `include("libs:adapter-llm-spring")`.

- [ ] **Step 2: Move the adapter**

```bash
mkdir -p libs/adapter-llm-spring/src/main/java/dev/sdlc/adapter/llm
git mv agents/agent-spec/src/main/java/dev/sdlc/agentspec/adapters/outbound/SpringAiLanguageModel.java \
       libs/adapter-llm-spring/src/main/java/dev/sdlc/adapter/llm/SpringAiLanguageModel.java
```
Change its package line to `package dev.sdlc.adapter.llm;`. The now-empty `agents/agent-spec/.../adapters/` directory tree is removed (`git` handles it via the moves).

- [ ] **Step 3: Re-point agent-spec build**

In `agents/agent-spec/build.gradle.kts`: remove `implementation(libs.spring.ai.anthropic)`, `implementation(libs.spring.ai.openai)`, the `constraints { implementation(libs.httpclient5) }` block, and the milestone `maven(...)` repository block (keep `mavenCentral()` — and keep the repositories block itself only if anything else needs it; if it shrinks to just mavenCentral, keep it as-is since the convention plugin already declares mavenCentral and the explicit block is harmless duplication — remove the whole `repositories { ... }` block for cleanliness). Add:
```kotlin
    implementation(project(":libs:adapter-llm-spring"))
```
Spring Boot starter + starter-test stay in agent-spec (bootstrap-only). Fix imports: `AgentSpecApplication.java` and `OpenAiCompatIntegrationTest.java` now import `dev.sdlc.adapter.llm.SpringAiLanguageModel`.

- [ ] **Step 4: Run everything**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green (OpenAiCompatIntegrationTest still boots the context — autoconfiguration comes from the transitive starter via adapter-llm-spring).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: extract spring-ai adapter into libs/adapter-llm-spring"
```

### Task 3: Generalize the ArchUnit rules

**Files:**
- Modify: `agents/agent-spec/src/test/java/dev/sdlc/agentspec/HexagonalArchitectureTest.java`

- [ ] **Step 1: Replace the rule set**

The rules must now (a) cover every current AND future agent module (`dev.sdlc.agentspec`, `dev.sdlc.agentintent`, `dev.sdlc.agentdesign`, `dev.sdlc.agentbacklog` — Phase 1B), (b) recognize `dev.sdlc.adapter..` as the only framework-touching zone besides agent `bootstrap`/`adapters` packages:

```java
package dev.sdlc.agentspec;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "dev.sdlc")
class HexagonalArchitectureTest {

    private static final String[] FRAMEWORK_PACKAGES = {
            "org.springframework..", "org.springframework.ai..", "dev.langchain4j..",
            "io.opentelemetry..", "org.postgresql..", "org.yaml.."};

    // any agent's domain: no app/adapters/bootstrap/framework deps
    @ArchTest
    static final ArchRule agentDomainsAreInnermost = noClasses()
            .that().resideInAPackage("dev.sdlc.agent*.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(merge(
                    new String[]{"..application..", "..adapters..", "..bootstrap.."},
                    FRAMEWORK_PACKAGES));

    // any agent's application: ports only — no adapters/bootstrap/framework
    @ArchTest
    static final ArchRule agentApplicationsUsePortsOnly = noClasses()
            .that().resideInAPackage("dev.sdlc.agent*.application..")
            .should().dependOnClassesThat().resideInAnyPackage(merge(
                    new String[]{"..adapters..", "..bootstrap.."},
                    FRAMEWORK_PACKAGES));

    // core libs stay framework-free (snakeyaml is the one sanctioned exception in dev.sdlc.trace)
    @ArchTest
    static final ArchRule coreLibsAreFrameworkFree = noClasses()
            .that().resideInAnyPackage("dev.sdlc.domain..", "dev.sdlc.agent.port..", "dev.sdlc.agent")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.springframework.ai..", "dev.langchain4j..",
                    "io.opentelemetry..", "org.postgresql..");

    // adapter libs may use frameworks but never depend on agent modules
    @ArchTest
    static final ArchRule adaptersNeverDependOnAgents = noClasses()
            .that().resideInAPackage("dev.sdlc.adapter..")
            .should().dependOnClassesThat().resideInAPackage("dev.sdlc.agent*..");

    private static String[] merge(String[] a, String[] b) {
        var out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
```

Notes for the implementer: ArchUnit package-identifier wildcards — verify `dev.sdlc.agent*.domain..` actually matches `dev.sdlc.agentspec.domain` with ArchUnit 1.4.2 (the `*` matches within one package segment). If it does not, fall back to explicit enumeration of the four agent roots in a String[] constant — correctness beats cleverness. `dev.sdlc.agent` (no `..`) in rule 3 covers agent-core's root package classes (AgentLoop etc.); `dev.sdlc.agent.port..` its ports; deliberately NOT `dev.sdlc.agent..` because that would also match `dev.sdlc.agentspec..` (prefix matching) — verify this concern empirically too and adjust so agent-core is covered without dragging agent modules into rule 3.

- [ ] **Step 2: Planted-violation verification (vacuous rules bit us before — archunit 1.3.0 silently analyzed zero Java 25 classes)**

```bash
cat > agents/agent-spec/src/main/java/dev/sdlc/agentspec/domain/Sneaky.java <<'EOF'
package dev.sdlc.agentspec.domain;
class Sneaky { org.springframework.boot.SpringApplication s = null; }
EOF
./gradlew :agents:agent-spec:test --tests HexagonalArchitectureTest   # MUST FAIL (agentDomainsAreInnermost)
rm agents/agent-spec/src/main/java/dev/sdlc/agentspec/domain/Sneaky.java
```
Wait — domain has no Spring on its compile classpath, so that file won't compile (which is itself the build-level guard). Use a violation that compiles instead: plant in domain a dependency on an application class:
```bash
cat > agents/agent-spec/src/main/java/dev/sdlc/agentspec/domain/Sneaky.java <<'EOF'
package dev.sdlc.agentspec.domain;
class Sneaky { Object o = dev.sdlc.agentspec.application.SpecDraftParser.class; }
EOF
./gradlew :agents:agent-spec:test --tests HexagonalArchitectureTest 2>&1 | grep -c "agentDomainsAreInnermost FAILED"   # expect 1
rm agents/agent-spec/src/main/java/dev/sdlc/agentspec/domain/Sneaky.java
```

- [ ] **Step 3: Run clean, expect PASS (4 rules), then full build**

Run: `./gradlew :agents:agent-spec:test --tests HexagonalArchitectureTest` then `./gradlew build`
Expected: 4 testcases, 0 failures; full build green.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "test: generalize archunit rules for multi-agent + adapter libs"
```

---

### Task 4: Pinned edge refs in the parser (`REQ-0012@<sha>`)

**Files:**
- Create: `libs/traceability-graph/src/main/java/dev/sdlc/trace/EdgeTarget.java`
- Modify: `libs/traceability-graph/src/main/java/dev/sdlc/trace/ArtifactFile.java`, `FrontmatterParser.java`, `ProjectionBuilder.java`
- Test: extend `libs/traceability-graph/src/test/java/dev/sdlc/trace/FrontmatterParserTest.java`; adjust `ProjectionBuilderTest.java`; adjust `agents/agent-spec/src/test/java/dev/sdlc/agentspec/application/GenerateSpecificationUseCaseTest.java` (round-trip assertion)

- [ ] **Step 1: Write the failing test**

Add to `FrontmatterParserTest`:
```java
    @Test
    void parsesPinnedEdgeRefs() {
        var artifact = new FrontmatterParser().parse("""
                ---
                id: SPEC-0001
                type: Specification
                title: t
                status: DRAFT
                derivesFrom: ['REQ-0012@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', UC-0003]
                provenance:
                  sourceRefs: [x]
                  generatedBy: h
                  confidence: 1.0
                  assumptions: []
                  humanApproved: false
                ---
                body
                """, "s.md");
        assertThat(artifact.edgeTargets().get(EdgeType.DERIVES_FROM)).containsExactly(
                new EdgeTarget(ArtifactId.of("REQ-0012"), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                new EdgeTarget(ArtifactId.of("UC-0003"), null));
    }

    @Test
    void rejectsMalformedPin() {
        assertThatThrownBy(() -> new FrontmatterParser().parse("""
                ---
                id: SPEC-0001
                type: Specification
                title: t
                status: DRAFT
                derivesFrom: ['REQ-0012@nothex']
                provenance:
                  sourceRefs: [x]
                  generatedBy: h
                  confidence: 1.0
                  assumptions: []
                  humanApproved: false
                ---
                body
                """, "s.md"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("s.md");
    }
```
Existing assertions referencing `edgeTargets()` values as `ArtifactId` lists must be updated in the same step (they'll fail to compile — that IS the failing state): in `FrontmatterParserTest.parsesNodeAndEdgesFromFrontmatter` expect `EdgeTarget(...id..., null)`; in `GenerateSpecificationUseCaseTest.writtenFileRoundTripsThroughFrontmatterParserEvenWithHostileTitle` the final assertion becomes (pin value asserted in Task 5; for now):
```java
        assertThat(reparsed.edgeTargets().get(EdgeType.DERIVES_FROM))
                .extracting(EdgeTarget::id).containsExactly(ArtifactId.of("REQ-0012"));
```
(add `import dev.sdlc.trace.EdgeTarget;`)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :libs:traceability-graph:test --tests FrontmatterParserTest`
Expected: FAIL — `EdgeTarget` does not exist / compile errors.

- [ ] **Step 3: Implement**

`EdgeTarget.java`:
```java
package dev.sdlc.trace;

import dev.sdlc.domain.ArtifactId;
import java.util.regex.Pattern;

/** An upstream reference from frontmatter: target id plus the optionally pinned blob sha. */
public record EdgeTarget(ArtifactId id, String pinnedSha) {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    public EdgeTarget {
        if (pinnedSha != null && !SHA.matcher(pinnedSha).matches())
            throw new IllegalArgumentException("pinned sha must be 40 hex chars: " + pinnedSha);
    }

    /** Parses "REQ-0012" or "REQ-0012@<sha>". */
    public static EdgeTarget parse(String raw) {
        int at = raw.indexOf('@');
        if (at < 0) return new EdgeTarget(ArtifactId.of(raw), null);
        return new EdgeTarget(ArtifactId.of(raw.substring(0, at)), raw.substring(at + 1));
    }

    public String render() { return pinnedSha == null ? id.value() : id.value() + "@" + pinnedSha; }
}
```

`ArtifactFile.java` — change the map value type:
```java
package dev.sdlc.trace;

import dev.sdlc.domain.EdgeType;
import java.util.List;
import java.util.Map;

/** A parsed spec-as-code file: the node plus the upstream refs it links to. */
public record ArtifactFile(Node node, Map<EdgeType, List<EdgeTarget>> edgeTargets, String body) {}
```

`FrontmatterParser.java` — replace `putEdges` and its call sites' generic type:
```java
    private static void putEdges(Map<EdgeType, List<EdgeTarget>> map, EdgeType type, Object raw) {
        var targets = strings(raw).stream().map(EdgeTarget::parse).toList();
        if (!targets.isEmpty()) map.put(type, targets);
    }
```
and in `toArtifact`: `Map<EdgeType, List<EdgeTarget>> edges = new EnumMap<>(EdgeType.class);` (imports unchanged; `ArtifactId` import may become unused in putEdges — keep the class compiling cleanly).

`ProjectionBuilder.java` — the edge loop adapts (staleness logic arrives in Task 6; here just keep current behavior, resolving the linked sha from the pin when present):
```java
            for (var artifact : artifacts)
                artifact.edgeTargets().forEach((type, targets) -> targets.forEach(target -> {
                    var currentSha = graph.get(target.id()).map(Node::blobSha).orElse("unknown");
                    var linkedSha = target.pinnedSha() != null ? target.pinnedSha() : currentSha;
                    graph.link(Edge.current(type, artifact.node().id(), target.id(), linkedSha,
                            "projection", Instant.now()));
                }));
```
`ProjectionBuilderTest` needs no assertion changes (it asserts node presence + downstreamOf only).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :libs:traceability-graph:test && ./gradlew :agents:agent-spec:test`
Expected: all green (16 + 24 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(traceability-graph): pinned edge refs (id@sha) in frontmatter"
```

---

### Task 5: Writers emit pinned refs; approval preserves them

**Files:**
- Modify: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/application/GenerateSpecificationUseCase.java` (renderFile)
- Test: extend `GenerateSpecificationUseCaseTest` and `ApproveArtifactUseCaseTest`

- [ ] **Step 1: Write the failing tests**

In `GenerateSpecificationUseCaseTest`: the fixture requirement's blobSha is the literal `"r1"`, which is not a valid 40-hex pin — first add
```java
    static final String R1_SHA = "a".repeat(40);
```
and replace `"r1"` with `R1_SHA` in `approvedReq()` and in the provenance assertion (`contains("REQ-0012@" + R1_SHA)`). Then strengthen the round-trip test's edge assertion (replacing the Task 4 interim form):
```java
        assertThat(reparsed.edgeTargets().get(EdgeType.DERIVES_FROM))
                .containsExactly(new EdgeTarget(ArtifactId.of("REQ-0012"), R1_SHA));
```

In `ApproveArtifactUseCaseTest`, the FILE fixture's `derivesFrom` becomes pinned, and a new assertion proves approval preserves it:
```java
    // in FILE constant:            derivesFrom: ['REQ-0012@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa']
    @Test
    void approvalPreservesPinnedRefs() {
        new ApproveArtifactUseCase(graph, repo, decide(true, null), () -> T0).review(specId);
        assertThat(files.get("specs/SPEC-0001.md"))
                .contains("derivesFrom: ['REQ-0012@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa']");
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :agents:agent-spec:test --tests GenerateSpecificationUseCaseTest --tests ApproveArtifactUseCaseTest`
Expected: round-trip test FAILS (writer emits bare `[REQ-0012]`); the preserve test PASSES already (replaceFirst only touches status/humanApproved lines) — it's a regression pin, note that honestly.

- [ ] **Step 3: Implement the writer change**

In `GenerateSpecificationUseCase.renderFile`, the `derives` line changes from joining bare ids to pinned, quoted refs. The method needs the source nodes' shas — it already receives only the draft; the pins equal each source's `blobSha` which `generate()` has as `sources`. Pass them: change the call to `renderFile(draft, provenance, parsed.report(), sources)` and the signature/derives accordingly:
```java
    private String renderFile(SpecificationDraft draft, Provenance prov, TestabilityReport report,
                              List<Node> sources) {
        String derives = sources.stream()
                .map(src -> yq(src.id().value() + "@" + src.blobSha()))
                .collect(Collectors.joining(", ", "[", "]"));
        // ... rest of the method unchanged (refs, assumptions, flags, format template)
    }
```
(`draft.derivesFrom()` and `sources` ids are identical by construction — `generate()` builds both from `requirementIds`.)

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :agents:agent-spec:test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(agent-spec): emit sha-pinned derivesFrom refs; pin approval regression"
```

### Task 6: Rebuild computes staleness from pins (restart-safe FR-TRACE-2)

**Files:**
- Modify: `libs/traceability-graph/src/main/java/dev/sdlc/trace/ProjectionBuilder.java`
- Test: extend `libs/traceability-graph/src/test/java/dev/sdlc/trace/ProjectionBuilderTest.java`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void rebuildFlagsStalenessFromPinnedShas(@TempDir Path dir) throws Exception {
        String req = """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                EDITED while the system was down
                """;
        Files.writeString(dir.resolve("REQ-0012.md"), req);
        // spec pins a sha that is NOT the current content's sha → must come up stale
        String stalePin = "b".repeat(40);
        Files.writeString(dir.resolve("SPEC-0007.md"), """
                ---
                id: SPEC-0007
                type: Specification
                title: Spec
                status: APPROVED
                derivesFrom: ['REQ-0012@%s']
                provenance:
                  sourceRefs: ['REQ-0012@%s']
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """.formatted(stalePin, stalePin));

        var graph = new InMemoryTraceabilityGraph();
        var events = new java.util.ArrayList<dev.sdlc.domain.event.RevalidationRequested>();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph, events::add);

        assertThat(graph.get(ArtifactId.of("SPEC-0007")).orElseThrow().status())
                .isEqualTo(dev.sdlc.domain.NodeStatus.NEEDS_REVALIDATION);
        assertThat(graph.staleNodes()).extracting(n -> n.id().value()).containsExactly("SPEC-0007");
        assertThat(events).extracting(e -> e.subject().value()).containsExactly("SPEC-0007");
    }

    @Test
    void rebuildWithMatchingPinsStaysClean(@TempDir Path dir) throws Exception {
        String req = """
                ---
                id: REQ-0012
                type: Requirement
                title: Regional tax
                status: APPROVED
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: human
                  confidence: 1.0
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """;
        Files.writeString(dir.resolve("REQ-0012.md"), req);
        String pin = FrontmatterParser.gitBlobSha(req);
        Files.writeString(dir.resolve("SPEC-0007.md"), """
                ---
                id: SPEC-0007
                type: Specification
                title: Spec
                status: APPROVED
                derivesFrom: ['REQ-0012@%s']
                provenance:
                  sourceRefs: ['REQ-0012@%s']
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: true
                  approvedBy: a.dupont
                ---
                body
                """.formatted(pin, pin));

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph);
        assertThat(graph.staleNodes()).isEmpty();
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :libs:traceability-graph:test --tests ProjectionBuilderTest`
Expected: FAIL — no 3-arg `rebuild` overload; edges currently always CURRENT.

- [ ] **Step 3: Implement**

`ProjectionBuilder.java` full replacement:
```java
package dev.sdlc.trace;

import dev.sdlc.domain.NodeStatus;
import dev.sdlc.domain.event.RevalidationRequested;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Rebuilds the projection from artifact files; runs at startup and after writes.
 * Pinned refs (id@sha) make staleness restart-safe: a pin that no longer matches the
 * upstream's current sha is recreated as a STALE edge and the owner flagged, exactly
 * as a live applyChange would have done (FR-TRACE-2). traceability-graph must not
 * depend on agent-core, so events surface through a plain Consumer.
 */
public final class ProjectionBuilder {
    private final FrontmatterParser parser;

    public ProjectionBuilder(FrontmatterParser parser) { this.parser = parser; }

    public void rebuild(Path artifactRoot, TraceabilityGraphPort graph) {
        rebuild(artifactRoot, graph, e -> { });
    }

    public void rebuild(Path artifactRoot, TraceabilityGraphPort graph,
                        Consumer<RevalidationRequested> onStale) {
        List<ArtifactFile> artifacts;
        try (Stream<Path> files = Files.walk(artifactRoot)) {
            artifacts = files.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> {
                        try { return parser.parse(Files.readString(p), artifactRoot.relativize(p).toString()); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }).toList();
        } catch (IOException e) { throw new UncheckedIOException(e); }

        artifacts.forEach(a -> graph.upsert(a.node()));
        for (var artifact : artifacts)
            artifact.edgeTargets().forEach((type, targets) -> targets.forEach(target -> {
                var upstreamSha = graph.get(target.id()).map(Node::blobSha).orElse("unknown");
                var pinned = target.pinnedSha();
                boolean stale = pinned != null && !pinned.equals(upstreamSha);
                var linkedSha = pinned != null ? pinned : upstreamSha;
                var edge = Edge.current(type, artifact.node().id(), target.id(), linkedSha,
                        "projection", Instant.now());
                graph.link(stale ? edge.withStatus(dev.sdlc.domain.LinkStatus.STALE) : edge);
                if (stale) flag(graph, artifact.node().id(), target.id(), onStale);
            }));
    }

    private void flag(TraceabilityGraphPort graph, dev.sdlc.domain.ArtifactId owner,
                      dev.sdlc.domain.ArtifactId changedUpstream,
                      Consumer<RevalidationRequested> onStale) {
        graph.get(owner).filter(n -> n.status() != NodeStatus.NEEDS_REVALIDATION).ifPresent(n -> {
            graph.upsert(n.withStatus(NodeStatus.NEEDS_REVALIDATION, n.provenance(), Instant.now()));
            onStale.accept(new RevalidationRequested(owner, List.of(changedUpstream)));
        });
    }
}
```

- [ ] **Step 4: Run module + full build**

Run: `./gradlew :libs:traceability-graph:test && ./gradlew build`
Expected: green (the agent-spec E2E's final rebuilt-graph step still passes: its pins match because the spec was written from the then-current REQ sha and the E2E's step-3 edit happens through `applyChange` on the ORIGINAL graph — the step-4 rebuilt graph now sees the pin≠current mismatch and flags SPEC stale at rebuild, making `events2` assertions still hold; if the E2E's `staleNodes` expectations shift because flagging now happens during rebuild rather than the explicit applyChange, adjust the E2E to assert the stronger property: after rebuild the spec is ALREADY `NEEDS_REVALIDATION` without any applyChange call — that is the entire point of this task; rewrite E2E step 4 accordingly and say so in the commit).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(traceability-graph): restart-safe staleness from pinned refs at rebuild"
```

---

### Task 7: `RevalidateArtifactUseCase` rewrites the pin

**Files:**
- Create: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/application/RevalidateArtifactUseCase.java`
- Test: `agents/agent-spec/src/test/java/dev/sdlc/agentspec/application/RevalidateArtifactUseCaseTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.sdlc.agentspec.application;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.domain.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RevalidateArtifactUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    static final String OLD_SHA = "a".repeat(40);

    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();
    Map<String, String> files = new HashMap<>();
    ArtifactRepositoryPort repo = new ArtifactRepositoryPort() {
        public String write(String path, String content) {
            files.put(path, content);
            return FrontmatterParser.gitBlobSha(content);
        }
        public Optional<String> read(String path) { return Optional.ofNullable(files.get(path)); }
    };

    @BeforeEach
    void staleSpec() {
        String reqContent = "---\nid: REQ-0012\ntype: Requirement\ntitle: r\nstatus: APPROVED\n"
                + "provenance:\n  sourceRefs: [t]\n  generatedBy: h\n  confidence: 1.0\n"
                + "  assumptions: []\n  humanApproved: true\n  approvedBy: a.dupont\n---\nnew content\n";
        files.put("requirements/REQ-0012.md", reqContent);
        String specContent = ("---\nid: SPEC-0001\ntype: Specification\ntitle: s\nstatus: APPROVED\n"
                + "derivesFrom: ['REQ-0012@%s']\n"
                + "provenance:\n  sourceRefs: ['REQ-0012@%s']\n  generatedBy: agent-spec@v1\n"
                + "  confidence: 0.8\n  assumptions: []\n  humanApproved: true\n  approvedBy: a.dupont\n"
                + "---\nbody\n").formatted(OLD_SHA, OLD_SHA);
        files.put("specs/SPEC-0001.md", specContent);
        // projection state mirroring a rebuild that found the pin stale
        new ProjectionBuilder(new FrontmatterParser()).rebuild(
                new InMemoryFs(files).asPath(), graph);
    }

    // NOTE to implementer: the ProjectionBuilder works on real Paths; instead of inventing an
    // in-memory FS, write the two files into a @TempDir in @BeforeEach and rebuild from it.
    // Restructure the fixture accordingly (keep `files` map in sync by reading back from disk
    // for the repo fake, or simpler: make the repo fake delegate to the temp dir).

    @Test
    void revalidationRestampsPinAndClearsFlag(@TempDir java.nio.file.Path dir) {
        // (fixture per the note above; assertions below are the contract)
        var useCase = new RevalidateArtifactUseCase(graph, repo, () -> T0);
        useCase.revalidate(ArtifactId.of("SPEC-0001"), ArtifactId.of("REQ-0012"), "a.dupont");

        // file pin updated to the upstream's CURRENT sha
        String reqSha = FrontmatterParser.gitBlobSha(files.get("requirements/REQ-0012.md"));
        assertThat(files.get("specs/SPEC-0001.md")).contains("REQ-0012@" + reqSha);
        // graph: edge CURRENT again, node restored (APPROVED — provenance was approved)
        assertThat(graph.staleNodes()).isEmpty();
        assertThat(graph.get(ArtifactId.of("SPEC-0001")).orElseThrow().status())
                .isEqualTo(NodeStatus.APPROVED);
        // node sha matches the rewritten file
        assertThat(graph.get(ArtifactId.of("SPEC-0001")).orElseThrow().blobSha())
                .isEqualTo(FrontmatterParser.gitBlobSha(files.get("specs/SPEC-0001.md")));
    }
}
```
The implementer fixes the fixture per the inline note (use @TempDir + a FileArtifactRepository from adapter-common — agent-spec already depends on it; that exercises the real read/write path). The three assertion blocks are the contract and must remain.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :agents:agent-spec:test --tests RevalidateArtifactUseCaseTest`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement**

```java
package dev.sdlc.agentspec.application;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.trace.Node;
import dev.sdlc.trace.TraceabilityGraphPort;

import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human re-validates a stale derives-from link: the downstream artifact's pinned ref is
 * re-stamped to the upstream's current sha in the FILE (canonical), then the graph edge is
 * revalidated. The file rewrite is a metadata change — no propagation is triggered.
 */
public final class RevalidateArtifactUseCase {
    private final TraceabilityGraphPort graph;
    private final ArtifactRepositoryPort repo;
    private final Supplier<Instant> clock;

    public RevalidateArtifactUseCase(TraceabilityGraphPort graph, ArtifactRepositoryPort repo,
                                     Supplier<Instant> clock) {
        this.graph = graph; this.repo = repo; this.clock = clock;
    }

    public void revalidate(ArtifactId downstream, ArtifactId upstream, String validatedBy) {
        var down = graph.get(downstream).orElseThrow(() ->
                new IllegalStateException("unknown node " + downstream));
        var up = graph.get(upstream).orElseThrow(() ->
                new IllegalStateException("unknown node " + upstream));

        var content = repo.read(down.repoPath()).orElseThrow(() -> new IllegalStateException(
                "canonical artifact file missing: " + down.repoPath()));
        var rewritten = content.replaceAll(
                Pattern.quote(upstream.value()) + "@[0-9a-f]{40}",
                Matcher.quoteReplacement(upstream.value() + "@" + up.blobSha()));
        var now = clock.get();
        Node updated = down;
        if (!rewritten.equals(content)) {
            var sha = repo.write(down.repoPath(), rewritten);
            updated = down.withContentChange(sha, now);
        }
        graph.upsert(updated);
        graph.revalidate(downstream.value() + "->" + upstream.value() + ":DERIVES_FROM", validatedBy);
    }
}
```
Note the ordering: upsert the sha-updated node BEFORE `graph.revalidate`, because revalidate's flag-clearing reads the node and would otherwise restore a stale-sha'd node (then our upsert would overwrite the restored status). Verify with the test; if the status restored by `revalidate` is lost by a later upsert in your implementation order, fetch the node fresh after `revalidate` and apply `withContentChange` to THAT instance instead — the test's three assertions arbitrate.

- [ ] **Step 4: Run to verify pass, then full build**

Run: `./gradlew :agents:agent-spec:test && ./gradlew build`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(agent-spec): revalidation re-stamps pinned refs in canonical files"
```

### Task 8: Extract `TraceabilityGraphContract` (shared port contract tests)

**Files:**
- Modify: `libs/traceability-graph/build.gradle.kts` (add `java-test-fixtures` plugin)
- Create: `libs/traceability-graph/src/testFixtures/java/dev/sdlc/trace/TraceabilityGraphContract.java`
- Modify: `libs/traceability-graph/src/test/java/dev/sdlc/trace/ChangePropagationTest.java` (becomes the in-memory binding)

- [ ] **Step 1: Add test fixtures and the contract**

`libs/traceability-graph/build.gradle.kts` plugins block becomes:
```kotlin
plugins {
    id("sdlc.java-conventions")
    `java-test-fixtures`
}
```
testFixtures need assertj/junit on their compile classpath:
```kotlin
dependencies {
    api(project(":libs:domain-shared"))
    implementation(libs.snakeyaml) // used by the parser
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj)
}
```

`TraceabilityGraphContract.java` — move the SIX existing `ChangePropagationTest` test methods verbatim into an abstract class, with the graph supplied by subclasses:
```java
package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral contract every TraceabilityGraphPort adapter must satisfy.
 * Subclasses provide the adapter (in-memory, postgres, ...).
 */
public abstract class TraceabilityGraphContract {
    protected static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");
    protected static final Provenance PROV = Provenance.generated(List.of("doc:x"), "test", 1.0, List.of());

    protected TraceabilityGraphPort graph;

    protected abstract TraceabilityGraphPort newGraph();

    protected Node node(String id, NodeType type, NodeStatus status, String sha) {
        var prov = status == NodeStatus.APPROVED ? PROV.approve("tester", T0) : PROV;
        return new Node(ArtifactId.of(id), type, id, id + ".md", sha, status, 1, prov, T0, T0);
    }

    @BeforeEach
    void chain() { // GOAL-0001 <- REQ-0012 <- SPEC-0007 <- STORY-0042
        graph = newGraph();
        graph.upsert(node("GOAL-0001", NodeType.GOAL, NodeStatus.APPROVED, "g1"));
        graph.upsert(node("REQ-0012", NodeType.REQUIREMENT, NodeStatus.APPROVED, "r1"));
        graph.upsert(node("SPEC-0007", NodeType.SPECIFICATION, NodeStatus.APPROVED, "s1"));
        graph.upsert(node("STORY-0042", NodeType.BACKLOG_ITEM, NodeStatus.APPROVED, "b1"));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("REQ-0012"), ArtifactId.of("GOAL-0001"), "g1", "test", T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("SPEC-0007"), ArtifactId.of("REQ-0012"), "r1", "test", T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("STORY-0042"), ArtifactId.of("SPEC-0007"), "s1", "test", T0));
    }

    // ... the six @Test methods from ChangePropagationTest, UNCHANGED except that the
    //     `graph` field is now the contract's protected field (it already is named `graph`):
    //     upstreamChangeMarksDirectDependentStaleAndRecursesTransitively
    //     impactOfAnswersTheHeadlineQuery
    //     revalidationRestampsEdgeToCurrent
    //     revalidatingEdgeOfFormerlyProposedNodeDemotesToDraftInsteadOfCrashing
    //     revalidationClearsTransitivelyFlaggedDependentsWithNoStaleEdges
    //     applyChangeWithUnchangedShaIsAFullNoOp
    //     COPY THEM VERBATIM from ChangePropagationTest — do not retype from memory.
}
```

`ChangePropagationTest.java` shrinks to the binding:
```java
package dev.sdlc.trace;

class ChangePropagationTest extends TraceabilityGraphContract {
    @Override protected TraceabilityGraphPort newGraph() { return new InMemoryTraceabilityGraph(); }
}
```

- [ ] **Step 2: Run**

Run: `./gradlew :libs:traceability-graph:test`
Expected: same test count as before (6 contract tests now reported under ChangePropagationTest + the parser/builder/node tests), all green. Full `./gradlew build` green.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "refactor(traceability-graph): extract port contract tests into test fixtures"
```

---

### Task 9: `libs/adapter-graph-postgres`

**Files:**
- Modify: `gradle/libs.versions.toml` (postgres driver, testcontainers)
- Create: `libs/adapter-graph-postgres/build.gradle.kts`
- Modify: `settings.gradle.kts` (include)
- Create: `libs/adapter-graph-postgres/src/main/resources/dev/sdlc/adapter/graph/schema.sql`
- Create: `libs/adapter-graph-postgres/src/main/java/dev/sdlc/adapter/graph/PostgresTraceabilityGraph.java`
- Test: `libs/adapter-graph-postgres/src/test/java/dev/sdlc/adapter/graph/PostgresTraceabilityGraphTest.java`

- [ ] **Step 1: Catalog + module**

`gradle/libs.versions.toml` additions:
```toml
# [versions]
postgresql = "42.7.7"
testcontainers = "1.21.3"
# [libraries]
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
testcontainers-postgresql = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
testcontainers-junit = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
```
(If these exact patch versions no longer resolve, use the latest stable of each line and report.)

`libs/adapter-graph-postgres/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
dependencies {
    api(project(":libs:traceability-graph"))
    implementation(libs.jakarta.json)       // provenance jsonb (de)serialization
    runtimeOnly(libs.parsson)
    implementation(libs.postgresql)
    testImplementation(testFixtures(project(":libs:traceability-graph")))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
```
`settings.gradle.kts`: add `include("libs:adapter-graph-postgres")`.

- [ ] **Step 2: schema.sql (spec §12.8)**

```sql
CREATE TABLE IF NOT EXISTS nodes (
    id          varchar(16) PRIMARY KEY,
    type        varchar(32)  NOT NULL,
    title       text,
    repo_path   text         NOT NULL,
    blob_sha    varchar(64)  NOT NULL,
    status      varchar(32)  NOT NULL,
    version     int          NOT NULL,
    provenance  jsonb        NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);
CREATE TABLE IF NOT EXISTS edges (
    id                          varchar(64) PRIMARY KEY,
    type                        varchar(32) NOT NULL,
    from_id                     varchar(16) NOT NULL,
    to_id                       varchar(16) NOT NULL,
    upstream_blob_sha_at_link   varchar(64) NOT NULL,
    link_status                 varchar(16) NOT NULL,
    established_by              text        NOT NULL,
    validated_at                timestamptz,
    validated_by                text
);
CREATE INDEX IF NOT EXISTS idx_edges_to   ON edges (to_id, link_status);
CREATE INDEX IF NOT EXISTS idx_edges_from ON edges (from_id);
CREATE INDEX IF NOT EXISTS idx_nodes_status ON nodes (status);
```
No FK constraints: dangling frontmatter refs are legal in the domain (parser/builder tolerate them); the projection must too.

- [ ] **Step 3: Write the failing test (the contract does the heavy lifting)**

```java
package dev.sdlc.adapter.graph;

import dev.sdlc.domain.ArtifactId;
import dev.sdlc.trace.TraceabilityGraphContract;
import dev.sdlc.trace.TraceabilityGraphPort;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresTraceabilityGraphTest extends TraceabilityGraphContract {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Override
    protected TraceabilityGraphPort newGraph() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        var graph = new PostgresTraceabilityGraph(ds);
        graph.initSchema();
        graph.truncate(); // fresh state per test (container is shared across the class)
        return graph;
    }

    @Test
    void impactOfStaysInteractiveAtOneThousandNodes() {
        // NFR-PERF smoke: chain GOAL <- REQ-1..1000, each deriving from the previous
        var g = graph;
        for (int i = 1; i <= 1000; i++) {
            var id = String.format("PERF-%04d", i);
            g.upsert(node(id, dev.sdlc.domain.NodeType.REQUIREMENT,
                    dev.sdlc.domain.NodeStatus.DRAFT, "s" + i));
            if (i > 1)
                g.link(dev.sdlc.trace.Edge.current(dev.sdlc.domain.EdgeType.DERIVES_FROM,
                        ArtifactId.of(id), ArtifactId.of(String.format("PERF-%04d", i - 1)),
                        "s" + (i - 1), "test", T0));
        }
        long start = System.nanoTime();
        var impacted = g.impactOf(ArtifactId.of("PERF-0001"));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(impacted).hasSize(999);
        assertThat(ms).isLessThan(200);
    }
}
```

Run: `./gradlew :libs:adapter-graph-postgres:test` → FAIL (class missing). Requires Docker; if the daemon isn't running, report BLOCKED rather than stubbing.

- [ ] **Step 4: Implement `PostgresTraceabilityGraph`**

```java
package dev.sdlc.adapter.graph;

import dev.sdlc.domain.*;
import dev.sdlc.domain.event.RevalidationRequested;
import dev.sdlc.trace.*;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import javax.sql.DataSource;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Postgres projection (spec §12.8). Same behavioral contract as the in-memory adapter —
 * enforced by TraceabilityGraphContract. Plain JDBC; one transaction per mutating call.
 */
public final class PostgresTraceabilityGraph implements TraceabilityGraphPort {
    private final DataSource ds;

    public PostgresTraceabilityGraph(DataSource ds) { this.ds = ds; }

    public void initSchema() {
        try (var in = PostgresTraceabilityGraph.class.getResourceAsStream(
                "/dev/sdlc/adapter/graph/schema.sql");
             var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("schema init failed", e); }
    }

    /** Test hook: wipe both tables. */
    public void truncate() {
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("TRUNCATE nodes, edges");
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public Optional<Node> get(ArtifactId id) {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("SELECT * FROM nodes WHERE id = ?")) {
            ps.setString(1, id.value());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readNode(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public void upsert(Node n) {
        try (var c = ds.getConnection()) { upsert(c, n); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private void upsert(Connection c, Node n) throws SQLException {
        try (var ps = c.prepareStatement("""
                INSERT INTO nodes (id, type, title, repo_path, blob_sha, status, version,
                                   provenance, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)
                ON CONFLICT (id) DO UPDATE SET type=EXCLUDED.type, title=EXCLUDED.title,
                  repo_path=EXCLUDED.repo_path, blob_sha=EXCLUDED.blob_sha,
                  status=EXCLUDED.status, version=EXCLUDED.version,
                  provenance=EXCLUDED.provenance, updated_at=EXCLUDED.updated_at""")) {
            ps.setString(1, n.id().value());
            ps.setString(2, n.type().name());
            ps.setString(3, n.title());
            ps.setString(4, n.repoPath());
            ps.setString(5, n.blobSha());
            ps.setString(6, n.status().name());
            ps.setInt(7, n.version());
            ps.setString(8, provenanceJson(n.provenance()));
            ps.setTimestamp(9, Timestamp.from(n.createdAt()));
            ps.setTimestamp(10, Timestamp.from(n.updatedAt()));
            ps.executeUpdate();
        }
    }

    @Override public void link(Edge e) {
        try (var c = ds.getConnection()) { link(c, e); }
        catch (SQLException ex) { throw new IllegalStateException(ex); }
    }

    private void link(Connection c, Edge e) throws SQLException {
        try (var ps = c.prepareStatement("""
                INSERT INTO edges (id, type, from_id, to_id, upstream_blob_sha_at_link,
                                   link_status, established_by, validated_at, validated_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET upstream_blob_sha_at_link=EXCLUDED.upstream_blob_sha_at_link,
                  link_status=EXCLUDED.link_status, validated_at=EXCLUDED.validated_at,
                  validated_by=EXCLUDED.validated_by""")) {
            ps.setString(1, e.id());
            ps.setString(2, e.type().name());
            ps.setString(3, e.from().value());
            ps.setString(4, e.to().value());
            ps.setString(5, e.upstreamBlobShaAtLink());
            ps.setString(6, e.linkStatus().name());
            ps.setString(7, e.establishedBy());
            ps.setTimestamp(8, e.validatedAt() == null ? null : Timestamp.from(e.validatedAt()));
            ps.setString(9, e.validatedBy());
            ps.executeUpdate();
        }
    }

    @Override public List<Node> downstreamOf(ArtifactId id, EdgeType... types) {
        var wanted = types.length == 0
                ? Arrays.stream(EdgeType.values()).map(Enum::name).toList()
                : Arrays.stream(types).map(Enum::name).toList();
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("""
                SELECT n.* FROM edges e JOIN nodes n ON n.id = e.from_id
                WHERE e.to_id = ? AND e.type = ANY (?)""")) {
            ps.setString(1, id.value());
            ps.setArray(2, c.createArrayOf("varchar", wanted.toArray()));
            return readNodes(ps);
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public List<Node> staleNodes() {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("SELECT * FROM nodes WHERE status = 'NEEDS_REVALIDATION'")) {
            return readNodes(ps);
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public List<Node> impactOf(ArtifactId changed) {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement("""
                WITH RECURSIVE impact AS (
                  SELECT e.from_id FROM edges e
                    WHERE e.to_id = ? AND e.type IN ('DERIVES_FROM','SATISFIES')
                  UNION
                  SELECT e.from_id FROM edges e JOIN impact i ON e.to_id = i.from_id
                    WHERE e.type IN ('DERIVES_FROM','SATISFIES')
                )
                SELECT n.* FROM nodes n JOIN impact i ON n.id = i.from_id""")) {
            ps.setString(1, changed.value());
            return readNodes(ps);
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    @Override public void revalidate(String edgeId, String validatedBy) {
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                var edge = findEdge(c, edgeId).orElseThrow(
                        () -> new NoSuchElementException("edge " + edgeId));
                var upstream = findNode(c, edge.to().value()).orElseThrow(
                        () -> new NoSuchElementException("node " + edge.to()));
                if (edge.linkStatus() == LinkStatus.CURRENT
                        && edge.upstreamBlobShaAtLink().equals(upstream.blobSha())) {
                    c.rollback();
                    return; // nothing to revalidate; keep the original audit stamp
                }
                link(c, edge.revalidated(upstream.blobSha(), validatedBy, Instant.now()));
                clearFlagIfNoStaleDeps(c, edge.from());
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private void clearFlagIfNoStaleDeps(Connection c, ArtifactId id) throws SQLException {
        var node = findNode(c, id.value()).orElse(null);
        if (node == null || node.status() != NodeStatus.NEEDS_REVALIDATION) return;
        try (var ps = c.prepareStatement(
                "SELECT count(*) FROM edges WHERE from_id = ? AND link_status = 'STALE'")) {
            ps.setString(1, id.value());
            try (var rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) > 0) return;
            }
        }
        var restored = node.provenance().humanApproved() ? NodeStatus.APPROVED : NodeStatus.DRAFT;
        upsert(c, node.withStatus(restored, node.provenance(), Instant.now()));
        try (var ps = c.prepareStatement("""
                SELECT from_id FROM edges WHERE to_id = ? AND type IN ('DERIVES_FROM','SATISFIES')""")) {
            ps.setString(1, id.value());
            try (var rs = ps.executeQuery()) {
                var dependents = new ArrayList<String>();
                while (rs.next()) dependents.add(rs.getString(1));
                for (var d : dependents) clearFlagIfNoStaleDeps(c, ArtifactId.of(d));
            }
        }
    }

    @Override public List<RevalidationRequested> applyChange(ArtifactId nodeId, String newBlobSha) {
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                var changed = findNode(c, nodeId.value()).orElseThrow(
                        () -> new NoSuchElementException("node " + nodeId));
                if (changed.blobSha().equals(newBlobSha)) { c.rollback(); return List.of(); }
                upsert(c, changed.withContentChange(newBlobSha, Instant.now()));
                try (var ps = c.prepareStatement("""
                        UPDATE edges SET link_status = 'STALE'
                        WHERE to_id = ? AND type IN ('DERIVES_FROM','SATISFIES')
                          AND upstream_blob_sha_at_link <> ?""")) {
                    ps.setString(1, nodeId.value());
                    ps.setString(2, newBlobSha);
                    ps.executeUpdate();
                }
                var events = new ArrayList<RevalidationRequested>();
                for (var node : impactOf(c, nodeId)) {
                    if (node.status() != NodeStatus.NEEDS_REVALIDATION) {
                        upsert(c, node.withStatus(NodeStatus.NEEDS_REVALIDATION,
                                node.provenance(), Instant.now()));
                        events.add(new RevalidationRequested(node.id(), List.of(nodeId)));
                    }
                }
                c.commit();
                return events;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    // --- helpers ---

    private List<Node> impactOf(Connection c, ArtifactId changed) throws SQLException {
        try (var ps = c.prepareStatement("""
                WITH RECURSIVE impact AS (
                  SELECT e.from_id FROM edges e
                    WHERE e.to_id = ? AND e.type IN ('DERIVES_FROM','SATISFIES')
                  UNION
                  SELECT e.from_id FROM edges e JOIN impact i ON e.to_id = i.from_id
                    WHERE e.type IN ('DERIVES_FROM','SATISFIES')
                )
                SELECT n.* FROM nodes n JOIN impact i ON n.id = i.from_id""")) {
            ps.setString(1, changed.value());
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<Node>();
                while (rs.next()) out.add(readNode(rs));
                return out;
            }
        }
    }

    private Optional<Node> findNode(Connection c, String id) throws SQLException {
        try (var ps = c.prepareStatement("SELECT * FROM nodes WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readNode(rs)) : Optional.empty();
            }
        }
    }

    private Optional<Edge> findEdge(Connection c, String id) throws SQLException {
        try (var ps = c.prepareStatement("SELECT * FROM edges WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                var validatedAt = rs.getTimestamp("validated_at");
                return Optional.of(new Edge(rs.getString("id"),
                        EdgeType.valueOf(rs.getString("type")),
                        ArtifactId.of(rs.getString("from_id")),
                        ArtifactId.of(rs.getString("to_id")),
                        rs.getString("upstream_blob_sha_at_link"),
                        LinkStatus.valueOf(rs.getString("link_status")),
                        rs.getString("established_by"),
                        validatedAt == null ? null : validatedAt.toInstant(),
                        rs.getString("validated_by")));
            }
        }
    }

    private List<Node> readNodes(PreparedStatement ps) throws SQLException {
        try (var rs = ps.executeQuery()) {
            var out = new ArrayList<Node>();
            while (rs.next()) out.add(readNode(rs));
            return out;
        }
    }

    private Node readNode(ResultSet rs) throws SQLException {
        return new Node(ArtifactId.of(rs.getString("id")),
                NodeType.valueOf(rs.getString("type")),
                rs.getString("title"), rs.getString("repo_path"), rs.getString("blob_sha"),
                NodeStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                provenanceFromJson(rs.getString("provenance")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String provenanceJson(Provenance p) {
        var b = Json.createObjectBuilder()
                .add("generatedBy", p.generatedBy())
                .add("confidence", p.confidence())
                .add("humanApproved", p.humanApproved());
        var refs = Json.createArrayBuilder();
        p.sourceRefs().forEach(refs::add);
        var assumptions = Json.createArrayBuilder();
        p.assumptions().forEach(assumptions::add);
        b.add("sourceRefs", refs).add("assumptions", assumptions);
        if (p.approvedBy() != null) b.add("approvedBy", p.approvedBy());
        if (p.approvedAt() != null) b.add("approvedAt", p.approvedAt().toString());
        return b.build().toString();
    }

    private static Provenance provenanceFromJson(String json) {
        try (var r = Json.createReader(new StringReader(json))) {
            JsonObject o = r.readObject();
            return new Provenance(
                    o.getJsonArray("sourceRefs").stream()
                            .map(v -> ((jakarta.json.JsonString) v).getString()).toList(),
                    o.getString("generatedBy"),
                    o.getJsonNumber("confidence").doubleValue(),
                    o.getJsonArray("assumptions").stream()
                            .map(v -> ((jakarta.json.JsonString) v).getString()).toList(),
                    o.getBoolean("humanApproved"),
                    o.containsKey("approvedBy") ? o.getString("approvedBy") : null,
                    o.containsKey("approvedAt") ? Instant.parse(o.getString("approvedAt")) : null);
        }
    }
}
```
Edge id column is varchar(64) but derived ids can exceed that (`STORY-0042->SPEC-0007:DERIVES_FROM` = 33 chars; fine, but don't shrink). If any contract test fails against Postgres but passes in-memory, the POSTGRES adapter is wrong — fix the adapter, never the contract.

- [ ] **Step 5: Run, expect contract green + perf smoke**

Run: `./gradlew :libs:adapter-graph-postgres:test`
Expected: 7 tests (6 contract + perf), 0 failures. Then full `./gradlew build`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(adapter-graph-postgres): jdbc projection passing the shared port contract"
```

### Task 10: Real cost — `ModelPricing` in adapter-llm-spring

**Files:**
- Create: `libs/adapter-llm-spring/src/main/resources/pricing.yaml`
- Create: `libs/adapter-llm-spring/src/main/java/dev/sdlc/adapter/llm/ModelPricing.java`
- Modify: `libs/adapter-llm-spring/src/main/java/dev/sdlc/adapter/llm/SpringAiLanguageModel.java`
- Modify: `libs/adapter-llm-spring/build.gradle.kts` (snakeyaml for the loader)
- Test: `libs/adapter-llm-spring/src/test/java/dev/sdlc/adapter/llm/ModelPricingTest.java`; modify `agents/agent-spec/src/test/java/dev/sdlc/agentspec/bootstrap/OpenAiCompatIntegrationTest.java` if its cost assertion assumed 0.0

- [ ] **Step 1: Write the failing test**

```java
package dev.sdlc.adapter.llm;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelPricingTest {
    @Test
    void computesCostFromBundledTable() {
        var pricing = ModelPricing.fromBundledYaml();
        // claude-sonnet-4-6: 3.00 / 15.00 USD per 1M tokens (config, not code — pricing.yaml)
        double cost = pricing.costUsd("claude-sonnet-4-6", 1_000_000, 1_000_000);
        assertThat(cost).isEqualTo(18.0);
    }

    @Test
    void unknownModelCostsZero() {
        var pricing = ModelPricing.fromBundledYaml();
        assertThat(pricing.costUsd("mystery-model", 1000, 1000)).isZero();
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :libs:adapter-llm-spring:test` → FAIL (class missing).

- [ ] **Step 3: Implement**

`pricing.yaml`:
```yaml
# USD per 1M tokens. This is CONFIG, not code: update when providers change prices,
# add entries for the models you actually run. Unknown models cost 0 and log a warning.
claude-sonnet-4-6:   { input: 3.00, output: 15.00 }
claude-haiku-4-5:    { input: 1.00, output: 5.00 }
gpt-5.2:             { input: 1.75, output: 14.00 }   # placeholder — verify before relying on it
```

`ModelPricing.java`:
```java
package dev.sdlc.adapter.llm;

import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/** USD-per-million-token table, loaded from pricing.yaml (config, not code). */
public final class ModelPricing {
    private record Rate(double inputPerMTok, double outputPerMTok) {}
    private final Map<String, Rate> rates;

    private ModelPricing(Map<String, Rate> rates) { this.rates = rates; }

    @SuppressWarnings("unchecked")
    public static ModelPricing fromBundledYaml() {
        try (var in = ModelPricing.class.getResourceAsStream("/pricing.yaml")) {
            Map<String, Map<String, Number>> raw = new Yaml().load(in);
            var rates = new java.util.HashMap<String, Rate>();
            raw.forEach((model, io) -> rates.put(model,
                    new Rate(io.get("input").doubleValue(), io.get("output").doubleValue())));
            return new ModelPricing(Map.copyOf(rates));
        } catch (Exception e) { throw new IllegalStateException("pricing.yaml unreadable", e); }
    }

    /** Unknown model → 0.0 (caller logs). */
    public double costUsd(String model, long inputTokens, long outputTokens) {
        var rate = rates.get(model);
        if (rate == null) return 0.0;
        return inputTokens / 1_000_000.0 * rate.inputPerMTok()
                + outputTokens / 1_000_000.0 * rate.outputPerMTok();
    }

    public boolean knows(String model) { return rates.containsKey(model); }
}
```
`libs/adapter-llm-spring/build.gradle.kts` dependencies gain `implementation(libs.snakeyaml)`.

`SpringAiLanguageModel` change — constructor takes the pricing table and the response's model id feeds the lookup:
```java
    private final ChatModel chatModel;
    private final ModelPricing pricing;

    public SpringAiLanguageModel(ChatModel chatModel) { this(chatModel, ModelPricing.fromBundledYaml()); }

    public SpringAiLanguageModel(ChatModel chatModel, ModelPricing pricing) {
        this.chatModel = chatModel; this.pricing = pricing;
    }
```
and in `complete(...)`, replacing the hardcoded 0.0 (keep the null-safe token guards exactly as they are):
```java
        String model = response.getMetadata().getModel();
        double cost = pricing.costUsd(model == null ? "" : model, in, out);
        if (model != null && !pricing.knows(model))
            System.err.println("[pricing] unknown model '" + model + "' — cost recorded as $0");
        return new ModelResponse(response.getResult().getOutput().getText(), List.of(),
                new Usage(in, out, cost));
```
Check `OpenAiCompatIntegrationTest`: its stub reports model "stub" (unknown → cost 0) so its assertions hold; if it asserts on stderr cleanliness, it doesn't — leave it.

- [ ] **Step 4: Run** — `./gradlew :libs:adapter-llm-spring:test && ./gradlew build` → green.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(adapter-llm-spring): real per-model cost from pricing.yaml (NFR-COST)"
```

---

### Task 11: `libs/adapter-otel` — OpenTelemetry RunTrace

**Files:**
- Modify: `gradle/libs.versions.toml` (otel entries)
- Create: `libs/adapter-otel/build.gradle.kts`; modify `settings.gradle.kts` (include)
- Create: `libs/adapter-otel/src/main/java/dev/sdlc/adapter/otel/OtelRunTrace.java`
- Test: `libs/adapter-otel/src/test/java/dev/sdlc/adapter/otel/OtelRunTraceTest.java`

- [ ] **Step 1: Catalog + module**

```toml
# [versions]
opentelemetry = "1.52.0"
# [libraries]
otel-api = { module = "io.opentelemetry:opentelemetry-api", version.ref = "opentelemetry" }
otel-sdk = { module = "io.opentelemetry:opentelemetry-sdk", version.ref = "opentelemetry" }
otel-exporter-otlp = { module = "io.opentelemetry:opentelemetry-exporter-otlp", version.ref = "opentelemetry" }
otel-autoconfigure = { module = "io.opentelemetry:opentelemetry-sdk-extension-autoconfigure", version.ref = "opentelemetry" }
otel-sdk-testing = { module = "io.opentelemetry:opentelemetry-sdk-testing", version.ref = "opentelemetry" }
```
(Latest 1.x if 1.52.0 doesn't resolve; report what you used.)

`libs/adapter-otel/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
dependencies {
    api(project(":libs:agent-core"))
    implementation(libs.otel.api)
    runtimeOnly(libs.otel.sdk)
    runtimeOnly(libs.otel.exporter.otlp)
    runtimeOnly(libs.otel.autoconfigure)
    testImplementation(libs.otel.sdk)
    testImplementation(libs.otel.sdk.testing)
}
```

- [ ] **Step 2: Write the failing test**

```java
package dev.sdlc.adapter.otel;

import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class OtelRunTraceTest {
    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    @Test
    void runBecomesSpanWithStepsAndCounters() {
        var trace = new OtelRunTrace(OTEL.getOpenTelemetry());
        trace.step("run-1", "model", "tool-request", 100, 20, 0.0015);
        trace.step("run-1", "tool:echo", "ok", 0, 0, 0);
        trace.finish("run-1", "completed");

        var spans = OTEL.getSpans();
        // one run span + two step spans
        assertThat(spans).hasSize(3);
        var run = spans.stream().filter(s -> s.getName().equals("sdlc.agent.run")).findFirst().orElseThrow();
        assertThat(run.getAttributes().asMap().toString()).contains("completed");
        var modelStep = spans.stream().filter(s -> s.getName().equals("sdlc.agent.step.model")).findFirst().orElseThrow();
        assertThat(modelStep.getAttributes().asMap().toString())
                .contains("100").contains("20");
    }

    @Test
    void finishOnUnknownRunIsANoOp() {
        var trace = new OtelRunTrace(OTEL.getOpenTelemetry());
        trace.finish("never-started", "completed"); // must not throw
    }
}
```

- [ ] **Step 3: Run to verify failure** — `./gradlew :libs:adapter-otel:test` → FAIL.

- [ ] **Step 4: Implement**

```java
package dev.sdlc.adapter.otel;

import dev.sdlc.agent.port.RunTracePort;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NFR-OBS: one span per agent run, a child span per step, token/cost attributes.
 * Wire io.opentelemetry.sdk autoconfigure in bootstrap (OTEL_EXPORTER_OTLP_ENDPOINT);
 * without an endpoint the SDK no-ops, which is the desired fallback.
 */
public final class OtelRunTrace implements RunTracePort {
    private static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("sdlc.run.id");
    private static final AttributeKey<String> STEP_KIND = AttributeKey.stringKey("sdlc.step.kind");
    private static final AttributeKey<Long> IN_TOK = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> OUT_TOK = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Double> COST = AttributeKey.doubleKey("sdlc.cost.usd");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("sdlc.run.outcome");

    private final Tracer tracer;
    private final Map<String, Span> runs = new ConcurrentHashMap<>();

    public OtelRunTrace(OpenTelemetry otel) { this.tracer = otel.getTracer("dev.sdlc.agent"); }

    @Override
    public void step(String runId, String kind, String detail,
                     long inputTokens, long outputTokens, double costUsd) {
        var run = runs.computeIfAbsent(runId, id ->
                tracer.spanBuilder("sdlc.agent.run").setAttribute(RUN_ID, id).startSpan());
        var step = tracer.spanBuilder("sdlc.agent.step." + (kind.startsWith("tool:") ? "tool" : kind))
                .setParent(Context.current().with(run))
                .setAttribute(RUN_ID, runId)
                .setAttribute(STEP_KIND, kind)
                .setAttribute(IN_TOK, inputTokens)
                .setAttribute(OUT_TOK, outputTokens)
                .setAttribute(COST, costUsd)
                .startSpan();
        step.end();
    }

    @Override
    public void finish(String runId, String outcome) {
        var run = runs.remove(runId);
        if (run == null) return;
        run.setAttribute(OUTCOME, outcome);
        run.end();
    }
}
```

- [ ] **Step 5: Run** — `./gradlew :libs:adapter-otel:test && ./gradlew build` → green. If the span-count assertion is off because the extension reports spans only after end (run span ends in finish), reorder assertions/expectations to match real SDK behavior — the contract is: 1 run span carrying the outcome + 1 step span per step call, with token/cost attributes on steps.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(adapter-otel): run/step spans with token and cost attributes (NFR-OBS)"
```

---

### Task 12: ScopedValue `RunContext` in agent-core

**Files:**
- Create: `libs/agent-core/src/main/java/dev/sdlc/agent/RunContext.java`
- Modify: `libs/agent-core/src/main/java/dev/sdlc/agent/AgentLoop.java`
- Test: extend `libs/agent-core/src/test/java/dev/sdlc/agent/AgentLoopTest.java`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void runContextIsScopedDuringTheRun() {
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        Tool peek = new Tool() {
            public String name() { return "peek"; }
            public String description() { return "peek"; }
            public Map<String, String> parameterSchema() { return Map.of(); }
            public String execute(Map<String, Object> args) {
                seen.set(RunContext.current().map(RunContext::runId).orElse(null));
                return "ok";
            }
        };
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("peek", Map.of()),
                FakeLanguageModel.finalText("done"));
        var loop = new AgentLoop(model, new ToolRegistry(List.of(peek)), noTrace, new Guardrails(5, 1.0));

        loop.run("run-ctx-1", "system", "task");

        assertThat(seen.get()).isEqualTo("run-ctx-1");
        assertThat(RunContext.current()).isEmpty(); // not leaked outside the run
    }
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :libs:agent-core:test --tests AgentLoopTest` → FAIL (RunContext missing).

- [ ] **Step 3: Implement**

`RunContext.java`:
```java
package dev.sdlc.agent;

import java.util.Optional;

/**
 * Per-run context carried via ScopedValue (Java 25) inside agent-core. Ports keep explicit
 * runId parameters — this exists so tools/adapters deep in a run can correlate without
 * threading runId through every signature.
 */
public record RunContext(String runId) {
    static final ScopedValue<RunContext> SCOPE = ScopedValue.newInstance();

    public static Optional<RunContext> current() {
        return SCOPE.isBound() ? Optional.of(SCOPE.get()) : Optional.empty();
    }
}
```

`AgentLoop.run` wraps its existing body:
```java
    public AgentRunResult run(String runId, String systemPrompt, String task) {
        return ScopedValue.where(RunContext.SCOPE, new RunContext(runId))
                .call(() -> doRun(runId, systemPrompt, task));
    }

    private AgentRunResult doRun(String runId, String systemPrompt, String task) {
        // existing body, unchanged
    }
```
(`ScopedValue.where(...).call(...)` throws no checked exception in Java 25 final API for a `Supplier`-shaped callable — if `call` requires handling `Exception`, use the `Callable` overload and rethrow `RuntimeException`s unwrapped; verify against the actual Java 25 API with a quick compile, and keep `GuardrailExceeded` propagating untouched — the existing guardrail tests prove it.)

- [ ] **Step 4: Run** — `./gradlew :libs:agent-core:test && ./gradlew build` → green (12 agent-core tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(agent-core): scoped run context (Java 25 ScopedValue)"
```

### Task 13: `libs/adapter-git` — GitPort, ProcessGitAdapter, GitArtifactRepository

**Files:**
- Create: `libs/agent-core/src/main/java/dev/sdlc/agent/port/GitPort.java`
- Create: `libs/adapter-git/build.gradle.kts`; modify `settings.gradle.kts` (include)
- Create: `libs/adapter-git/src/main/java/dev/sdlc/adapter/git/ProcessGitAdapter.java`, `GitArtifactRepository.java`
- Test: `libs/adapter-git/src/test/java/dev/sdlc/adapter/git/GitArtifactRepositoryTest.java`

**Semantics (spec §8, made precise):** the WORKSPACE (artifact directory) is its own git repo, separate from the source repo. Every artifact write happens on branch `proposal/<artifactId>`: checkout -B from main, write, commit, checkout main back. Reads: working tree (main) first; if absent, `git show proposal/<id>:<path>`. Approval (Task 14) merges `proposal/<id>` into main. Rejection leaves the branch for rework.

- [ ] **Step 1: GitPort in agent-core**

```java
package dev.sdlc.agent.port;

import java.util.Optional;

/** Outbound port to version control for the ARTIFACT workspace (not the source repo). */
public interface GitPort {
    /** Initializes the repo (with an initial commit on `main`) if absent; idempotent. */
    void ensureRepo();
    void checkoutBranch(String name, boolean createFromMain);
    void commitAll(String message);
    void merge(String branch, String message);
    String currentBranch();
    Optional<String> showFile(String branch, String relativePath);
}
```

- [ ] **Step 2: Module + failing test**

`libs/adapter-git/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
dependencies {
    api(project(":libs:agent-core"))
    implementation(project(":libs:adapter-common")) // decorates FileArtifactRepository
    implementation(project(":libs:traceability-graph")) // gitBlobSha
}
```
`settings.gradle.kts`: add `include("libs:adapter-git")`.

`GitArtifactRepositoryTest.java`:
```java
package dev.sdlc.adapter.git;

import dev.sdlc.adapter.common.FileArtifactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class GitArtifactRepositoryTest {

    private GitArtifactRepository repo(Path dir) {
        var git = new ProcessGitAdapter(dir);
        return new GitArtifactRepository(new FileArtifactRepository(dir), git);
    }

    @Test
    void writeLandsOnProposalBranchNotMain(@TempDir Path dir) {
        var repo = repo(dir);
        String sha = repo.write("specs/SPEC-0001.md", "draft content\n");

        assertThat(sha).hasSize(40);
        // main's working tree does NOT have the file (it lives on the proposal branch)
        assertThat(dir.resolve("specs/SPEC-0001.md")).doesNotExist();
        // but reads fall through to the proposal branch
        assertThat(repo.read("specs/SPEC-0001.md")).contains("draft content\n");
    }

    @Test
    void mergeProposalMakesFileVisibleOnMain(@TempDir Path dir) {
        var repo = repo(dir);
        repo.write("specs/SPEC-0001.md", "draft content\n");
        repo.git().merge("proposal/SPEC-0001", "approval: SPEC-0001 by a.dupont");

        assertThat(dir.resolve("specs/SPEC-0001.md")).exists();
        assertThat(repo.read("specs/SPEC-0001.md")).contains("draft content\n");
    }

    @Test
    void secondWriteToSameArtifactAppendsToItsBranch(@TempDir Path dir) {
        var repo = repo(dir);
        repo.write("specs/SPEC-0001.md", "v1\n");
        repo.write("specs/SPEC-0001.md", "v2\n");
        assertThat(repo.read("specs/SPEC-0001.md")).contains("v2\n");
    }

    @Test
    void nonArtifactPathsCommitDirectlyToMain(@TempDir Path dir) {
        var repo = repo(dir);
        repo.write("inbox/notes.md", "raw stakeholder text\n");
        assertThat(dir.resolve("inbox/notes.md")).exists(); // no proposal dance for non-artifacts
    }
}
```
(Artifact paths are recognized by their basename matching `<PREFIX>-<4 digits>.md`.)

- [ ] **Step 3: Run to verify failure** — `./gradlew :libs:adapter-git:test` → FAIL.

- [ ] **Step 4: Implement**

`ProcessGitAdapter.java`:
```java
package dev.sdlc.adapter.git;

import dev.sdlc.agent.port.GitPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** GitPort over the git CLI (no JGit). All commands run with -C <workdir>. */
public final class ProcessGitAdapter implements GitPort {
    private final Path workdir;

    public ProcessGitAdapter(Path workdir) {
        this.workdir = workdir;
        ensureRepo();
    }

    @Override public void ensureRepo() {
        if (workdir.resolve(".git").toFile().exists()) return;
        run("init", "--initial-branch=main");
        run("config", "user.email", "agents@sdlc.local");
        run("config", "user.name", "sdlc-agent");
        run("commit", "--allow-empty", "-m", "workspace init");
    }

    @Override public void checkoutBranch(String name, boolean createFromMain) {
        if (createFromMain) run("checkout", "-B", name, "main");
        else run("checkout", name);
    }

    @Override public void commitAll(String message) {
        run("add", "-A");
        // --allow-empty: re-writes with identical content must not fail the flow
        run("commit", "--allow-empty", "-m", message);
    }

    @Override public void merge(String branch, String message) {
        run("checkout", "main");
        run("merge", "--no-ff", branch, "-m", message);
    }

    @Override public String currentBranch() {
        return run("rev-parse", "--abbrev-ref", "HEAD").strip();
    }

    @Override public Optional<String> showFile(String branch, String relativePath) {
        try {
            return Optional.of(run("show", branch + ":" + relativePath));
        } catch (IllegalStateException e) {
            return Optional.empty(); // path not on that branch
        }
    }

    private String run(String... args) {
        var cmd = new ArrayList<String>(List.of("git", "-C", workdir.toString()));
        cmd.addAll(List.of(args));
        try {
            var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0)
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("git unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running git", e);
        }
    }
}
```

`GitArtifactRepository.java`:
```java
package dev.sdlc.adapter.git;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.agent.port.GitPort;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * FR-SAC-2 (local-first): artifact writes land as commits on proposal/<artifactId>;
 * approval merges that branch into main (done by the approval use case via GitPort).
 * Non-artifact paths (inbox, notes) commit straight to main.
 */
public final class GitArtifactRepository implements ArtifactRepositoryPort {
    private static final Pattern ARTIFACT_FILE = Pattern.compile("([A-Z]{2,}-\\d{4})\\.md");

    private final ArtifactRepositoryPort files;
    private final GitPort git;

    public GitArtifactRepository(ArtifactRepositoryPort files, GitPort git) {
        this.files = files; this.git = git;
    }

    public GitPort git() { return git; }

    @Override public String write(String repoPath, String content) {
        var artifactId = artifactIdFrom(repoPath);
        if (artifactId == null) {
            var sha = files.write(repoPath, content);
            git.commitAll("update: " + repoPath);
            return sha;
        }
        git.checkoutBranch("proposal/" + artifactId, true);
        try {
            var sha = files.write(repoPath, content);
            git.commitAll("proposal: " + artifactId);
            return sha;
        } finally {
            git.checkoutBranch("main", false);
        }
    }

    @Override public Optional<String> read(String repoPath) {
        var onMain = files.read(repoPath);
        if (onMain.isPresent()) return onMain;
        var artifactId = artifactIdFrom(repoPath);
        return artifactId == null ? Optional.empty()
                : git.showFile("proposal/" + artifactId, repoPath);
    }

    private static String artifactIdFrom(String repoPath) {
        var name = repoPath.substring(repoPath.lastIndexOf('/') + 1);
        var m = ARTIFACT_FILE.matcher(name);
        return m.matches() ? m.group(1) : null;
    }
}
```
Subtlety the test will catch: `checkoutBranch("proposal/X", true)` from main switches the working tree — a SECOND write to the same artifact must branch from its EXISTING proposal branch, not recreate from main (or v1's commit is lost from the branch tip — though `-B name main` resets it). Fix inside `write`: create-from-main only when the branch doesn't exist yet; otherwise plain checkout. Add to ProcessGitAdapter a `boolean branchExists(String name)` helper (`run("branch", "--list", name)` non-empty) and use `checkoutBranch(name, !git.branchExists(name))`. Add `branchExists` to GitPort. The third test pins this.

- [ ] **Step 5: Run** — `./gradlew :libs:adapter-git:test && ./gradlew build` → green (4 tests).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(adapter-git): proposal-branch artifact repository over the git cli (FR-SAC-2)"
```

---

### Task 14: Approval merges the proposal branch; bootstrap profiles; README

**Files:**
- Modify: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/application/ApproveArtifactUseCase.java`
- Modify: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/bootstrap/AgentSpecApplication.java`
- Modify: `agents/agent-spec/build.gradle.kts` (depend on adapter-git, adapter-graph-postgres, adapter-otel)
- Test: extend `agents/agent-spec/src/test/java/dev/sdlc/agentspec/application/ApproveArtifactUseCaseTest.java`
- Modify: `README.md`

- [ ] **Step 1: Write the failing test**

In `ApproveArtifactUseCaseTest` add a recording fake and a test:
```java
    static final class RecordingGit implements dev.sdlc.agent.port.GitPort {
        final java.util.List<String> merges = new java.util.ArrayList<>();
        public void ensureRepo() {}
        public boolean branchExists(String name) { return true; }
        public void checkoutBranch(String name, boolean createFromMain) {}
        public void commitAll(String message) {}
        public void merge(String branch, String message) { merges.add(branch + " :: " + message); }
        public String currentBranch() { return "main"; }
        public java.util.Optional<String> showFile(String branch, String path) { return java.util.Optional.empty(); }
    }

    @Test
    void approvalMergesTheProposalBranchWhenGitIsWired() {
        var git = new RecordingGit();
        new ApproveArtifactUseCase(graph, repo, decide(true, null), () -> T0, git).review(specId);
        assertThat(git.merges).containsExactly("proposal/SPEC-0001 :: approval: SPEC-0001 by a.dupont");
    }

    @Test
    void rejectionDoesNotMerge() {
        var git = new RecordingGit();
        new ApproveArtifactUseCase(graph, repo, decide(false, "vague"), () -> T0, git).review(specId);
        assertThat(git.merges).isEmpty();
    }
```

- [ ] **Step 2: Run to verify failure** — no 5-arg constructor → compile FAIL.

- [ ] **Step 3: Implement**

`ApproveArtifactUseCase` gains an optional GitPort (existing 4-arg constructor delegates with null — all current callers stay valid):
```java
    private final dev.sdlc.agent.port.GitPort git; // nullable: plain-file mode

    public ApproveArtifactUseCase(TraceabilityGraphPort graph, ArtifactRepositoryPort repo,
                                  HumanInTheLoopPort human, Supplier<Instant> clock) {
        this(graph, repo, human, clock, null);
    }

    public ApproveArtifactUseCase(TraceabilityGraphPort graph, ArtifactRepositoryPort repo,
                                  HumanInTheLoopPort human, Supplier<Instant> clock,
                                  dev.sdlc.agent.port.GitPort git) {
        this.graph = graph; this.repo = repo; this.human = human; this.clock = clock; this.git = git;
    }
```
and at the end of the approved path in `review(...)`, after `graph.upsert(persistFrontmatter(...))`:
```java
        if (decision.approved() && git != null)
            git.merge("proposal/" + id.value(), "approval: " + id.value() + " by " + decision.reviewer());
```
(The frontmatter rewrite happens through `repo` — when `repo` is the GitArtifactRepository, that rewrite landed on the proposal branch; the merge then publishes file + approval to main atomically.)

- [ ] **Step 4: Bootstrap profile wiring**

`agents/agent-spec/build.gradle.kts` dependencies gain:
```kotlin
    implementation(project(":libs:adapter-git"))
    implementation(project(":libs:adapter-graph-postgres"))
    implementation(project(":libs:adapter-otel"))
```
In `AgentSpecApplication.demo(...)`, replace the fixed wiring with profile-driven selection (inject `org.springframework.core.env.Environment env` into the @Bean method):
```java
            // graph: in-memory default; 'postgres' profile uses the durable projection
            TraceabilityGraphPort graph;
            if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("postgres"))) {
                var ds = new org.postgresql.ds.PGSimpleDataSource();
                ds.setUrl(System.getenv("SDLC_DB_URL"));
                ds.setUser(System.getenv("SDLC_DB_USER"));
                ds.setPassword(System.getenv("SDLC_DB_PASSWORD"));
                var pg = new dev.sdlc.adapter.graph.PostgresTraceabilityGraph(ds);
                pg.initSchema();
                graph = pg;
            } else {
                graph = new InMemoryTraceabilityGraph();
            }

            // repo: plain files default; 'git-approval' profile versions the workspace
            ArtifactRepositoryPort repo = new FileArtifactRepository(workspace);
            dev.sdlc.agent.port.GitPort gitPort = null;
            if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("git-approval"))) {
                gitPort = new dev.sdlc.adapter.git.ProcessGitAdapter(workspace);
                repo = new dev.sdlc.adapter.git.GitArtifactRepository(repo, gitPort);
            }

            // trace: console default; 'otel' profile exports spans
            RunTracePort trace = env.acceptsProfiles(org.springframework.core.env.Profiles.of("otel"))
                    ? new dev.sdlc.adapter.otel.OtelRunTrace(
                            io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
                                    .initialize().getOpenTelemetrySdk())
                    : new LoggingRunTrace();
```
(rebuild + the rest of the runner stays; the approval call passes `gitPort` via the 5-arg constructor.) The otel autoconfigure class needs `implementation(libs.otel.autoconfigure)` (not runtimeOnly) in adapter-otel OR direct in agent-spec — put `implementation(libs.otel.autoconfigure)` in agent-spec where it is used, and keep adapter-otel's runtimeOnly entries as they are. Sort imports properly instead of inline FQNs where the file already imports the package.

- [ ] **Step 5: README — add a "Profiles" section**

```markdown
## Profiles

| Profile | Effect | Env vars |
|---|---|---|
| (none) | in-memory graph, plain files, console trace | ANTHROPIC_API_KEY |
| `openai` | OpenAI-compatible LLM endpoint | OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_MODEL |
| `postgres` | durable graph projection | SDLC_DB_URL, SDLC_DB_USER, SDLC_DB_PASSWORD |
| `git-approval` | workspace is a git repo; proposals on branches, approval merges | — |
| `otel` | OTLP span export per agent run/step | OTEL_EXPORTER_OTLP_ENDPOINT |

Profiles combine: `SPRING_PROFILES_ACTIVE=openai,postgres,git-approval,otel`.
```

- [ ] **Step 6: Run everything**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all modules green (incl. the E2E, which keeps using plain-file mode and the 4-arg approval constructor).

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(agent-spec): merge-on-approve, postgres/otel/git-approval profiles"
```

---

## Phase 1A definition-of-done checklist (spec §4–§8)

- [ ] §4 adapter extraction: Tasks 1–2 (adapter-common, adapter-llm-spring), Task 3 (ArchUnit covers the new shape)
- [ ] §5 sha-pinned edges: Tasks 4–7 (parse, write, rebuild-staleness, revalidate-restamps) — restart-safe FR-TRACE-2
- [ ] §6 Postgres projection: Tasks 8–9 (contract extraction, adapter + NFR-PERF smoke)
- [ ] §7 OTel + cost: Tasks 10–11 (pricing.yaml, span export), Task 12 (ScopedValue RunContext)
- [ ] §8 git approval: Tasks 13–14 (proposal branches, merge-on-approve, profiles)

## Deferred / explicitly not here

- Phase 1B (Intent/Design/Backlog agents, closed-loop E2E) — separate plan, depends on Tasks 1–7.
- Real message bus, vendor PR adapter, Jira/Linear/ADO — Phase 2 (spec §14).
- Flyway migrations — single idempotent schema.sql is Phase 1A's deliberate simplification.

