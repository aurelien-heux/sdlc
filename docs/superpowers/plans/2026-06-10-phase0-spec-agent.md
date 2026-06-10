# Phase 0 — Traceability Graph + Specification Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the monorepo skeleton and run the Specification agent end-to-end: read `APPROVED` Requirement/UseCase artifacts → produce a Specification artifact (frontmatter + Gherkin) → update the graph projection → emit events → demonstrate change propagation, with hexagonal boundaries enforced by ArchUnit.

**Architecture:** Gradle multi-project monorepo, hexagonal per module. Libraries `domain-shared`, `traceability-graph`, `agent-core` are pure Java (no framework deps). The agent `agent-spec` has `domain` / `application` / `adapters` / `bootstrap` packages; all I/O (LLM, files, events, human approval) sits behind ports. The graph projection is **in-memory, rebuilt from artifact frontmatter files** for Phase 0 (Postgres is deferred to Phase 1 — the port hides it, per NFR-PERF this is fine at Phase 0 volume).

**Tech Stack:** Java 25 (toolchain), Gradle (Kotlin DSL) with convention plugins, JUnit 5 + AssertJ, ArchUnit, SnakeYAML (frontmatter), Spring Boot + Spring AI in `bootstrap` only.

**Scope decisions (deviations are deliberate, all behind ports):**
- In-memory projection instead of Postgres (Phase 1 swaps the adapter, port unchanged).
- Observability = a `RunTracePort` recording steps/tokens/cost, logged at run end; OpenTelemetry export is Phase 1+.
- Event bus = in-process publisher adapter; real messaging is Phase 2.
- `blobSha` computed exactly like `git hash-object` (sha1 of `"blob <len>\0<content>"`) so values stay valid when Git becomes the store.

---

## File structure

```
settings.gradle.kts
gradle/libs.versions.toml
build-logic/
  build.gradle.kts
  settings.gradle.kts
  src/main/kotlin/sdlc.java-conventions.gradle.kts
libs/domain-shared/            # dev.sdlc.domain — IDs, enums, Provenance, events
libs/traceability-graph/       # dev.sdlc.trace  — Node, Edge, port, in-memory adapter,
                               #                   frontmatter parser, projection builder
libs/agent-core/               # dev.sdlc.agent  — ports (LLM, tools, HITL, repo, events,
                               #                   trace) + AgentLoop with guardrails
agents/agent-spec/             # dev.sdlc.agentspec.{domain,application,adapters,bootstrap}
.github/workflows/ci.yml
```

Each library: `src/main/java`, `src/test/java`, `build.gradle.kts` applying `sdlc.java-conventions`.

---

### Task 1: Repo + Gradle skeleton with convention plugin

**Files:**
- Create: `settings.gradle.kts`, `gradle/libs.versions.toml`
- Create: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/sdlc.java-conventions.gradle.kts`
- Create: `libs/domain-shared/build.gradle.kts` (placeholder module to prove the build)
- Create: `.gitignore`

- [ ] **Step 1: Init git and wrapper**

```bash
cd /home/heuxa/dev/claude/sdlc
git init && git add -A && git commit -m "chore: brief, spec, plan"
gradle wrapper --gradle-version 9.1.0   # or: install Gradle via sdkman first
```

- [ ] **Step 2: Write the build files**

`settings.gradle.kts`:
```kotlin
pluginManagement { includeBuild("build-logic") }
rootProject.name = "sdlc-platform"
include("libs:domain-shared")
// later tasks add: libs:traceability-graph, libs:agent-core, agents:agent-spec
```

`gradle/libs.versions.toml`:
```toml
[versions]
junit = "5.11.4"
assertj = "3.27.3"
archunit = "1.3.0"
snakeyaml = "2.3"
springBoot = "4.0.6"     # latest GA (Apr 2026)
springAi = "2.0.0-M4"    # the 2.x line is the one compatible with Boot 4; GA expected mid-2026.
                         # If a 2.0.x GA exists when you execute this task, use it instead.

[libraries]
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj = { module = "org.assertj:assertj-core", version.ref = "assertj" }
archunit = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
snakeyaml = { module = "org.yaml:snakeyaml", version.ref = "snakeyaml" }
```

`build-logic/settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "build-logic"
```

`build-logic/build.gradle.kts`:
```kotlin
plugins { `kotlin-dsl` }
repositories { mavenCentral(); gradlePluginPortal() }
```

`build-logic/src/main/kotlin/sdlc.java-conventions.gradle.kts`:
```kotlin
plugins { java }

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.withType<Test> { useJUnitPlatform() }
```

`libs/domain-shared/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
```

`.gitignore`:
```
.gradle/
build/
*.class
.idea/
```

- [ ] **Step 3: Verify the build works**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` (no sources yet — configuration is what's under test).

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "build: gradle monorepo skeleton with convention plugin"
```

### Task 2: domain-shared — IDs and type enums

**Files:**
- Create: `libs/domain-shared/src/main/java/dev/sdlc/domain/ArtifactId.java`
- Create: `libs/domain-shared/src/main/java/dev/sdlc/domain/NodeType.java`, `NodeStatus.java`, `EdgeType.java`, `LinkStatus.java`
- Test: `libs/domain-shared/src/test/java/dev/sdlc/domain/ArtifactIdTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.sdlc.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ArtifactIdTest {
    @Test
    void acceptsWellFormedIds() {
        assertThat(ArtifactId.of("REQ-0012").value()).isEqualTo("REQ-0012");
        assertThat(ArtifactId.of("SPEC-0007").prefix()).isEqualTo("SPEC");
    }

    @Test
    void rejectsMalformedIds() {
        assertThatThrownBy(() -> ArtifactId.of("req-12")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactId.of("REQ12")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactId.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :libs:domain-shared:test --tests ArtifactIdTest`
Expected: FAIL — `ArtifactId` does not compile/exist.

- [ ] **Step 3: Write minimal implementation**

`ArtifactId.java`:
```java
package dev.sdlc.domain;

import java.util.regex.Pattern;

/** Stable logical identity, e.g. REQ-0012. Identity is separate from version (blobSha). */
public record ArtifactId(String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Z]+-\\d{4}");

    public ArtifactId {
        if (value == null || !FORMAT.matcher(value).matches())
            throw new IllegalArgumentException("ArtifactId must match PREFIX-#### : " + value);
    }

    public static ArtifactId of(String value) { return new ArtifactId(value); }

    public String prefix() { return value.substring(0, value.indexOf('-')); }
}
```

`NodeType.java`:
```java
package dev.sdlc.domain;

public enum NodeType {
    GOAL, REQUIREMENT, NFR, USE_CASE, SPECIFICATION,
    DESIGN_ELEMENT, ADR, API_CONTRACT, BACKLOG_ITEM, CODE, TEST
}
```

`NodeStatus.java`:
```java
package dev.sdlc.domain;

public enum NodeStatus { DRAFT, PROPOSED, APPROVED, NEEDS_REVALIDATION, DEPRECATED }
```

`EdgeType.java`:
```java
package dev.sdlc.domain;

public enum EdgeType {
    DERIVES_FROM, SATISFIES, VERIFIES, DEPENDS_ON,
    CONFLICTS_WITH, DUPLICATES, CONSTRAINS, SUPERSEDES
}
```

`LinkStatus.java`:
```java
package dev.sdlc.domain;

public enum LinkStatus { CURRENT, STALE, ORPHANED, REJECTED }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :libs:domain-shared:test --tests ArtifactIdTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(domain-shared): artifact ids and graph type enums"
```

---

### Task 3: domain-shared — Provenance with grounding invariant

**Files:**
- Create: `libs/domain-shared/src/main/java/dev/sdlc/domain/Provenance.java`
- Test: `libs/domain-shared/src/test/java/dev/sdlc/domain/ProvenanceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.sdlc.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ProvenanceTest {
    @Test
    void groundedProvenanceIsValid() {
        var p = Provenance.generated(List.of("conv:2026-06-03#msg42"), "agent-spec@v1", 0.8, List.of());
        assertThat(p.humanApproved()).isFalse();
        assertThat(p.approvedBy()).isNull();
    }

    @Test
    void assumptionsAloneAreSufficientGrounding() {
        var p = Provenance.generated(List.of(), "agent-spec@v1", 0.5, List.of("tax rounding is per-jurisdiction"));
        assertThat(p.assumptions()).hasSize(1);
    }

    @Test
    void emptySourceRefsAndNoAssumptionsIsInvalid() {
        // NFR-GROUND: an artifact with empty sourceRefs and no assumptions is invalid
        assertThatThrownBy(() -> Provenance.generated(List.of(), "agent-spec@v1", 0.5, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("grounded");
    }

    @Test
    void approvalRecordsApprover() {
        var p = Provenance.generated(List.of("ticket:PROJ-88"), "agent-spec@v1", 0.9, List.of())
                .approve("a.dupont", java.time.Instant.parse("2026-06-10T10:00:00Z"));
        assertThat(p.humanApproved()).isTrue();
        assertThat(p.approvedBy()).isEqualTo("a.dupont");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :libs:domain-shared:test --tests ProvenanceTest`
Expected: FAIL — `Provenance` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package dev.sdlc.domain;

import java.time.Instant;
import java.util.List;

/** Anti-hallucination metadata carried by every node (brief §12.5). */
public record Provenance(
        List<String> sourceRefs,
        String generatedBy,
        double confidence,
        List<String> assumptions,
        boolean humanApproved,
        String approvedBy,
        Instant approvedAt) {

    public Provenance {
        sourceRefs = List.copyOf(sourceRefs);
        assumptions = List.copyOf(assumptions);
        if (sourceRefs.isEmpty() && assumptions.isEmpty())
            throw new IllegalArgumentException("artifact must be grounded: sourceRefs or assumptions required");
        if (humanApproved && approvedBy == null)
            throw new IllegalArgumentException("humanApproved requires approvedBy");
    }

    public static Provenance generated(List<String> sourceRefs, String generatedBy,
                                       double confidence, List<String> assumptions) {
        return new Provenance(sourceRefs, generatedBy, confidence, assumptions, false, null, null);
    }

    public Provenance approve(String approver, Instant at) {
        return new Provenance(sourceRefs, generatedBy, confidence, assumptions, true, approver, at);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :libs:domain-shared:test --tests ProvenanceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(domain-shared): provenance with grounding invariant (NFR-GROUND)"
```

---

### Task 4: domain-shared — SDLC events

**Files:**
- Create: `libs/domain-shared/src/main/java/dev/sdlc/domain/event/SdlcEvent.java`, `ArtifactChanged.java`, `ArtifactProposed.java`, `RevalidationRequested.java`
- Test: `libs/domain-shared/src/test/java/dev/sdlc/domain/event/SdlcEventTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SdlcEventTest {
    @Test
    void eventsCarryTheSubjectNode() {
        SdlcEvent changed = new ArtifactChanged(ArtifactId.of("REQ-0012"), "abc123");
        SdlcEvent proposed = new ArtifactProposed(ArtifactId.of("SPEC-0007"));
        SdlcEvent revalidate = new RevalidationRequested(ArtifactId.of("SPEC-0007"),
                List.of(ArtifactId.of("REQ-0012")));
        assertThat(changed.subject().value()).isEqualTo("REQ-0012");
        assertThat(proposed.subject().value()).isEqualTo("SPEC-0007");
        assertThat(revalidate.subject().value()).isEqualTo("SPEC-0007");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :libs:domain-shared:test --tests SdlcEventTest`
Expected: FAIL — event types do not exist.

- [ ] **Step 3: Write minimal implementation**

`SdlcEvent.java`:
```java
package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;

/** Shared event vocabulary; agents coordinate by choreography over these. */
public sealed interface SdlcEvent permits ArtifactChanged, ArtifactProposed, RevalidationRequested {
    ArtifactId subject();
}
```

`ArtifactChanged.java`:
```java
package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;

/** A node's content changed; newBlobSha is the new version. */
public record ArtifactChanged(ArtifactId subject, String newBlobSha) implements SdlcEvent {}
```

`ArtifactProposed.java`:
```java
package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;

/** An agent produced a new artifact awaiting human review. */
public record ArtifactProposed(ArtifactId subject) implements SdlcEvent {}
```

`RevalidationRequested.java`:
```java
package dev.sdlc.domain.event;

import dev.sdlc.domain.ArtifactId;
import java.util.List;

/** Subject became stale because the listed upstream nodes changed. */
public record RevalidationRequested(ArtifactId subject, List<ArtifactId> changedUpstream) implements SdlcEvent {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :libs:domain-shared:test --tests SdlcEventTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(domain-shared): sdlc event vocabulary"
```

### Task 5: traceability-graph — Node, Edge, port

**Files:**
- Create: `libs/traceability-graph/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `include("libs:traceability-graph")`)
- Create: `libs/traceability-graph/src/main/java/dev/sdlc/trace/Node.java`, `Edge.java`, `TraceabilityGraphPort.java`
- Test: `libs/traceability-graph/src/test/java/dev/sdlc/trace/NodeTest.java`

- [ ] **Step 1: Add the module**

`libs/traceability-graph/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
dependencies {
    api(project(":libs:domain-shared"))
    implementation(libs.snakeyaml) // used by Task 7's parser
}
```
In `settings.gradle.kts` add: `include("libs:traceability-graph")`

- [ ] **Step 2: Write the failing test**

```java
package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class NodeTest {
    static final Provenance PROV =
            Provenance.generated(List.of("doc:brief"), "test", 1.0, List.of());

    @Test
    void nodeCannotBeApprovedWithoutHumanApproval() {
        // brief §12.5: a node cannot reach APPROVED without humanApproved = true
        assertThatThrownBy(() -> new Node(ArtifactId.of("REQ-0001"), NodeType.REQUIREMENT,
                "title", "reqs/REQ-0001.md", "sha1", NodeStatus.APPROVED, 1, PROV,
                Instant.EPOCH, Instant.EPOCH))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("humanApproved");
    }

    @Test
    void withContentChangeBumpsVersionAndSha() {
        var node = new Node(ArtifactId.of("REQ-0001"), NodeType.REQUIREMENT, "t",
                "reqs/REQ-0001.md", "sha1", NodeStatus.DRAFT, 1, PROV, Instant.EPOCH, Instant.EPOCH);
        var changed = node.withContentChange("sha2", Instant.parse("2026-06-10T10:00:00Z"));
        assertThat(changed.blobSha()).isEqualTo("sha2");
        assertThat(changed.version()).isEqualTo(2);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :libs:traceability-graph:test --tests NodeTest`
Expected: FAIL — `Node` does not exist.

- [ ] **Step 4: Write minimal implementation**

`Node.java`:
```java
package dev.sdlc.trace;

import dev.sdlc.domain.*;
import java.time.Instant;

/** Graph node: identity, version (= git blob sha), status. Content stays in the file. */
public record Node(ArtifactId id, NodeType type, String title, String repoPath,
                   String blobSha, NodeStatus status, int version, Provenance provenance,
                   Instant createdAt, Instant updatedAt) {

    public Node {
        if (status == NodeStatus.APPROVED && !provenance.humanApproved())
            throw new IllegalArgumentException("APPROVED requires provenance.humanApproved");
    }

    public Node withContentChange(String newBlobSha, Instant at) {
        return new Node(id, type, title, repoPath, newBlobSha, status, version + 1,
                provenance, createdAt, at);
    }

    public Node withStatus(NodeStatus newStatus, Provenance newProvenance, Instant at) {
        return new Node(id, type, title, repoPath, blobSha, newStatus, version,
                newProvenance, createdAt, at);
    }
}
```

`Edge.java`:
```java
package dev.sdlc.trace;

import dev.sdlc.domain.*;
import java.time.Instant;

/** Typed link; upstreamBlobShaAtLink is the to-node's version when last validated. */
public record Edge(String id, EdgeType type, ArtifactId from, ArtifactId to,
                   String upstreamBlobShaAtLink, LinkStatus linkStatus,
                   String establishedBy, Instant validatedAt, String validatedBy) {

    public static Edge current(EdgeType type, ArtifactId from, ArtifactId to,
                               String upstreamSha, String establishedBy, Instant at) {
        return new Edge(from.value() + "->" + to.value() + ":" + type, type, from, to,
                upstreamSha, LinkStatus.CURRENT, establishedBy, at, establishedBy);
    }

    public Edge withStatus(LinkStatus status) {
        return new Edge(id, type, from, to, upstreamBlobShaAtLink, status,
                establishedBy, validatedAt, validatedBy);
    }

    public Edge revalidated(String upstreamSha, String by, Instant at) {
        return new Edge(id, type, from, to, upstreamSha, LinkStatus.CURRENT, establishedBy, at, by);
    }
}
```

`TraceabilityGraphPort.java` (brief §12.9, adapted to these types):
```java
package dev.sdlc.trace;

import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.EdgeType;
import dev.sdlc.domain.event.RevalidationRequested;
import java.util.List;
import java.util.Optional;

public interface TraceabilityGraphPort {
    Optional<Node> get(ArtifactId id);
    void upsert(Node node);
    void link(Edge edge);
    List<Node> downstreamOf(ArtifactId id, EdgeType... types);
    List<Node> staleNodes();
    List<Node> impactOf(ArtifactId changed);
    void revalidate(String edgeId, String validatedBy);
    /** Apply an upstream content change: stale edges, flag downstream, return events to emit. */
    List<RevalidationRequested> applyChange(ArtifactId nodeId, String newBlobSha);
}
```

- [ ] **Step 5: Run test, expect PASS, then commit**

Run: `./gradlew :libs:traceability-graph:test --tests NodeTest`
```bash
git add -A && git commit -m "feat(traceability-graph): node, edge, graph port"
```

---

### Task 6: traceability-graph — in-memory adapter with change propagation

**Files:**
- Create: `libs/traceability-graph/src/main/java/dev/sdlc/trace/InMemoryTraceabilityGraph.java`
- Test: `libs/traceability-graph/src/test/java/dev/sdlc/trace/ChangePropagationTest.java`

- [ ] **Step 1: Write the failing test (UC-0003 is the core scenario)**

```java
package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ChangePropagationTest {
    static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");
    static final Provenance PROV = Provenance.generated(List.of("doc:x"), "test", 1.0, List.of());

    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();

    Node node(String id, NodeType type, NodeStatus status, String sha) {
        var prov = status == NodeStatus.APPROVED ? PROV.approve("tester", T0) : PROV;
        return new Node(ArtifactId.of(id), type, id, id + ".md", sha, status, 1, prov, T0, T0);
    }

    @BeforeEach
    void chain() { // GOAL-0001 <- REQ-0012 <- SPEC-0007 <- STORY-0042
        graph.upsert(node("GOAL-0001", NodeType.GOAL, NodeStatus.APPROVED, "g1"));
        graph.upsert(node("REQ-0012", NodeType.REQUIREMENT, NodeStatus.APPROVED, "r1"));
        graph.upsert(node("SPEC-0007", NodeType.SPECIFICATION, NodeStatus.APPROVED, "s1"));
        graph.upsert(node("STORY-0042", NodeType.BACKLOG_ITEM, NodeStatus.APPROVED, "b1"));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("REQ-0012"), ArtifactId.of("GOAL-0001"), "g1", "test", T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("SPEC-0007"), ArtifactId.of("REQ-0012"), "r1", "test", T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("STORY-0042"), ArtifactId.of("SPEC-0007"), "s1", "test", T0));
    }

    @Test
    void upstreamChangeMarksDirectDependentStaleAndRecursesTransitively() {
        var events = graph.applyChange(ArtifactId.of("REQ-0012"), "r2");

        // direct: SPEC-0007 needs revalidation, its edge is STALE
        assertThat(graph.get(ArtifactId.of("SPEC-0007")).orElseThrow().status())
                .isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        // transitive: STORY-0042 flagged too (flag only — its edge sha untouched)
        assertThat(graph.get(ArtifactId.of("STORY-0042")).orElseThrow().status())
                .isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        // upstream of the change is untouched
        assertThat(graph.get(ArtifactId.of("GOAL-0001")).orElseThrow().status())
                .isEqualTo(NodeStatus.APPROVED);
        // events: one RevalidationRequested per flagged node
        assertThat(events).extracting(e -> e.subject().value())
                .containsExactlyInAnyOrder("SPEC-0007", "STORY-0042");
        // changed node has new sha and bumped version
        var req = graph.get(ArtifactId.of("REQ-0012")).orElseThrow();
        assertThat(req.blobSha()).isEqualTo("r2");
        assertThat(req.version()).isEqualTo(2);
    }

    @Test
    void impactOfAnswersTheHeadlineQuery() {
        assertThat(graph.impactOf(ArtifactId.of("REQ-0012")))
                .extracting(n -> n.id().value())
                .containsExactlyInAnyOrder("SPEC-0007", "STORY-0042");
    }

    @Test
    void revalidationRestampsEdgeToCurrent() {
        graph.applyChange(ArtifactId.of("REQ-0012"), "r2");
        var staleEdgeId = "SPEC-0007->REQ-0012:DERIVES_FROM";
        graph.revalidate(staleEdgeId, "a.dupont");
        assertThat(graph.staleNodes()).extracting(n -> n.id().value())
                .doesNotContain("SPEC-0007");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :libs:traceability-graph:test --tests ChangePropagationTest`
Expected: FAIL — `InMemoryTraceabilityGraph` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package dev.sdlc.trace;

import dev.sdlc.domain.*;
import dev.sdlc.domain.event.RevalidationRequested;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Phase 0 projection. Phase 1 replaces this with a Postgres adapter behind the same port. */
public final class InMemoryTraceabilityGraph implements TraceabilityGraphPort {
    private final Map<ArtifactId, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, Edge> edges = new ConcurrentHashMap<>();

    @Override public Optional<Node> get(ArtifactId id) { return Optional.ofNullable(nodes.get(id)); }
    @Override public void upsert(Node node) { nodes.put(node.id(), node); }
    @Override public void link(Edge edge) { edges.put(edge.id(), edge); }

    @Override
    public List<Node> downstreamOf(ArtifactId id, EdgeType... types) {
        var wanted = types.length == 0 ? EnumSet.allOf(EdgeType.class) : EnumSet.of(types[0], types);
        return edges.values().stream()
                .filter(e -> e.to().equals(id) && wanted.contains(e.type()))
                .map(e -> nodes.get(e.from())).filter(Objects::nonNull).toList();
    }

    @Override
    public List<Node> staleNodes() {
        return nodes.values().stream().filter(n -> n.status() == NodeStatus.NEEDS_REVALIDATION).toList();
    }

    @Override
    public List<Node> impactOf(ArtifactId changed) {
        var impacted = new LinkedHashMap<ArtifactId, Node>();
        collectDownstream(changed, impacted);
        return List.copyOf(impacted.values());
    }

    private void collectDownstream(ArtifactId id, Map<ArtifactId, Node> acc) {
        for (var node : downstreamOf(id, EdgeType.DERIVES_FROM, EdgeType.SATISFIES)) {
            if (acc.putIfAbsent(node.id(), node) == null) collectDownstream(node.id(), acc);
        }
    }

    @Override
    public void revalidate(String edgeId, String validatedBy) {
        var edge = edges.get(edgeId);
        if (edge == null) throw new NoSuchElementException("edge " + edgeId);
        var upstream = nodes.get(edge.to());
        var now = Instant.now();
        edges.put(edgeId, edge.revalidated(upstream.blobSha(), validatedBy, now));
        // if the from-node has no remaining stale inbound deps, clear its flag
        var from = nodes.get(edge.from());
        boolean stillStale = edges.values().stream()
                .anyMatch(e -> e.from().equals(from.id()) && e.linkStatus() == LinkStatus.STALE);
        if (!stillStale && from.status() == NodeStatus.NEEDS_REVALIDATION)
            nodes.put(from.id(), from.withStatus(NodeStatus.APPROVED, from.provenance(), now));
    }

    @Override
    public List<RevalidationRequested> applyChange(ArtifactId nodeId, String newBlobSha) {
        var changed = nodes.get(nodeId);
        if (changed == null) throw new NoSuchElementException("node " + nodeId);
        nodes.put(nodeId, changed.withContentChange(newBlobSha, Instant.now()));

        var events = new ArrayList<RevalidationRequested>();
        // direct dependents whose link sha no longer matches → edge STALE
        edges.values().stream()
                .filter(e -> e.to().equals(nodeId)
                        && (e.type() == EdgeType.DERIVES_FROM || e.type() == EdgeType.SATISFIES)
                        && !e.upstreamBlobShaAtLink().equals(newBlobSha))
                .toList()
                .forEach(e -> edges.put(e.id(), e.withStatus(LinkStatus.STALE)));
        // flag every transitive dependent (flag only, no sha bump — brief §12.6)
        for (var node : impactOf(nodeId)) {
            if (node.status() != NodeStatus.NEEDS_REVALIDATION) {
                nodes.put(node.id(), node.withStatus(NodeStatus.NEEDS_REVALIDATION,
                        node.provenance(), Instant.now()));
                events.add(new RevalidationRequested(node.id(), List.of(nodeId)));
            }
        }
        return events;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :libs:traceability-graph:test --tests ChangePropagationTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(traceability-graph): in-memory projection with change propagation (UC-0003)"
```

### Task 7: traceability-graph — frontmatter parser + projection builder

**Files:**
- Create: `libs/traceability-graph/src/main/java/dev/sdlc/trace/ArtifactFile.java`, `FrontmatterParser.java`, `ProjectionBuilder.java`
- Test: `libs/traceability-graph/src/test/java/dev/sdlc/trace/FrontmatterParserTest.java`, `ProjectionBuilderTest.java`

- [ ] **Step 1: Write the failing parser test**

```java
package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FrontmatterParserTest {
    static final String DOC = """
            ---
            id: SPEC-0007
            type: Specification
            title: Checkout applies regional tax
            status: APPROVED
            derivesFrom: [REQ-0012, UC-0003]
            constrainedBy: [ADR-0002]
            provenance:
              sourceRefs: [conv:2026-06-03#msg42]
              generatedBy: agent-spec@v3
              confidence: 0.82
              assumptions: ["tax rounding follows local jurisdiction rules"]
              humanApproved: true
              approvedBy: a.dupont
            ---
            ## Acceptance criteria
            Scenario: body is not parsed into the graph
            """;

    @Test
    void parsesNodeAndEdgesFromFrontmatter() {
        ArtifactFile artifact = new FrontmatterParser().parse(DOC, "specs/SPEC-0007.md");

        assertThat(artifact.node().id().value()).isEqualTo("SPEC-0007");
        assertThat(artifact.node().type()).isEqualTo(NodeType.SPECIFICATION);
        assertThat(artifact.node().status()).isEqualTo(NodeStatus.APPROVED);
        assertThat(artifact.node().provenance().approvedBy()).isEqualTo("a.dupont");
        // blobSha = git hash-object of the full content
        assertThat(artifact.node().blobSha()).hasSize(40);
        assertThat(artifact.edgeTargets())
                .containsEntry(EdgeType.DERIVES_FROM,
                        java.util.List.of(ArtifactId.of("REQ-0012"), ArtifactId.of("UC-0003")))
                .containsEntry(EdgeType.CONSTRAINS,
                        java.util.List.of(ArtifactId.of("ADR-0002")));
    }

    @Test
    void rejectsDocumentWithoutFrontmatter() {
        assertThatThrownBy(() -> new FrontmatterParser().parse("no frontmatter", "x.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :libs:traceability-graph:test --tests FrontmatterParserTest`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write the implementation**

`ArtifactFile.java`:
```java
package dev.sdlc.trace;

import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.EdgeType;
import java.util.List;
import java.util.Map;

/** A parsed spec-as-code file: the node plus the upstream ids it links to. */
public record ArtifactFile(Node node, Map<EdgeType, List<ArtifactId>> edgeTargets, String body) {}
```

`FrontmatterParser.java`:
```java
package dev.sdlc.trace;

import dev.sdlc.domain.*;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Frontmatter is the canonical source of truth for identity and links (brief §12.1). */
public final class FrontmatterParser {

    @SuppressWarnings("unchecked")
    public ArtifactFile parse(String content, String repoPath) {
        if (!content.startsWith("---"))
            throw new IllegalArgumentException("missing frontmatter: " + repoPath);
        int end = content.indexOf("\n---", 3);
        if (end < 0) throw new IllegalArgumentException("unterminated frontmatter: " + repoPath);
        String yaml = content.substring(3, end);
        int nl = content.indexOf('\n', end + 1);
        String body = nl < 0 ? "" : content.substring(nl + 1);

        Map<String, Object> fm = new Yaml().load(yaml);
        Map<String, Object> prov = (Map<String, Object>) fm.getOrDefault("provenance", Map.of());

        var provenance = new Provenance(
                strings(prov.get("sourceRefs")),
                (String) prov.getOrDefault("generatedBy", "unknown"),
                ((Number) prov.getOrDefault("confidence", 0.0)).doubleValue(),
                strings(prov.get("assumptions")),
                Boolean.TRUE.equals(prov.get("humanApproved")),
                (String) prov.get("approvedBy"),
                null);

        var node = new Node(
                ArtifactId.of((String) fm.get("id")),
                nodeType((String) fm.get("type")),
                (String) fm.get("title"),
                repoPath,
                gitBlobSha(content),
                NodeStatus.valueOf((String) fm.get("status")),
                1, provenance, Instant.now(), Instant.now());

        Map<EdgeType, List<ArtifactId>> edges = new EnumMap<>(EdgeType.class);
        putEdges(edges, EdgeType.DERIVES_FROM, fm.get("derivesFrom"));
        putEdges(edges, EdgeType.CONSTRAINS, fm.get("constrainedBy"));
        putEdges(edges, EdgeType.DEPENDS_ON, fm.get("dependsOn"));
        return new ArtifactFile(node, edges, body);
    }

    private static NodeType nodeType(String value) {
        // frontmatter uses PascalCase (Specification, UseCase); enum is UPPER_SNAKE
        return NodeType.valueOf(value.replaceAll("(?<=[a-z])(?=[A-Z])", "_").toUpperCase(Locale.ROOT));
    }

    private static void putEdges(Map<EdgeType, List<ArtifactId>> map, EdgeType type, Object raw) {
        var ids = strings(raw).stream().map(ArtifactId::of).toList();
        if (!ids.isEmpty()) map.put(type, ids);
    }

    private static List<String> strings(Object raw) {
        if (raw == null) return List.of();
        return ((List<?>) raw).stream().map(Object::toString).toList();
    }

    /** Identical to `git hash-object`: sha1("blob <len>\0<content>"). */
    public static String gitBlobSha(String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
            var sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
```

- [ ] **Step 4: Run parser test, expect PASS**

Run: `./gradlew :libs:traceability-graph:test --tests FrontmatterParserTest`

- [ ] **Step 5: Write the failing projection-builder test**

```java
package dev.sdlc.trace;

import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.LinkStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectionBuilderTest {
    @Test
    void rebuildsGraphFromArtifactDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("REQ-0012.md"), """
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
                """);
        Files.writeString(dir.resolve("SPEC-0007.md"), """
                ---
                id: SPEC-0007
                type: Specification
                title: Checkout applies regional tax
                status: DRAFT
                derivesFrom: [REQ-0012]
                provenance:
                  sourceRefs: [ticket:PROJ-88]
                  generatedBy: agent-spec@v1
                  confidence: 0.8
                  assumptions: []
                  humanApproved: false
                ---
                body
                """);

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(dir, graph);

        assertThat(graph.get(ArtifactId.of("SPEC-0007"))).isPresent();
        assertThat(graph.downstreamOf(ArtifactId.of("REQ-0012")))
                .extracting(n -> n.id().value()).containsExactly("SPEC-0007");
    }
}
```

- [ ] **Step 6: Implement `ProjectionBuilder`**

```java
package dev.sdlc.trace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.stream.Stream;

/** Rebuilds the projection from artifact files; in Phase 0 this runs at startup and after writes. */
public final class ProjectionBuilder {
    private final FrontmatterParser parser;

    public ProjectionBuilder(FrontmatterParser parser) { this.parser = parser; }

    public void rebuild(Path artifactRoot, TraceabilityGraphPort graph) {
        try (Stream<Path> files = Files.walk(artifactRoot)) {
            var artifacts = files.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> {
                        try { return parser.parse(Files.readString(p), artifactRoot.relativize(p).toString()); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }).toList();
            artifacts.forEach(a -> graph.upsert(a.node()));
            for (var artifact : artifacts)
                artifact.edgeTargets().forEach((type, targets) -> targets.forEach(to -> {
                    var upstreamSha = graph.get(to).map(Node::blobSha).orElse("unknown");
                    graph.link(Edge.current(type, artifact.node().id(), to, upstreamSha,
                            "projection", Instant.now()));
                }));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
```

- [ ] **Step 7: Run all module tests, expect PASS, then commit**

Run: `./gradlew :libs:traceability-graph:test`
```bash
git add -A && git commit -m "feat(traceability-graph): frontmatter parser and projection builder (FR-TRACE-4)"
```

### Task 8: agent-core — ports

**Files:**
- Create: `libs/agent-core/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `include("libs:agent-core")`)
- Create in `libs/agent-core/src/main/java/dev/sdlc/agent/port/`: `LanguageModelPort.java`, `Tool.java`, `ToolRegistry.java`, `HumanInTheLoopPort.java`, `ArtifactRepositoryPort.java`, `EventPublisherPort.java`, `RunTracePort.java`
- Test: `libs/agent-core/src/test/java/dev/sdlc/agent/port/ToolRegistryTest.java`

- [ ] **Step 1: Add the module**

`libs/agent-core/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
dependencies { api(project(":libs:domain-shared")) }
```
In `settings.gradle.kts` add: `include("libs:agent-core")`

- [ ] **Step 2: Write the failing test**

```java
package dev.sdlc.agent.port;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ToolRegistryTest {
    Tool echo = new Tool() {
        public String name() { return "echo"; }
        public String description() { return "echoes input"; }
        public Map<String, String> parameterSchema() { return Map.of("text", "string"); }
        public String execute(Map<String, Object> args) { return String.valueOf(args.get("text")); }
    };

    @Test
    void executesRegisteredTool() {
        var registry = new ToolRegistry(java.util.List.of(echo));
        assertThat(registry.execute("echo", Map.of("text", "hi"))).isEqualTo("hi");
        assertThat(registry.schemas()).hasSize(1);
    }

    @Test
    void unknownToolIsAnError() {
        var registry = new ToolRegistry(java.util.List.of());
        assertThatThrownBy(() -> registry.execute("rm-rf", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allow-listed");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :libs:agent-core:test --tests ToolRegistryTest`
Expected: FAIL — classes do not exist.

- [ ] **Step 4: Write the ports**

`LanguageModelPort.java`:
```java
package dev.sdlc.agent.port;

import java.util.List;
import java.util.Map;

/** Outbound port to the LLM. The provider SDK lives in an adapter, never here. */
public interface LanguageModelPort {

    ModelResponse complete(ModelRequest request);

    record ModelRequest(String systemPrompt, List<Message> messages, List<ToolSchema> tools) {}
    record Message(Role role, String content) {}
    enum Role { USER, ASSISTANT, TOOL_RESULT }
    record ToolSchema(String name, String description, Map<String, String> parameters) {}

    /** Either finalText is set (model finished) or toolCalls is non-empty (model wants tools). */
    record ModelResponse(String finalText, List<ToolCall> toolCalls, Usage usage) {
        public boolean wantsTools() { return toolCalls != null && !toolCalls.isEmpty(); }
    }
    record ToolCall(String toolName, Map<String, Object> arguments) {}
    record Usage(long inputTokens, long outputTokens, double costUsd) {}
}
```

`Tool.java`:
```java
package dev.sdlc.agent.port;

import java.util.Map;

/** A capability the model may invoke. Implementations are outbound adapters. */
public interface Tool {
    String name();
    String description();
    Map<String, String> parameterSchema();
    String execute(Map<String, Object> args);
}
```

`ToolRegistry.java`:
```java
package dev.sdlc.agent.port;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Per-agent allow-list of tools (NFR-SEC); the loop executes only through this. */
public final class ToolRegistry {
    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> tools) {
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(Tool::name, Function.identity()));
    }

    public List<LanguageModelPort.ToolSchema> schemas() {
        return tools.values().stream()
                .map(t -> new LanguageModelPort.ToolSchema(t.name(), t.description(), t.parameterSchema()))
                .toList();
    }

    public String execute(String name, Map<String, Object> args) {
        var tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("tool not allow-listed: " + name);
        return tool.execute(args);
    }
}
```

`HumanInTheLoopPort.java`:
```java
package dev.sdlc.agent.port;

import dev.sdlc.domain.ArtifactId;

/** Clarification and approval are the loop, not an afterthought (brief §6). */
public interface HumanInTheLoopPort {
    String askClarifyingQuestion(String question);
    ApprovalDecision requestApproval(ArtifactId artifact, String summary);

    record ApprovalDecision(boolean approved, String reviewer, String feedback) {}
}
```

`ArtifactRepositoryPort.java`:
```java
package dev.sdlc.agent.port;

import java.util.Optional;

/** Spec-as-code persistence: artifacts are versioned files (FR-SAC-1). */
public interface ArtifactRepositoryPort {
    /** Writes the artifact file and returns its blobSha. */
    String write(String repoPath, String content);
    Optional<String> read(String repoPath);
}
```

`EventPublisherPort.java`:
```java
package dev.sdlc.agent.port;

import dev.sdlc.domain.event.SdlcEvent;

public interface EventPublisherPort {
    void publish(SdlcEvent event);
}
```

`RunTracePort.java`:
```java
package dev.sdlc.agent.port;

/** NFR-OBS: every step recorded with tokens and cost. OTel export arrives in Phase 1. */
public interface RunTracePort {
    void step(String runId, String kind, String detail, long inputTokens, long outputTokens, double costUsd);
    void finish(String runId, String outcome);
}
```

- [ ] **Step 5: Run test, expect PASS, then commit**

Run: `./gradlew :libs:agent-core:test`
```bash
git add -A && git commit -m "feat(agent-core): outbound ports and tool registry"
```

---

### Task 9: agent-core — the agent loop with guardrails

**Files:**
- Create: `libs/agent-core/src/main/java/dev/sdlc/agent/AgentLoop.java`, `Guardrails.java`, `AgentRunResult.java`
- Test: `libs/agent-core/src/test/java/dev/sdlc/agent/AgentLoopTest.java`
- Test helper: `libs/agent-core/src/test/java/dev/sdlc/agent/FakeLanguageModel.java` (place in `src/testFixtures/java` if preferred; plain test sources are fine for Phase 0)

- [ ] **Step 1: Write the fake and the failing test**

`FakeLanguageModel.java`:
```java
package dev.sdlc.agent;

import dev.sdlc.agent.port.LanguageModelPort;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Scripted model: returns queued responses in order; records every request. */
public final class FakeLanguageModel implements LanguageModelPort {
    private final Deque<ModelResponse> script = new ArrayDeque<>();
    public final List<ModelRequest> requests = new ArrayList<>();

    public FakeLanguageModel respondWith(ModelResponse... responses) {
        script.addAll(List.of(responses));
        return this;
    }

    @Override public ModelResponse complete(ModelRequest request) {
        requests.add(request);
        if (script.isEmpty()) throw new AssertionError("model called more times than scripted");
        return script.poll();
    }

    public static ModelResponse finalText(String text) {
        return new ModelResponse(text, List.of(), new Usage(100, 50, 0.001));
    }
    public static ModelResponse toolCall(String tool, java.util.Map<String, Object> args) {
        return new ModelResponse(null, List.of(new ToolCall(tool, args)), new Usage(100, 20, 0.001));
    }
}
```

`AgentLoopTest.java`:
```java
package dev.sdlc.agent;

import dev.sdlc.agent.port.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AgentLoopTest {
    Tool echo = new Tool() {
        public String name() { return "echo"; }
        public String description() { return "echo"; }
        public Map<String, String> parameterSchema() { return Map.of("text", "string"); }
        public String execute(Map<String, Object> args) { return "tool says: " + args.get("text"); }
    };
    ToolRegistry registry = new ToolRegistry(List.of(echo));
    RunTracePort noTrace = new RunTracePort() {
        public void step(String r, String k, String d, long i, long o, double c) {}
        public void finish(String r, String o) {}
    };

    @Test
    void returnsFinalTextWhenModelFinishesImmediately() {
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText("done"));
        var loop = new AgentLoop(model, registry, noTrace, new Guardrails(5, 1.00));

        var result = loop.run("run-1", "system", "task");

        assertThat(result.finalText()).isEqualTo("done");
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    void executesToolCallsAndFeedsResultsBack() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("echo", Map.of("text", "hi")),
                FakeLanguageModel.finalText("finished"));
        var loop = new AgentLoop(model, registry, noTrace, new Guardrails(5, 1.00));

        var result = loop.run("run-1", "system", "task");

        assertThat(result.finalText()).isEqualTo("finished");
        // second request must contain the tool result message
        var second = model.requests.get(1);
        assertThat(second.messages())
                .anyMatch(m -> m.role() == LanguageModelPort.Role.TOOL_RESULT
                        && m.content().contains("tool says: hi"));
    }

    @Test
    void stopsAtMaxIterations() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("echo", Map.of("text", "1")),
                FakeLanguageModel.toolCall("echo", Map.of("text", "2")),
                FakeLanguageModel.toolCall("echo", Map.of("text", "3")));
        var loop = new AgentLoop(model, registry, noTrace, new Guardrails(2, 1.00));

        assertThatThrownBy(() -> loop.run("run-1", "system", "task"))
                .isInstanceOf(AgentLoop.GuardrailExceeded.class)
                .hasMessageContaining("iterations");
    }

    @Test
    void stopsAtCostCeiling() {
        var model = new FakeLanguageModel().respondWith(
                FakeLanguageModel.toolCall("echo", Map.of("text", "1")),
                FakeLanguageModel.finalText("never reached"));
        var loop = new AgentLoop(model, registry, noTrace, new Guardrails(5, 0.0005));

        assertThatThrownBy(() -> loop.run("run-1", "system", "task"))
                .isInstanceOf(AgentLoop.GuardrailExceeded.class)
                .hasMessageContaining("cost");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :libs:agent-core:test --tests AgentLoopTest`
Expected: FAIL — `AgentLoop`, `Guardrails`, `AgentRunResult` do not exist.

- [ ] **Step 3: Write the implementation**

`Guardrails.java`:
```java
package dev.sdlc.agent;

/** Hard limits per run (brief §5 step 5). */
public record Guardrails(int maxIterations, double costCeilingUsd) {}
```

`AgentRunResult.java`:
```java
package dev.sdlc.agent;

public record AgentRunResult(String finalText, int iterations, long totalTokens, double totalCostUsd) {}
```

`AgentLoop.java`:
```java
package dev.sdlc.agent;

import dev.sdlc.agent.port.*;
import dev.sdlc.agent.port.LanguageModelPort.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The reasoning loop (brief §5): assemble context → call model → execute requested
 * tools through the registry → feed results back → repeat until final text.
 * Pure application code: no SDK, no HTTP, no framework.
 */
public final class AgentLoop {
    private final LanguageModelPort model;
    private final ToolRegistry tools;
    private final RunTracePort trace;
    private final Guardrails guardrails;

    public AgentLoop(LanguageModelPort model, ToolRegistry tools, RunTracePort trace, Guardrails guardrails) {
        this.model = model; this.tools = tools; this.trace = trace; this.guardrails = guardrails;
    }

    public AgentRunResult run(String runId, String systemPrompt, String task) {
        var messages = new ArrayList<Message>();
        messages.add(new Message(Role.USER, task));
        long tokens = 0; double cost = 0;

        for (int i = 1; i <= guardrails.maxIterations(); i++) {
            var response = model.complete(new ModelRequest(systemPrompt, List.copyOf(messages), tools.schemas()));
            tokens += response.usage().inputTokens() + response.usage().outputTokens();
            cost += response.usage().costUsd();
            trace.step(runId, "model", response.wantsTools() ? "tool-request" : "final",
                    response.usage().inputTokens(), response.usage().outputTokens(),
                    response.usage().costUsd());
            if (cost > guardrails.costCeilingUsd()) {
                trace.finish(runId, "aborted:cost");
                throw new GuardrailExceeded("cost ceiling exceeded: $" + cost);
            }
            if (!response.wantsTools()) {
                trace.finish(runId, "completed");
                return new AgentRunResult(response.finalText(), i, tokens, cost);
            }
            for (var call : response.toolCalls()) {
                String result = tools.execute(call.toolName(), call.arguments());
                trace.step(runId, "tool:" + call.toolName(), result, 0, 0, 0);
                messages.add(new Message(Role.TOOL_RESULT,
                        "[" + call.toolName() + "] " + result));
            }
        }
        trace.finish(runId, "aborted:iterations");
        throw new GuardrailExceeded("max iterations reached: " + guardrails.maxIterations());
    }

    public static final class GuardrailExceeded extends RuntimeException {
        public GuardrailExceeded(String message) { super(message); }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :libs:agent-core:test --tests AgentLoopTest`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(agent-core): agent loop with iteration and cost guardrails"
```

### Task 10: agent-spec — domain model (Specification draft + testability check)

**Files:**
- Create: `agents/agent-spec/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `include("agents:agent-spec")`)
- Create: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/domain/SpecificationDraft.java`, `AcceptanceCriterion.java`, `TestabilityReport.java`
- Test: `agents/agent-spec/src/test/java/dev/sdlc/agentspec/domain/SpecificationDraftTest.java`

- [ ] **Step 1: Add the module**

`agents/agent-spec/build.gradle.kts`:
```kotlin
plugins { id("sdlc.java-conventions") }
dependencies {
    implementation(project(":libs:domain-shared"))
    implementation(project(":libs:agent-core"))
    implementation(project(":libs:traceability-graph"))
    testImplementation(libs.archunit)
}
```
In `settings.gradle.kts` add: `include("agents:agent-spec")`

- [ ] **Step 2: Write the failing test**

```java
package dev.sdlc.agentspec.domain;

import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SpecificationDraftTest {
    AcceptanceCriterion gherkin = new AcceptanceCriterion(
            "Checkout applies regional tax",
            """
            Given a cart with items shipped to region FR
            When the customer checks out
            Then VAT at the FR rate is added to the total
            """);

    @Test
    void draftRequiresAtLeastOneAcceptanceCriterion() {
        // FR-SPEC-2/3: a spec without criteria is not testable
        assertThatThrownBy(() -> new SpecificationDraft(
                ArtifactId.of("SPEC-0007"), "Checkout tax",
                List.of(ArtifactId.of("REQ-0012")), List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("acceptance criterion");
    }

    @Test
    void draftRequiresAtLeastOneSourceRequirement() {
        // FR-SPEC-1: a spec must derive from approved requirements
        assertThatThrownBy(() -> new SpecificationDraft(
                ArtifactId.of("SPEC-0007"), "Checkout tax",
                List.of(), List.of(gherkin), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("derives");
    }

    @Test
    void rendersMarkdownBodyWithGherkin() {
        var draft = new SpecificationDraft(ArtifactId.of("SPEC-0007"), "Checkout tax",
                List.of(ArtifactId.of("REQ-0012")), List.of(gherkin),
                List.of("rounding follows jurisdiction rules"));
        String body = draft.renderBody();
        assertThat(body).contains("## Acceptance criteria")
                .contains("Scenario: Checkout applies regional tax")
                .contains("Given a cart")
                .contains("## Constraints");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :agents:agent-spec:test --tests SpecificationDraftTest`
Expected: FAIL — classes do not exist.

- [ ] **Step 4: Write the implementation**

`AcceptanceCriterion.java`:
```java
package dev.sdlc.agentspec.domain;

/** One Gherkin scenario: a name plus Given/When/Then steps. */
public record AcceptanceCriterion(String scenario, String steps) {
    public AcceptanceCriterion {
        if (scenario == null || scenario.isBlank())
            throw new IllegalArgumentException("scenario name required");
        if (steps == null || !steps.contains("Given") || !steps.contains("When") || !steps.contains("Then"))
            throw new IllegalArgumentException("steps must contain Given/When/Then");
    }
}
```

`SpecificationDraft.java`:
```java
package dev.sdlc.agentspec.domain;

import dev.sdlc.domain.ArtifactId;
import java.util.List;

/** What the Specification agent produces before human review. */
public record SpecificationDraft(ArtifactId id, String title, List<ArtifactId> derivesFrom,
                                 List<AcceptanceCriterion> criteria, List<String> constraints) {
    public SpecificationDraft {
        derivesFrom = List.copyOf(derivesFrom);
        criteria = List.copyOf(criteria);
        constraints = List.copyOf(constraints);
        if (criteria.isEmpty())
            throw new IllegalArgumentException("spec needs at least one acceptance criterion");
        if (derivesFrom.isEmpty())
            throw new IllegalArgumentException("spec derives from at least one requirement");
    }

    public String renderBody() {
        var sb = new StringBuilder("## Acceptance criteria\n\n");
        for (var c : criteria)
            sb.append("Scenario: ").append(c.scenario()).append('\n')
              .append(c.steps().strip()).append("\n\n");
        if (!constraints.isEmpty()) {
            sb.append("## Constraints\n\n");
            constraints.forEach(c -> sb.append("- ").append(c).append('\n'));
        }
        return sb.toString();
    }
}
```

`TestabilityReport.java`:
```java
package dev.sdlc.agentspec.domain;

import dev.sdlc.domain.ArtifactId;
import java.util.List;

/** FR-SPEC-3: requirements the agent could not make testable, surfaced not silenced. */
public record TestabilityReport(List<ArtifactId> covered, List<Flag> flags) {
    public record Flag(ArtifactId requirement, String reason) {}
    public boolean clean() { return flags.isEmpty(); }
}
```

- [ ] **Step 5: Run test, expect PASS, then commit**

Run: `./gradlew :agents:agent-spec:test --tests SpecificationDraftTest`
```bash
git add -A && git commit -m "feat(agent-spec): specification domain model"
```

---

### Task 11: agent-spec — GenerateSpecification use case

**Files:**
- Create: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/application/GenerateSpecificationUseCase.java`, `SpecDraftParser.java`
- Test: `agents/agent-spec/src/test/java/dev/sdlc/agentspec/application/GenerateSpecificationUseCaseTest.java`

The use case (FR-SPEC-1/2, UC-0002): verify the input Requirement/UseCase nodes are `APPROVED` → run the agent loop with their file contents in the prompt → parse the model's final JSON into a `SpecificationDraft` → write the artifact file (frontmatter + Gherkin body) → upsert node + `DERIVES_FROM` edges as `PROPOSED` → emit `ArtifactProposed`.

The model is instructed to answer with a JSON object:
```json
{"title": "...", "criteria": [{"scenario": "...", "steps": "Given ...\nWhen ...\nThen ..."}],
 "constraints": ["..."], "assumptions": ["..."], "untestable": [{"id": "REQ-0099", "reason": "..."}]}
```

- [ ] **Step 1: Write `SpecDraftParser` test first (pure parsing, no mocks)**

```java
package dev.sdlc.agentspec.application;

import dev.sdlc.domain.ArtifactId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SpecDraftParserTest {
    static final String JSON = """
            {"title": "Checkout applies regional tax",
             "criteria": [{"scenario": "FR VAT", "steps": "Given a FR cart\\nWhen checkout\\nThen VAT added"}],
             "constraints": ["rounding per jurisdiction"],
             "assumptions": ["single currency"],
             "untestable": []}
            """;

    @Test
    void parsesModelJsonIntoDraft() {
        var parsed = new SpecDraftParser().parse(JSON, ArtifactId.of("SPEC-0001"),
                List.of(ArtifactId.of("REQ-0012")));
        assertThat(parsed.draft().title()).isEqualTo("Checkout applies regional tax");
        assertThat(parsed.draft().criteria()).hasSize(1);
        assertThat(parsed.assumptions()).containsExactly("single currency");
        assertThat(parsed.report().clean()).isTrue();
    }

    @Test
    void rejectsUnparseableOutput() {
        assertThatThrownBy(() -> new SpecDraftParser().parse("sorry, here is prose",
                ArtifactId.of("SPEC-0001"), List.of(ArtifactId.of("REQ-0012"))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Implement `SpecDraftParser`**

Add a minimal-JSON dependency to keep `application` framework-free: use `jakarta.json` (`org.eclipse.parsson:parsson`) — add to the catalog:
```toml
parsson = { module = "org.eclipse.parsson:parsson", version = "1.1.7" }
jakarta-json = { module = "jakarta.json:jakarta.json-api", version = "2.1.3" }
```
and to `agents/agent-spec/build.gradle.kts`:
```kotlin
implementation(libs.jakarta.json)
runtimeOnly(libs.parsson)
```

```java
package dev.sdlc.agentspec.application;

import dev.sdlc.agentspec.domain.*;
import dev.sdlc.domain.ArtifactId;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.StringReader;
import java.util.List;

/** Parses the model's final JSON answer; tolerates a ```json fence around it. */
public final class SpecDraftParser {

    public record Parsed(SpecificationDraft draft, List<String> assumptions, TestabilityReport report) {}

    public Parsed parse(String modelOutput, ArtifactId specId, List<ArtifactId> derivesFrom) {
        String json = modelOutput.strip();
        if (json.startsWith("```"))
            json = json.substring(json.indexOf('\n') + 1, json.lastIndexOf("```")).strip();
        JsonObject root;
        try (var reader = Json.createReader(new StringReader(json))) {
            root = reader.readObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("model output is not the expected JSON: " + e.getMessage(), e);
        }
        var criteria = root.getJsonArray("criteria").stream()
                .map(v -> v.asJsonObject())
                .map(o -> new AcceptanceCriterion(o.getString("scenario"), o.getString("steps")))
                .toList();
        var constraints = root.getJsonArray("constraints").stream()
                .map(v -> ((jakarta.json.JsonString) v).getString()).toList();
        var assumptions = root.getJsonArray("assumptions").stream()
                .map(v -> ((jakarta.json.JsonString) v).getString()).toList();
        var flags = root.getJsonArray("untestable").stream()
                .map(v -> v.asJsonObject())
                .map(o -> new TestabilityReport.Flag(ArtifactId.of(o.getString("id")), o.getString("reason")))
                .toList();
        var draft = new SpecificationDraft(specId, root.getString("title"), derivesFrom, criteria, constraints);
        return new Parsed(draft, assumptions, new TestabilityReport(derivesFrom, flags));
    }
}
```

Run: `./gradlew :agents:agent-spec:test --tests SpecDraftParserTest` → PASS, then commit:
```bash
git add -A && git commit -m "feat(agent-spec): parse model output into specification draft"
```

- [ ] **Step 3: Write the failing use-case test (all ports faked)**

```java
package dev.sdlc.agentspec.application;

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

class GenerateSpecificationUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");

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
    EventPublisherPort events = published::add;
    RunTracePort noTrace = new RunTracePort() {
        public void step(String r, String k, String d, long i, long o, double c) {}
        public void finish(String r, String o) {}
    };

    Node approvedReq() {
        var prov = Provenance.generated(List.of("ticket:PROJ-88"), "human", 1.0, List.of())
                .approve("a.dupont", T0);
        return new Node(ArtifactId.of("REQ-0012"), NodeType.REQUIREMENT, "Regional tax",
                "requirements/REQ-0012.md", "r1", NodeStatus.APPROVED, 1, prov, T0, T0);
    }

    String modelJson = """
            {"title": "Checkout applies regional tax",
             "criteria": [{"scenario": "FR VAT", "steps": "Given a FR cart\\nWhen checkout\\nThen VAT added"}],
             "constraints": [], "assumptions": ["single currency"], "untestable": []}
            """;

    GenerateSpecificationUseCase useCase(LanguageModelPort model) {
        files.put("requirements/REQ-0012.md", "---\nid: REQ-0012\n---\nRegional tax body");
        return new GenerateSpecificationUseCase(
                new AgentLoop(model, new ToolRegistry(List.of()), noTrace, new Guardrails(5, 1.0)),
                graph, repo, events, new SpecDraftParser(), "agent-spec@v1");
    }

    @Test
    void producesProposedSpecWithEdgesAndEvent() {
        graph.upsert(approvedReq());
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(modelJson));

        var specId = useCase(model).generate(List.of(ArtifactId.of("REQ-0012")));

        // node exists, PROPOSED, grounded provenance
        var spec = graph.get(specId).orElseThrow();
        assertThat(spec.status()).isEqualTo(NodeStatus.PROPOSED);
        assertThat(spec.provenance().humanApproved()).isFalse();
        assertThat(spec.provenance().sourceRefs()).contains("REQ-0012@r1");
        // file written with frontmatter + Gherkin
        var content = files.get(spec.repoPath());
        assertThat(content).startsWith("---").contains("Scenario: FR VAT");
        // DERIVES_FROM edge pins the upstream sha
        assertThat(graph.downstreamOf(ArtifactId.of("REQ-0012")))
                .extracting(Node::id).containsExactly(specId);
        // event emitted
        assertThat(published).containsExactly(new ArtifactProposed(specId));
        // prompt contained the requirement's file content
        assertThat(model.requests.getFirst().messages().getFirst().content())
                .contains("Regional tax body");
    }

    @Test
    void refusesUnapprovedInputs() {
        var draftReq = approvedReq().withStatus(NodeStatus.DRAFT,
                Provenance.generated(List.of("t"), "h", 1.0, List.of()), T0);
        graph.upsert(draftReq);
        var model = new FakeLanguageModel();

        assertThatThrownBy(() -> useCase(model).generate(List.of(ArtifactId.of("REQ-0012"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPROVED");
        assertThat(model.requests).isEmpty(); // never called the model
    }
}
```

Note: `FakeLanguageModel` moves to `libs/agent-core` test-fixtures so `agent-spec` can use it. In `libs/agent-core/build.gradle.kts` add `` `java-test-fixtures` `` to `plugins`, move the fake to `libs/agent-core/src/testFixtures/java/dev/sdlc/agent/FakeLanguageModel.java`, and in `agents/agent-spec/build.gradle.kts` add `testImplementation(testFixtures(project(":libs:agent-core")))`. Also make `FrontmatterParser.gitBlobSha` `public static`.

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :agents:agent-spec:test --tests GenerateSpecificationUseCaseTest`
Expected: FAIL — `GenerateSpecificationUseCase` does not exist.

- [ ] **Step 5: Write the implementation**

```java
package dev.sdlc.agentspec.application;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agentspec.domain.SpecificationDraft;
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
        String content = renderFile(draft, provenance, sources);
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

    private String renderFile(SpecificationDraft draft, Provenance prov, List<Node> sources) {
        String derives = draft.derivesFrom().stream().map(ArtifactId::value)
                .collect(Collectors.joining(", ", "[", "]"));
        String refs = prov.sourceRefs().stream().collect(Collectors.joining(", ", "[", "]"));
        String assumptions = prov.assumptions().stream()
                .map(a -> "\"" + a + "\"").collect(Collectors.joining(", ", "[", "]"));
        return """
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
                %s""".formatted(draft.id().value(), draft.title(), derives, refs,
                prov.generatedBy(), prov.confidence(), assumptions, draft.renderBody());
    }
}
```

- [ ] **Step 6: Run tests, expect PASS, then commit**

Run: `./gradlew :agents:agent-spec:test :libs:agent-core:test`
```bash
git add -A && git commit -m "feat(agent-spec): generate-specification use case (UC-0002)"
```

---

### Task 12: agent-spec — human approval gate

**Files:**
- Create: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/application/ApproveArtifactUseCase.java`
- Test: `agents/agent-spec/src/test/java/dev/sdlc/agentspec/application/ApproveArtifactUseCaseTest.java`

- [ ] **Step 1: Write the failing test (UC-0005)**

```java
package dev.sdlc.agentspec.application;

import dev.sdlc.agent.port.HumanInTheLoopPort;
import dev.sdlc.domain.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ApproveArtifactUseCaseTest {
    static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");
    InMemoryTraceabilityGraph graph = new InMemoryTraceabilityGraph();
    ArtifactId specId = ArtifactId.of("SPEC-0001");

    @BeforeEach
    void proposedSpec() {
        var prov = Provenance.generated(List.of("REQ-0012@r1"), "agent-spec@v1", 0.8, List.of());
        graph.upsert(new Node(specId, NodeType.SPECIFICATION, "t", "specs/SPEC-0001.md",
                "s1", NodeStatus.PROPOSED, 1, prov, T0, T0));
    }

    HumanInTheLoopPort decide(boolean approved, String feedback) {
        return new HumanInTheLoopPort() {
            public String askClarifyingQuestion(String q) { return ""; }
            public ApprovalDecision requestApproval(ArtifactId a, String s) {
                return new ApprovalDecision(approved, "a.dupont", feedback);
            }
        };
    }

    @Test
    void approvalTransitionsToApprovedWithRecordedApprover() {
        var result = new ApproveArtifactUseCase(graph, decide(true, null), () -> T0).review(specId);

        var node = graph.get(specId).orElseThrow();
        assertThat(node.status()).isEqualTo(NodeStatus.APPROVED);
        assertThat(node.provenance().humanApproved()).isTrue();
        assertThat(node.provenance().approvedBy()).isEqualTo("a.dupont");
        assertThat(result.approved()).isTrue();
    }

    @Test
    void rejectionReturnsToDraftAndKeepsFeedback() {
        var result = new ApproveArtifactUseCase(graph, decide(false, "criteria too vague"), () -> T0)
                .review(specId);

        assertThat(graph.get(specId).orElseThrow().status()).isEqualTo(NodeStatus.DRAFT);
        assertThat(result.feedback()).isEqualTo("criteria too vague");
    }

    @Test
    void onlyProposedArtifactsCanBeReviewed() {
        graph.upsert(graph.get(specId).orElseThrow().withStatus(NodeStatus.DRAFT,
                graph.get(specId).orElseThrow().provenance(), T0));
        assertThatThrownBy(() -> new ApproveArtifactUseCase(graph, decide(true, null), () -> T0)
                .review(specId))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROPOSED");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agents:agent-spec:test --tests ApproveArtifactUseCaseTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the implementation**

```java
package dev.sdlc.agentspec.application;

import dev.sdlc.agent.port.HumanInTheLoopPort;
import dev.sdlc.agent.port.HumanInTheLoopPort.ApprovalDecision;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.NodeStatus;
import dev.sdlc.trace.TraceabilityGraphPort;

import java.time.Instant;
import java.util.function.Supplier;

/** FR-HITL-1 / UC-0005: no node reaches APPROVED without a recorded human approver. */
public final class ApproveArtifactUseCase {
    private final TraceabilityGraphPort graph;
    private final HumanInTheLoopPort human;
    private final Supplier<Instant> clock;

    public ApproveArtifactUseCase(TraceabilityGraphPort graph, HumanInTheLoopPort human,
                                  Supplier<Instant> clock) {
        this.graph = graph; this.human = human; this.clock = clock;
    }

    public ApprovalDecision review(ArtifactId id) {
        var node = graph.get(id).orElseThrow(() -> new IllegalStateException("unknown node " + id));
        if (node.status() != NodeStatus.PROPOSED)
            throw new IllegalStateException(id + " is " + node.status() + ", expected PROPOSED");

        var decision = human.requestApproval(id, node.title());
        var now = clock.get();
        if (decision.approved()) {
            graph.upsert(node.withStatus(NodeStatus.APPROVED,
                    node.provenance().approve(decision.reviewer(), now), now));
        } else {
            graph.upsert(node.withStatus(NodeStatus.DRAFT, node.provenance(), now));
        }
        return decision;
    }
}
```

- [ ] **Step 4: Run test, expect PASS, then commit**

Run: `./gradlew :agents:agent-spec:test --tests ApproveArtifactUseCaseTest`
```bash
git add -A && git commit -m "feat(agent-spec): human approval gate (UC-0005, FR-HITL-1)"
```

### Task 13: agent-spec — change-propagation handler (closes UC-0003 at app level)

**Files:**
- Create: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/application/ArtifactChangedHandler.java`
- Test: `agents/agent-spec/src/test/java/dev/sdlc/agentspec/application/ArtifactChangedHandlerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.sdlc.agentspec.application;

import dev.sdlc.domain.*;
import dev.sdlc.domain.event.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ArtifactChangedHandlerTest {
    static final Instant T0 = Instant.parse("2026-06-10T10:00:00Z");

    @Test
    void changeEventStalesDownstreamAndEmitsRevalidationRequests() {
        var graph = new InMemoryTraceabilityGraph();
        var prov = Provenance.generated(List.of("x"), "h", 1.0, List.of()).approve("a.dupont", T0);
        graph.upsert(new Node(ArtifactId.of("REQ-0012"), NodeType.REQUIREMENT, "r",
                "requirements/REQ-0012.md", "r1", NodeStatus.APPROVED, 1, prov, T0, T0));
        graph.upsert(new Node(ArtifactId.of("SPEC-0001"), NodeType.SPECIFICATION, "s",
                "specs/SPEC-0001.md", "s1", NodeStatus.APPROVED, 1, prov, T0, T0));
        graph.link(Edge.current(EdgeType.DERIVES_FROM, ArtifactId.of("SPEC-0001"),
                ArtifactId.of("REQ-0012"), "r1", "test", T0));
        List<SdlcEvent> published = new ArrayList<>();

        var handler = new ArtifactChangedHandler(graph, published::add);
        handler.on(new ArtifactChanged(ArtifactId.of("REQ-0012"), "r2"));

        assertThat(graph.get(ArtifactId.of("SPEC-0001")).orElseThrow().status())
                .isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        assertThat(published).singleElement().isInstanceOf(RevalidationRequested.class);
        // idempotent: redelivery emits nothing new (NFR-RELY)
        handler.on(new ArtifactChanged(ArtifactId.of("REQ-0012"), "r2"));
        assertThat(published).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agents:agent-spec:test --tests ArtifactChangedHandlerTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the implementation**

```java
package dev.sdlc.agentspec.application;

import dev.sdlc.agent.port.EventPublisherPort;
import dev.sdlc.domain.event.ArtifactChanged;
import dev.sdlc.trace.TraceabilityGraphPort;

/** FR-TRACE-2: inbound ArtifactChanged → propagate staleness → emit RevalidationRequested. */
public final class ArtifactChangedHandler {
    private final TraceabilityGraphPort graph;
    private final EventPublisherPort events;

    public ArtifactChangedHandler(TraceabilityGraphPort graph, EventPublisherPort events) {
        this.graph = graph; this.events = events;
    }

    public void on(ArtifactChanged event) {
        // applyChange only flags nodes not already NEEDS_REVALIDATION → redelivery is a no-op
        graph.applyChange(event.subject(), event.newBlobSha())
             .forEach(events::publish);
    }
}
```

- [ ] **Step 4: Run test, expect PASS, then commit**

Run: `./gradlew :agents:agent-spec:test --tests ArtifactChangedHandlerTest`
```bash
git add -A && git commit -m "feat(agent-spec): artifact-changed handler with idempotent propagation"
```

---

### Task 14: agent-spec — outbound adapters (files, console HITL, in-process events, logging trace)

**Files:**
- Create in `agents/agent-spec/src/main/java/dev/sdlc/agentspec/adapters/outbound/`: `FileArtifactRepository.java`, `ConsoleHumanInTheLoop.java`, `InProcessEventPublisher.java`, `LoggingRunTrace.java`
- Test: `agents/agent-spec/src/test/java/dev/sdlc/agentspec/adapters/outbound/FileArtifactRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.sdlc.agentspec.adapters.outbound;

import dev.sdlc.trace.FrontmatterParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class FileArtifactRepositoryTest {
    @Test
    void writesFileAndReturnsGitCompatibleBlobSha(@TempDir Path root) {
        var repo = new FileArtifactRepository(root);
        String sha = repo.write("specs/SPEC-0001.md", "content\n");

        assertThat(root.resolve("specs/SPEC-0001.md")).exists();
        assertThat(sha).isEqualTo(FrontmatterParser.gitBlobSha("content\n"));
        assertThat(repo.read("specs/SPEC-0001.md")).contains("content\n");
        assertThat(repo.read("missing.md")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agents:agent-spec:test --tests FileArtifactRepositoryTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the adapters**

`FileArtifactRepository.java`:
```java
package dev.sdlc.agentspec.adapters.outbound;

import dev.sdlc.agent.port.ArtifactRepositoryPort;
import dev.sdlc.trace.FrontmatterParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Optional;

public final class FileArtifactRepository implements ArtifactRepositoryPort {
    private final Path root;

    public FileArtifactRepository(Path root) { this.root = root; }

    @Override public String write(String repoPath, String content) {
        try {
            Path target = root.resolve(repoPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return FrontmatterParser.gitBlobSha(content);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public Optional<String> read(String repoPath) {
        try {
            Path target = root.resolve(repoPath);
            return Files.exists(target) ? Optional.of(Files.readString(target)) : Optional.empty();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
```

`ConsoleHumanInTheLoop.java`:
```java
package dev.sdlc.agentspec.adapters.outbound;

import dev.sdlc.agent.port.HumanInTheLoopPort;
import dev.sdlc.domain.ArtifactId;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Phase 0 HITL: stdin. Phase 1 replaces this with PR review / chat without touching the app. */
public final class ConsoleHumanInTheLoop implements HumanInTheLoopPort {
    private final BufferedReader in;
    private final String reviewer;

    public ConsoleHumanInTheLoop(BufferedReader in, String reviewer) {
        this.in = in; this.reviewer = reviewer;
    }

    @Override public String askClarifyingQuestion(String question) {
        System.out.println("\n[clarification needed] " + question);
        System.out.print("> ");
        return readLine();
    }

    @Override public ApprovalDecision requestApproval(ArtifactId artifact, String summary) {
        System.out.println("\n[approval requested] " + artifact.value() + " — " + summary);
        System.out.print("approve? (y/n + optional feedback): ");
        String line = readLine();
        boolean approved = line.trim().toLowerCase().startsWith("y");
        String feedback = line.length() > 1 ? line.substring(1).trim() : null;
        return new ApprovalDecision(approved, reviewer, feedback);
    }

    private String readLine() {
        try { return in.readLine(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
```

`InProcessEventPublisher.java`:
```java
package dev.sdlc.agentspec.adapters.outbound;

import dev.sdlc.agent.port.EventPublisherPort;
import dev.sdlc.domain.event.SdlcEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Phase 0 bus: in-process dispatch + an inspectable log. Phase 2 swaps in real messaging. */
public final class InProcessEventPublisher implements EventPublisherPort {
    private final List<Consumer<SdlcEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final List<SdlcEvent> log = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<SdlcEvent> subscriber) { subscribers.add(subscriber); }
    public List<SdlcEvent> log() { return List.copyOf(log); }

    @Override public void publish(SdlcEvent event) {
        log.add(event);
        subscribers.forEach(s -> s.accept(event));
    }
}
```

`LoggingRunTrace.java`:
```java
package dev.sdlc.agentspec.adapters.outbound;

import dev.sdlc.agent.port.RunTracePort;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** NFR-OBS Phase 0: per-run token/cost counters, printed at finish. OTel exporter in Phase 1. */
public final class LoggingRunTrace implements RunTracePort {
    private record Counters(LongAdder tokens, DoubleAdder cost) {}
    private final Map<String, Counters> runs = new ConcurrentHashMap<>();

    @Override public void step(String runId, String kind, String detail,
                               long inputTokens, long outputTokens, double costUsd) {
        var c = runs.computeIfAbsent(runId, r -> new Counters(new LongAdder(), new DoubleAdder()));
        c.tokens().add(inputTokens + outputTokens);
        c.cost().add(costUsd);
        System.out.printf("[trace %s] %s in=%d out=%d cost=$%.4f%n",
                runId, kind, inputTokens, outputTokens, costUsd);
    }

    @Override public void finish(String runId, String outcome) {
        var c = runs.getOrDefault(runId, new Counters(new LongAdder(), new DoubleAdder()));
        System.out.printf("[trace %s] %s — total tokens=%d cost=$%.4f%n",
                runId, outcome, c.tokens().sum(), c.cost().doubleValue());
    }
}
```

- [ ] **Step 4: Run tests, expect PASS, then commit**

Run: `./gradlew :agents:agent-spec:test`
```bash
git add -A && git commit -m "feat(agent-spec): file, console-HITL, event and trace adapters"
```

---

### Task 15: ArchUnit — enforce hexagonal boundaries (NFR-TEST)

**Files:**
- Test: `agents/agent-spec/src/test/java/dev/sdlc/agentspec/HexagonalArchitectureTest.java`

- [ ] **Step 1: Write the rules**

```java
package dev.sdlc.agentspec;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "dev.sdlc")
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnNothingOutside = noClasses()
            .that().resideInAPackage("..agentspec.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..agentspec.application..", "..agentspec.adapters..",
                    "..agentspec.bootstrap..", "org.springframework..");

    @ArchTest
    static final ArchRule applicationNeverTouchesAdapters = noClasses()
            .that().resideInAPackage("..agentspec.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..agentspec.adapters..", "..agentspec.bootstrap..", "org.springframework..");

    @ArchTest
    static final ArchRule coreLibsAreFrameworkFree = noClasses()
            .that().resideInAnyPackage("dev.sdlc.domain..", "dev.sdlc.trace..", "dev.sdlc.agent..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "dev.langchain4j..", "org.springframework.ai..");
}
```

- [ ] **Step 2: Run, expect PASS (the structure was built this way), then commit**

Run: `./gradlew :agents:agent-spec:test --tests HexagonalArchitectureTest`
Expected: PASS. If any rule fails, fix the offending dependency — do not weaken the rule.

```bash
git add -A && git commit -m "test: archunit rules enforce hexagonal boundaries (NFR-TEST)"
```

### Task 16: agent-spec — bootstrap wiring + end-to-end demo

**Files:**
- Modify: `agents/agent-spec/build.gradle.kts` (add Spring Boot + Spring AI to `bootstrap` only)
- Create: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/adapters/outbound/SpringAiLanguageModel.java`
- Create: `agents/agent-spec/src/main/java/dev/sdlc/agentspec/bootstrap/AgentSpecApplication.java`
- Create: `agents/agent-spec/src/main/resources/application.yaml`
- Create: `workspace/requirements/REQ-0001.md` (seed artifact for the demo)
- Test: `agents/agent-spec/src/test/java/dev/sdlc/agentspec/bootstrap/EndToEndTest.java`

- [ ] **Step 1: Add bootstrap dependencies**

In `gradle/libs.versions.toml` `[libraries]` add:
```toml
spring-boot-starter = { module = "org.springframework.boot:spring-boot-starter", version.ref = "springBoot" }
spring-ai-anthropic = { module = "org.springframework.ai:spring-ai-starter-model-anthropic", version.ref = "springAi" }
```
In `agents/agent-spec/build.gradle.kts`:
```kotlin
plugins {
    id("sdlc.java-conventions")
    alias(libs.plugins.spring.boot)   // version comes from the catalog (springBoot)
}
repositories {
    mavenCentral()
    maven("https://repo.spring.io/milestone") { // Spring AI 2.0 milestones; drop once 2.0 GA is out
        content { includeGroupByRegex("org\\.springframework\\.ai.*") }
    }
}
dependencies {
    // existing deps unchanged
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.ai.anthropic)
}
```
(The `spring-boot` plugin alias lives in the version catalog's `[plugins]` section, added in Task 1's review pass.)

Spring AI 2.0 is a milestone release, accepted deliberately: only the `bootstrap` package and the one `SpringAiLanguageModel` adapter touch it, so a breaking change before GA cannot reach domain or application code (NFR-PORT). If `./gradlew :agents:agent-spec:compileJava` flags renamed Spring AI types in the adapter, fix the adapter only — check the [Spring AI 2.0 docs](https://docs.spring.io/spring-ai/reference/) for the current `ChatModel`/`Prompt` API.

- [ ] **Step 2: Write the LLM adapter**

`SpringAiLanguageModel.java`:
```java
package dev.sdlc.agentspec.adapters.outbound;

import dev.sdlc.agent.port.LanguageModelPort;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 0 adapter: text-only bridge to Spring AI (the spec agent needs no tools yet —
 * its ToolRegistry is empty). Native tool-calling support lands when an agent needs it.
 */
public final class SpringAiLanguageModel implements LanguageModelPort {
    private final ChatModel chatModel;

    public SpringAiLanguageModel(ChatModel chatModel) { this.chatModel = chatModel; }

    @Override public ModelResponse complete(ModelRequest request) {
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(request.systemPrompt()));
        for (var m : request.messages()) {
            switch (m.role()) {
                case USER, TOOL_RESULT -> messages.add(new UserMessage(m.content()));
                case ASSISTANT -> messages.add(new AssistantMessage(m.content()));
            }
        }
        var response = chatModel.call(new Prompt(messages));
        var usage = response.getMetadata().getUsage();
        return new ModelResponse(response.getResult().getOutput().getText(), List.of(),
                new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), 0.0));
    }
}
```
Note: cost stays 0.0 here; pricing lookup is a Phase 1 concern (NFR-COST tracks tokens now).

- [ ] **Step 3: Write the end-to-end test (fake model, real everything else)**

This is the Phase 0 definition-of-done in one test: seed approved requirement → generate spec → approve → mutate requirement → propagation.

```java
package dev.sdlc.agentspec.bootstrap;

import dev.sdlc.agent.*;
import dev.sdlc.agent.port.*;
import dev.sdlc.agentspec.adapters.outbound.*;
import dev.sdlc.agentspec.application.*;
import dev.sdlc.domain.*;
import dev.sdlc.domain.event.*;
import dev.sdlc.trace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndTest {
    static final String REQ = """
            ---
            id: REQ-0001
            type: Requirement
            title: Regional tax at checkout
            status: APPROVED
            provenance:
              sourceRefs: [ticket:PROJ-88]
              generatedBy: human
              confidence: 1.0
              assumptions: []
              humanApproved: true
              approvedBy: a.dupont
            ---
            Checkout must apply the tax rate of the shipping region.
            """;
    static final String MODEL_JSON = """
            {"title": "Checkout applies regional tax",
             "criteria": [{"scenario": "FR VAT", "steps": "Given a FR cart\\nWhen checkout\\nThen VAT added"}],
             "constraints": [], "assumptions": [], "untestable": []}
            """;

    @Test
    void phase0DefinitionOfDone(@TempDir Path workspace) throws Exception {
        // --- assemble (what bootstrap wiring does) ---
        Files.createDirectories(workspace.resolve("requirements"));
        Files.writeString(workspace.resolve("requirements/REQ-0001.md"), REQ);

        var graph = new InMemoryTraceabilityGraph();
        new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, graph);

        var repo = new FileArtifactRepository(workspace);
        var bus = new InProcessEventPublisher();
        bus.subscribe(e -> { if (e instanceof ArtifactChanged c)
                new ArtifactChangedHandler(graph, bus).on(c); });
        var model = new FakeLanguageModel().respondWith(FakeLanguageModel.finalText(MODEL_JSON));
        var loop = new AgentLoop(model, new ToolRegistry(List.of()),
                new LoggingRunTrace(), new Guardrails(5, 1.0));

        // --- 1. generate spec from approved requirement ---
        var specId = new GenerateSpecificationUseCase(loop, graph, repo, bus,
                new SpecDraftParser(), "agent-spec@v1")
                .generate(List.of(ArtifactId.of("REQ-0001")));
        assertThat(workspace.resolve("specs/" + specId.value() + ".md")).exists();
        assertThat(bus.log()).contains(new ArtifactProposed(specId));

        // --- 2. human approves through the gate ---
        var hitl = new ConsoleHumanInTheLoop(new BufferedReader(new StringReader("y\n")), "a.dupont");
        new ApproveArtifactUseCase(graph, repo, hitl, Instant::now).review(specId);
        assertThat(graph.get(specId).orElseThrow().status()).isEqualTo(NodeStatus.APPROVED);

        // --- 3. upstream requirement changes → spec flagged stale ---
        String changed = REQ.replace("shipping region", "billing region");
        String newSha = repo.write("requirements/REQ-0001.md", changed);
        bus.publish(new ArtifactChanged(ArtifactId.of("REQ-0001"), newSha));

        assertThat(graph.get(specId).orElseThrow().status()).isEqualTo(NodeStatus.NEEDS_REVALIDATION);
        assertThat(bus.log()).anyMatch(e -> e instanceof RevalidationRequested r
                && r.subject().equals(specId));
        assertThat(graph.staleNodes()).extracting(n -> n.id().value()).contains(specId.value());
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew :agents:agent-spec:test --tests EndToEndTest`
Expected: PASS, with `[trace specgen-…]` lines showing token counters (DoD: observability).

- [ ] **Step 5: Write the bootstrap main + config**

`AgentSpecApplication.java`:
```java
package dev.sdlc.agentspec.bootstrap;

import dev.sdlc.agent.AgentLoop;
import dev.sdlc.agent.Guardrails;
import dev.sdlc.agent.port.ToolRegistry;
import dev.sdlc.agentspec.adapters.outbound.*;
import dev.sdlc.agentspec.application.*;
import dev.sdlc.domain.ArtifactId;
import dev.sdlc.domain.event.ArtifactChanged;
import dev.sdlc.trace.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@SpringBootApplication
public class AgentSpecApplication {
    public static void main(String[] args) { SpringApplication.run(AgentSpecApplication.class, args); }

    @Bean CommandLineRunner demo(ChatModel chatModel) {
        return args -> {
            // usage: --workspace=./workspace REQ-0001 [REQ-0002 ...]
            Path workspace = Path.of(System.getProperty("workspace", "workspace"));
            var graph = new InMemoryTraceabilityGraph();
            new ProjectionBuilder(new FrontmatterParser()).rebuild(workspace, graph);
            var repo = new FileArtifactRepository(workspace);
            var bus = new InProcessEventPublisher();
            bus.subscribe(e -> { if (e instanceof ArtifactChanged c)
                    new ArtifactChangedHandler(graph, bus).on(c); });
            var loop = new AgentLoop(new SpringAiLanguageModel(chatModel),
                    new ToolRegistry(List.of()), new LoggingRunTrace(),
                    new Guardrails(8, 0.50));

            var reqIds = List.of(args).stream().map(ArtifactId::of).toList();
            var specId = new GenerateSpecificationUseCase(loop, graph, repo, bus,
                    new SpecDraftParser(), "agent-spec@v1").generate(reqIds);

            var hitl = new ConsoleHumanInTheLoop(
                    new BufferedReader(new InputStreamReader(System.in)),
                    System.getProperty("user.name"));
            var decision = new ApproveArtifactUseCase(graph, repo, hitl, Instant::now).review(specId);
            System.out.println(decision.approved()
                    ? specId.value() + " APPROVED — see " + workspace.resolve("specs")
                    : specId.value() + " returned to DRAFT: " + decision.feedback());
        };
    }
}
```

`application.yaml`:
```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-6
          max-tokens: 4096
  main:
    web-application-type: none
```

`workspace/requirements/REQ-0001.md`: the same content as `REQ` in the E2E test above.

- [ ] **Step 6: Smoke-run against the real model (requires `ANTHROPIC_API_KEY`)**

Run: `./gradlew :agents:agent-spec:bootRun --args="REQ-0001"`
Expected: trace lines, a generated `workspace/specs/SPEC-0001.md` with frontmatter + Gherkin, an interactive `approve? (y/n)` prompt, and `SPEC-0001 APPROVED` after `y`.
If no API key is available, skip — the E2E test already proves the wiring; note the skip in the commit message.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(agent-spec): spring bootstrap, spring-ai adapter, e2e demo (Phase 0 DoD)"
```

---

### Task 17: CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Write the workflow**

```yaml
name: ci
on:
  push: { branches: [main] }
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 25 }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew build
```

- [ ] **Step 2: Verify locally that the same entrypoint is green**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, all module tests + ArchUnit rules pass — this is exactly what CI runs.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "ci: gradle build with archunit boundary checks"
```

---

## Phase 0 definition-of-done checklist (from spec §10)

- [ ] Repo skeleton with `build-logic`, `domain-shared`, `agent-core`, `traceability-graph` — Tasks 1–9
- [ ] Specification agent end-to-end: approved requirements → spec file (frontmatter + Gherkin) → projection updated → events emitted — Tasks 10, 11, 16
- [ ] `HumanInTheLoopPort` with a working approval gate — Tasks 12, 14, 16
- [ ] ArchUnit boundary tests in CI; agent loop unit-tested with fake ports — Tasks 9, 15, 17
- [ ] Change propagation demonstrated (edit requirement → spec `NEEDS_REVALIDATION` + `RevalidationRequested`) — Tasks 6, 13, 16
- [ ] Each run emits a trace with token and cost counters — Tasks 8, 14, 16

## Explicitly deferred (recorded so nothing silently disappears)

- Postgres projection (spec §8 / brief §12.8) → Phase 1; `TraceabilityGraphPort` already isolates it.
- OpenTelemetry export + Scoped Values context propagation (NFR-OBS) → Phase 1; `RunTracePort` is the seam.
- Real message bus, idempotency via consumer offsets (NFR-RELY) → Phase 2; Phase 0 proves handler idempotency.
- Tool-calling through Spring AI (the spec agent needs no tools yet) → when the first tool-using agent lands.
- Intent/Design/Backlog agents, `BacklogPort`, PR-based approval (FR-SAC-2) → Phase 1 per roadmap.

