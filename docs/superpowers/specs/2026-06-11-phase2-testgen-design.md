# Phase 2 (Slice 1) Design — Test-Generation Agent

*Extends `global-specification.md` (SPEC-0000), whose v1 scope excluded downstream agents (§2). This document defines the requirements for the first downstream agent as FR-TEST-*; SPEC-0000 §2/§5 should gain these on its next revision — noted here as the platform's own change-propagation case in miniature. Companion to the Phase 0/1 plans and `sdlc-agents-brief.md` §6/§12.0 (the `CODE/TEST —satisfies/verifies→ BACKLOG` bottom link this slice closes).*

## 1. Goal & decisions (brainstormed 2026-06-11)

Close the intent→verification chain: every approved Specification's Gherkin acceptance criteria become executable test artifacts (`TEST-xxxx`), traceable via `VERIFIES` edges, flagged stale by the existing propagation when upstream moves, and optionally executed against a configured target repository.

**Decisions:**
- **Phase 2 priority:** first downstream agent = test-gen (over platform-debt-first, code-review-first, demo-hardening).
- **Scope:** artifacts + optional run (full sandboxed execution loop deferred).
- **Approach A (hybrid):** `.feature` files rendered **deterministically** from the spec's own Gherkin — no LLM call, no hallucination surface, no cost. The LLM generates only step-definition skeletons (binding glue), grounded in the feature file + the spec's design/API-contract artifacts. Run commands are **allow-listed from config, never model-chosen** (brief §9 guardrail in minimal form).

## 2. Requirements defined by this slice

- **FR-TEST-1** From an `APPROVED` `Specification`, derive one `.feature` file per spec (scenarios 1:1 with the spec's acceptance criteria) deterministically, stored as a `TEST` artifact with frontmatter, `DERIVES_FROM` (pinned) the spec.
- **FR-TEST-2** Record `VERIFIES` edges: TEST→Specification always; TEST→BacklogItem for every story whose `acceptanceHook` names a scenario in the feature.
- **FR-TEST-3** Generate step-definition skeletons via the LLM (configured language, default Java/Cucumber), grounded in the feature file and the spec's approved design artifacts; stored as a companion `TEST` artifact (the skeleton), same edges.
- **FR-TEST-4** When a target repository is configured: place the generated files under the target's conventional paths and run the **configured** test command through `TestRunnerPort`; record `lastRun: passed|failed` + timestamp in the TEST artifact's frontmatter as metadata (no propagation — same rule as approvals).
- **FR-TEST-5** Upstream change propagation reaches TEST artifacts with no propagation-code changes (they ride the `DERIVES_FROM` spine).
- **FR-PORT-2** `TraceabilityGraphPort.listByType(NodeType...)` — new query API; retires the id-probe enumeration debt in `ReviewFlagsUseCase` and `GenerateDesignUseCase` (known-issues #1).

## 3. Module layout

```
agents/agent-testgen/            NEW — dev.sdlc.agenttestgen.{domain,application,bootstrap}
libs/agent-core/                 MOD — TestRunnerPort added
libs/adapter-common/             MOD — ProcessTestRunner (allow-listed command execution)
libs/traceability-graph/         MOD — listByType on the port + in-memory impl + contract test
libs/adapter-graph-postgres/     MOD — listByType SQL impl (contract-covered)
agents/agent-intent, agent-design  MOD — id-probe call sites migrate to listByType
```

## 4. Components

### 4.1 `listByType` (FR-PORT-2)
`List<Node> listByType(NodeType... types)` on `TraceabilityGraphPort`; in-memory = filtered values; Postgres = `WHERE type = ANY(?)`. Contract test added (runs against both). `ReviewFlagsUseCase.allIntentNodes` and `GenerateDesignUseCase.existingDesignSummaries` migrate; the id-probe caveat comments are deleted with the probes.

### 4.2 Gherkin extraction (deterministic, agent-testgen application)
`AcceptanceCriteriaExtractor`: parses the spec artifact's body (`## Acceptance criteria` section, `Scenario:` blocks — the exact format `SpecificationDraft.renderBody` writes) into scenario records. Malformed/absent section ⇒ IAE naming the spec (a spec without criteria cannot be APPROVED in practice, but hand-edited files exist). `FeatureRenderer`: scenarios → one Cucumber `.feature` text: `Feature: <spec title>` + verbatim scenarios. Round-trip property: extracting from a generated spec must always succeed (tested against `GenerateSpecificationUseCase` output).

### 4.3 agent-testgen domain
`ScenarioSpec(name, steps)`, `FeatureDraft(specId, title, scenarios)` (≥1 scenario), `StepSkeletonDraft(language, content)` (non-blank; language from an allow-list: java|kotlin|typescript|python). The LLM's JSON for skeletons: `{"language", "content"}` — parsed by `StepSkeletonParser` with the house fence/error conventions.

### 4.4 GenerateTestsUseCase
Input: APPROVED spec id (gate as in design/backlog agents). Steps: extract criteria → render feature → write `tests/TEST-xxxx.feature.md` artifact (frontmatter + feature text in body) → LLM step-skeleton call (context: feature + design summaries via `listByType(DESIGN_ELEMENT, ADR, API_CONTRACT)` filtered to APPROVED + the spec's constraints) → write `tests/TEST-yyyy.steps.md` artifact → edges per FR-TEST-2 (stories found via `listByType(BACKLOG_ITEM)`, their `acceptanceHook` read from frontmatter — **decision:** `ArtifactFile` gains a read-only `rawFrontmatter()` passthrough map so consumers can read per-type extras like `acceptanceHook`/`level` without schema churn in the parser) → `ArtifactProposed` events → both artifacts PROPOSED through the standard gate.

### 4.5 TestRunnerPort + optional run (FR-TEST-4)
```java
public interface TestRunnerPort {
    /** Runs the CONFIGURED command in the target repo; returns pass/fail + tail of output. */
    RunResult run();
    record RunResult(boolean passed, String outputTail) {}
}
```
`ProcessTestRunner(Path targetRepo, List<String> command)` — command comes from bootstrap config (`SDLC_TEST_CMD`, default `./gradlew test`); ProcessBuilder, captured output, 10-minute timeout, non-zero exit = failed. **The model never chooses or composes the command.** `PlaceAndRunUseCase`: copies the two artifacts' payloads (feature text, skeleton content) into the target repo (`src/test/resources/features/`, `src/test/java/...` — paths from config with those defaults), runs, then rewrites both TEST artifacts' frontmatter `lastRun: passed|failed` + `lastRunAt` via the governance-style line edit (metadata change, node sha updated, no propagation). Target repo absent ⇒ use case unavailable in bootstrap (profile `target-repo`).

### 4.6 Bootstrap + E2E
`AgentTestgenApplication` mirrors the sibling bootstraps (args = SPEC ids; profiles incl. new `target-repo` reading `SDLC_TARGET_REPO`/`SDLC_TEST_CMD`). E2E extension: `UpstreamLoopEndToEndTest` gains step 4c — test-gen on the approved spec (scripted skeleton JSON), assert TEST artifacts + VERIFIES edges (story matched via its acceptanceHook) — and the step-5 propagation assertion set gains the two TEST ids; restart parity inherits automatically. A separate `PlaceAndRunUseCaseTest` uses a @TempDir fake target repo with a stub script as the configured command (e.g. `sh -c "exit 0"` / `exit 1`) to pin pass/fail recording.

## 5. Out of scope (unchanged + new)
Full sandboxed execution loop (containers, model-driven iteration on failures), code-reading context, CI-triggered test-gen, coverage mapping (CODE nodes), everything in Phase 1's §14 list. SPEC-0000 revision to absorb FR-TEST-* (flagged for the Intent agent to eat its own dogfood, manually for now).

## 6. Risks
- Frontmatter `extras` passthrough widens the parser's surface — read-only map, no write path, low risk.
- LLM skeletons for non-Java targets are untested against real compilers — they are skeletons (PROPOSED artifacts a human reviews), not promised-compiling code; recorded as an assumption in their provenance.
- `PlaceAndRunUseCase` writes into a user repo — paths are config-fixed, never model-chosen; still, the bootstrap prints what it wrote where before running.
