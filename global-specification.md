---
id: SPEC-0000
type: Specification
title: Agentic SDLC Pipeline — Global Specification
status: DRAFT
derivesFrom: [GOAL-0001]
provenance:
  sourceRefs: [sdlc-agents-brief.md, traceability-graph-schema.md]
  generatedBy: drafting session
  confidence: medium
  assumptions:
    - "LLM provider not yet fixed; portability assumed"
    - "Backlog tool to be selected (Jira/Linear/ADO)"
  humanApproved: false
  approvedBy: null
---

# Agentic SDLC Pipeline — Global Specification

*Foundational specification and basis for development. Companion to the project brief (`sdlc-agents-brief.md`) and the traceability schema (`traceability-graph-schema.md`). This document intentionally follows the platform's own artifact model — it is the first piece of spec-as-code in the repository.*

## 1. Vision & intent (GOAL-0001)
Build an internal platform that automates the software delivery lifecycle through LLM-powered Java agents, beginning with the **upstream / discovery loop**: turning stakeholder intent into requirements, use cases, specifications, design, and a maintained backlog — with every artifact traceable back to the intent that justifies it.

**Why.** Upstream work (requirements, specs, design rationale, backlog) is today fragmented, hard to keep consistent, and rarely traceable to the business intent behind it. When intent changes, no one can answer reliably "what is now stale?" The platform uses LLM agents to accelerate this work while a traceability graph preserves consistency and governance — capabilities no off-the-shelf vendor maintains over *your* intent.

**Success metrics (initial targets, to be tuned).**
- Share of backlog items that trace to an approved specification: target > 95%.
- Median time from captured intent to an approved, testable spec: materially reduced vs. the manual baseline.
- Staleness detection: every downstream artifact affected by an upstream change is flagged within one projection rebuild.
- Human-approval coverage: 100% of artifacts reaching `APPROVED` carry a recorded human approver.
- Grounding: 0 artifacts with empty `sourceRefs` and no declared assumptions.

**Primary stakeholders.** Product (intent owners), Engineering (spec/design consumers), QA (acceptance criteria), Platform team (operators of the pipeline).

## 2. Scope
**In scope (v1, upstream-first).** Intent, Specification, Design, and Backlog agents; the traceability graph and change propagation; spec-as-code persistence; human-in-the-loop approvals; backlog-tool synchronisation.

**Out of scope (later phases).** Downstream delivery agents (code review, test generation, build, deploy, release notes); full governance-substrate parity; multi-tenant SaaS operation.

**Non-goals.** Replacing Jira/Linear/ADO; replacing existing CI/CD; taking autonomous, unattended action on irreversible operations.

## 3. Glossary
- **Agent** — an independently deployable Java service running an LLM-driven loop that proposes artifacts and calls tools when needed.
- **Port / adapter** — hexagonal boundary: the application depends on ports (interfaces); adapters implement them against concrete technology.
- **Artifact** — a versioned spec-as-code file (goal, requirement, use case, spec, design, ADR, backlog item) with frontmatter.
- **Traceability graph** — the network of artifacts (nodes) and typed, versioned links (edges); the platform's core asset.
- **blobSha** — the Git blob SHA of an artifact file; its version.
- **Provenance** — grounding metadata on every artifact (source refs, generating agent, confidence, assumptions, approval).
- **HITL** — human-in-the-loop: clarification and approval interactions.
- **Change propagation** — marking downstream artifacts stale when an upstream artifact changes.

## 4. Architecture (summary; see brief §3–§7, §12)
- One monorepo; hexagonal architecture per agent; Java 25 (LTS); Gradle multi-project.
- LLM provider behind `adapter-llm` (Spring AI or LangChain4j); domain layer is framework-free.
- Coordination by event choreography over a message bus; thin orchestrator for approvals and ordered sagas; agents deploy independently.
- Agent loop (in the application layer): assemble context → call the model → execute requested tool calls through ports → emit an artifact/event; guardrails (max iterations, cost ceiling, approval gates) throughout.
- Tools and the LLM are outbound adapters; the traceability graph is an outbound port (`TraceabilityGraphPort`).

## 5. Functional requirements

### 5.1 Intent agent
- **FR-INT-1** Capture stakeholder input (conversation, ticket, document) and produce structured `Goal`, `Requirement`, and `UseCase` artifacts.
- **FR-INT-2** Detect and surface ambiguity, conflicts, duplicates, and gaps; record `CONFLICTS_WITH` and `DUPLICATES` edges.
- **FR-INT-3** Ground every produced artifact in `sourceRefs`; flag any assumption explicitly; never fabricate (see NFR-GROUND).
- **FR-INT-4** Ask clarifying questions through `HumanInTheLoopPort` rather than guessing on material ambiguity.

### 5.2 Specification agent
- **FR-SPEC-1** Derive a testable `Specification` from approved `Requirement`/`UseCase` nodes, creating `DERIVES_FROM` edges.
- **FR-SPEC-2** Produce acceptance criteria (Gherkin) and explicit constraints.
- **FR-SPEC-3** Check completeness and testability; flag requirements that cannot be made testable.

### 5.3 Design agent
- **FR-DES-1** Produce `DesignElement`, `ADR`, and `ApiContract` artifacts from a `Specification`.
- **FR-DES-2** Read the existing system and the traceability graph to stay consistent with prior decisions.
- **FR-DES-3** Present alternatives and trade-offs; record decisions as ADRs rather than deciding silently.

### 5.4 Backlog agent
- **FR-BL-1** Decompose `Specification` + `Design` into `BacklogItem`s (Epic/Story/Task) with dependencies and estimates.
- **FR-BL-2** Synchronise bidirectionally with Jira/Linear/ADO through `BacklogPort`.
- **FR-BL-3** Flag backlog items orphaned or invalidated by upstream change.

### 5.5 Traceability & change propagation
- **FR-TRACE-1** Maintain nodes and edges per the schema (brief §12).
- **FR-TRACE-2** On any artifact change, mark dependent edges `STALE` and downstream nodes `NEEDS_REVALIDATION`.
- **FR-TRACE-3** Answer the impact query "what is stale if X changes?" in a single query.
- **FR-TRACE-4** Persist links canonically in artifact frontmatter; rebuild the query projection from commits.

### 5.6 Human-in-the-loop & governance
- **FR-HITL-1** Enforce an approval gate between each stage (intent → spec → design → backlog); no node reaches `APPROVED` without a recorded human approver.
- **FR-HITL-2** Support a clarifying-question interaction during elicitation.
- **FR-GOV-1** Record every agent action to an immutable, queryable audit trail.

### 5.7 Spec-as-code persistence
- **FR-SAC-1** Store all artifacts as versioned files in the monorepo with the specified frontmatter.
- **FR-SAC-2** Route artifact changes through pull requests; PR review is an approval mechanism where appropriate.

## 6. Non-functional requirements
- **NFR-TEST** Hexagonal boundaries enforced by ArchUnit in CI; the agent loop is unit-testable with fake ports (no live model or infrastructure).
- **NFR-PORT** Provider portability: the LLM provider can be swapped behind `adapter-llm` without domain changes.
- **NFR-GROUND** No fabrication: provenance is mandatory; an artifact with empty `sourceRefs` and no declared assumptions is invalid.
- **NFR-OBS** Trace every agent step (prompt, tool calls, tokens, latency, cost) via OpenTelemetry; carry trace/run context with Scoped Values (Java 25).
- **NFR-COST** Per-agent token/cost budgets, model tiering, and response caching.
- **NFR-SEC** Sandboxed tool execution; scoped secret injection; RBAC governs who may approve and act.
- **NFR-PERF** Impact/staleness queries remain interactive (target < 200 ms) at the expected artifact volume.
- **NFR-EXT** Adding an agent reuses the standard module shape; adding a tool is a new adapter behind a port.
- **NFR-RELY** Event handling is idempotent with retries; SDLC events may redeliver.

## 7. Key use cases (with acceptance criteria)

**UC-0001 — Capture intent into structured requirements**
- Given stakeholder input referencing a desired outcome,
- When the Intent agent processes it,
- Then it creates `Goal`/`Requirement`/`UseCase` nodes, each with `sourceRefs` pointing to the input, and raises clarifying questions for any material ambiguity before marking anything `PROPOSED`.

**UC-0002 — Generate a specification from requirements**
- Given one or more `APPROVED` `Requirement`/`UseCase` nodes,
- When the Specification agent runs,
- Then it produces a `Specification` artifact with Gherkin acceptance criteria, `DERIVES_FROM` edges to its sources, and a completeness/testability report; the spec remains `DRAFT` until a human approves it.

**UC-0003 — Propagate change when intent changes**
- Given an `APPROVED` `Specification` derived from `REQ-0012`,
- When `REQ-0012`'s content (blobSha) changes,
- Then every edge linking the spec to the old SHA becomes `STALE`, the spec becomes `NEEDS_REVALIDATION`, and a `RevalidationRequested` event is emitted.

**UC-0004 — Decompose a spec into a synced backlog**
- Given an `APPROVED` `Specification` and its `Design`,
- When the Backlog agent runs,
- Then it creates `BacklogItem`s with dependencies and estimates, links them with `DERIVES_FROM` edges, and reflects them in the connected backlog tool.

**UC-0005 — Human approval gate**
- Given a `PROPOSED` artifact,
- When a reviewer approves it through `HumanInTheLoopPort`,
- Then `humanApproved` is set with the approver recorded and the artifact transitions to `APPROVED`; rejection returns it to `DRAFT` with feedback retained.

## 8. Artifact & data model
The data model is the traceability graph defined in brief §12: node types (`Goal`, `Requirement`, `NFR`, `UseCase`, `Specification`, `DesignElement`, `ADR`, `ApiContract`, `BacklogItem`, future `Code`/`Test`), edge types (`DERIVES_FROM`, `SATISFIES`, `VERIFIES`, `DEPENDS_ON`, `CONFLICTS_WITH`, `DUPLICATES`, `CONSTRAINS`, `SUPERSEDES`), the provenance block, the `GOAL-####` ID scheme, frontmatter as canonical storage, and the Postgres projection as the query model.

## 9. Constraints & key decisions (see brief §8)
- Runtime: Java 25 (LTS). Build: Gradle multi-project with convention plugins.
- LLM framework: Spring AI (default) or LangChain4j, behind `adapter-llm`.
- Coordination: event choreography + thin orchestrator; independent deploy per agent.
- Persistence: spec-as-code in Git; Postgres projection of the graph.
- Substrate: assemble from existing CI/CD plus proven OSS (Temporal, Vault, OPA, OpenTelemetry) rather than building from scratch (brief §10).

## 10. Phase 0 — definition of done (first development target)
The first milestone proves the architecture end-to-end on the smallest meaningful slice.
- Repo skeleton with `build-logic`, `domain-shared`, `agent-core`, and `traceability-graph`.
- The **Specification agent** runs end-to-end: reads one or more `APPROVED` `Requirement`/`UseCase` artifacts → produces a `Specification` artifact (frontmatter + Gherkin) → writes it to the repo → updates the graph projection → emits artifact events.
- `HumanInTheLoopPort` implemented with at least one working approval gate.
- ArchUnit boundary tests pass in CI; the agent loop has unit tests using fake ports.
- Change propagation demonstrated: editing an upstream requirement marks the derived spec `NEEDS_REVALIDATION` and emits `RevalidationRequested`.
- Observability: each agent run emits a trace including token and cost counters.

## 11. Assumptions & open questions
- LLM provider is not yet fixed (Spring AI keeps this open).
- Backlog tool to be selected (Jira / Linear / Azure DevOps).
- Acceptance criteria modelled as spec body for now; may be promoted to first-class `AC-####` nodes when test agents arrive.
- Execution/governance substrate to confirm: existing CI/CD vs. Temporal-style assembly vs. Harness.

## 12. Roadmap (see brief §11)
- **Phase 0** — Skeleton, traceability graph, Specification agent end-to-end (this spec's DoD).
- **Phase 1** — Intent, Design, and Backlog agents; HITL and backlog sync; close the upstream loop with change propagation.
- **Phase 2** — Downstream delivery agents consuming these specs; event backbone, full observability, cost governance.
