# Phase 1 Design — Upstream Loop: Foundation Hardening + Intent/Design/Backlog Agents

*Derives from `global-specification.md` (SPEC-0000) §5, §12 and `sdlc-agents-brief.md` §6, §11. Companion to the Phase 0 plan (`docs/superpowers/plans/2026-06-10-phase0-spec-agent.md`), whose deferred-items list this design consumes.*

## 1. Goal

Close the upstream loop: stakeholder intent enters through an inbox, becomes governed `Goal`/`Requirement`/`UseCase` artifacts, flows through Specification (Phase 0) into Design and Backlog artifacts — every step human-gated, every artifact traceable, and staleness propagation surviving restarts. Simultaneously harden the foundation the agents stand on: durable projection, real observability, git-native approval.

**Decisions taken during brainstorming (2026-06-10):**
- Scope: agents **and** full infra (Postgres, OTel, git/PR approval, sha-pinned edges). The message-bus backbone stays in Phase 2.
- Backlog tool: **file-based in-repo adapter** now; Jira/Linear/ADO adapters later behind the same `BacklogPort`.
- Intent input: **inbox files + console clarifying questions**. spec-tacular integration (richer interview/confirm UX) deliberately deferred; its patterns inform but its code is not imported.

## 2. Sub-project decomposition

Phase 1 ships as two sequential plans, each independently shippable:

- **1A — Foundation hardening** (sections 4–8): shared-adapter extraction, sha-pinned edges, Postgres projection, OpenTelemetry + pricing, git-based approval.
- **1B — Agents** (sections 9–12): Intent, Design, Backlog agents and the closed-loop E2E.

1B depends on 1A's adapter extraction and sha-pinning; it does not depend on Postgres/OTel (any `TraceabilityGraphPort`/`RunTracePort` works — that's the point of the ports).

## 3. Module layout after Phase 1

```
/libs
  /domain-shared          (unchanged)
  /traceability-graph     (sha-pinned edges added)
  /agent-core             (unchanged API; pricing model added to Usage handling)
  /adapter-common         (NEW: FileArtifactRepository, ConsoleHumanInTheLoop,
                           InProcessEventPublisher, LoggingRunTrace — moved from agent-spec)
  /adapter-llm-spring     (NEW: SpringAiLanguageModel + provider config — moved from agent-spec)
  /adapter-graph-postgres (NEW: TraceabilityGraphPort over JDBC, spec §12.8 schema)
  /adapter-otel           (NEW: RunTracePort over OpenTelemetry SDK)
  /adapter-git            (NEW: GitPort + GitArtifactRepository: branch-per-proposal, merge-on-approve)
/agents
  /agent-spec             (slimmer: only its domain/application/bootstrap remain)
  /agent-intent           (NEW)
  /agent-design           (NEW)
  /agent-backlog          (NEW)
```

ArchUnit evolution: the three existing rules generalize from `..agentspec..` to `..agent(spec|intent|design|backlog)..` via one parameterized rule set per agent module, and a new rule pins `libs/adapter-*` as the only places Spring/OTel/JGit-free-zone exceptions live. Domain/application layers of every agent stay framework-free.

## 4. (1A) Shared adapter extraction

**What:** move the five adapters out of `agent-spec` into `libs/adapter-common` and `libs/adapter-llm-spring`; agents depend on the libs. Bootstrap wiring stays per-agent.

**Why now:** four agents would otherwise copy-paste adapters; the brief's §7 target layout has them shared.

**Interface contract:** unchanged — these classes already implement `agent-core` ports. Pure move + package rename (`dev.sdlc.adapter.common`, `dev.sdlc.adapter.llm`). All existing tests move with them; agent-spec's E2E keeps passing unchanged apart from imports.

## 5. (1A) Sha-pinned frontmatter edges (restart-safe staleness)

**Problem (Phase 0 known gap):** frontmatter stores `derivesFrom: [REQ-0012]`; rebuild stamps every edge with the *current* upstream sha, so upstream edits while the system is down are invisible, and staleness flags don't survive restarts.

**Design:**
- Writers emit pinned refs: `derivesFrom: ["REQ-0012@<40-hex-blobSha>"]`. Parser accepts both pinned and bare forms (bare = legacy/hand-written, resolves to current sha at link time).
- `ProjectionBuilder.rebuild` compares each pinned sha with the upstream node's current sha: mismatch ⇒ edge `STALE` + downstream node `NEEDS_REVALIDATION` at rebuild time (and a `RevalidationRequested` is emitted through a publisher the builder now accepts).
- `revalidate()` rewrites the pinned sha in the *downstream* artifact's frontmatter (the edge owner), keeping the file canonical.
- `ApproveArtifactUseCase`'s frontmatter rewrite preserves pinned refs untouched.

**Invariant:** after any rebuild, `staleNodes()` is identical to what an always-on system would have computed. This is FR-TRACE-2/4 made restart-honest.

## 6. (1A) Postgres projection — `adapter-graph-postgres`

- Schema per spec §12.8: `nodes(id PK, type, title, repo_path, blob_sha, status, version, provenance jsonb, created_at, updated_at)`, `edges(id PK, type, from_id, to_id, upstream_blob_sha_at_link, link_status, established_by, validated_at, validated_by)`; indexes `edges(to_id, link_status)`, `edges(from_id)`, `nodes(status)`.
- `PostgresTraceabilityGraph implements TraceabilityGraphPort`; `impactOf`/`collectDownstream` via recursive CTE; `applyChange` in one transaction (same semantics as the in-memory adapter, including the no-op-on-equal-sha redelivery rule and the flag-clearing cascade — the Phase 0 contract tests are extracted into a reusable `TraceabilityGraphContract` test base class run against BOTH adapters).
- Plain JDBC (no JPA); schema applied by a versioned `schema.sql` (Flyway deferred — one file, idempotent `CREATE TABLE IF NOT EXISTS`).
- Tests: Testcontainers `postgres:17`; the contract class + adapter-specific transactional tests. CI gets Docker (GitHub Actions has it by default).
- Selection: Spring profile `postgres` (`SDLC_DB_URL`/`USER`/`PASSWORD` env); default remains in-memory + rebuild-from-files. With Postgres active, rebuild still runs at startup (files stay canonical; the DB is a projection, FR-TRACE-4).

## 7. (1A) OpenTelemetry + real cost — `adapter-otel`

- `OtelRunTrace implements RunTracePort`: one span per run (`sdlc.agent.run`), child span per step; attributes: `sdlc.run.id`, `sdlc.step.kind`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `sdlc.cost.usd`, outcome on finish. OTLP/gRPC exporter, endpoint via standard `OTEL_EXPORTER_OTLP_ENDPOINT`; falls back to logging exporter when unset.
- **Pricing:** a small `ModelPricing` table (per-model USD per 1M input/output tokens, loaded from `pricing.yaml` in `adapter-llm-spring` resources) so `SpringAiLanguageModel` reports real `costUsd` instead of 0.0. Unknown model ⇒ cost 0 + a warning attribute (`sdlc.cost.unknown_model=true`). The Phase 0 cost-ceiling guardrail becomes meaningful in live runs.
- Scoped Values (Java 25): `RunContext` (runId, agent name) carried via `ScopedValue` from use case through the loop into adapters, replacing manual runId threading where it crosses thread boundaries; adopted only in `agent-core` internals — ports keep explicit runId parameters for API stability.

## 8. (1A) Git-based approval workflow — `adapter-git` (FR-SAC-2, local-first)

- `GitPort` (outbound, in `agent-core`): `branch(name)`, `commitAll(message)`, `checkout(name)`, `merge(branch, message)`, `currentSha()` — implemented by shelling out to the `git` CLI (`ProcessBuilder`; no JGit dependency).
- `GitArtifactRepository implements ArtifactRepositoryPort`: decorates the file repository — every `write` lands on branch `proposal/<artifactId>` as a commit (`proposal: SPEC-0002 by agent-spec@v1`). Reads resolve from the working tree.
- `ApproveArtifactUseCase` gains the merge step: approve ⇒ frontmatter rewrite committed on the proposal branch ⇒ merge into the mainline with `approval: SPEC-0002 by a.dupont`; reject ⇒ branch retained for rework, mainline untouched.
- Behavior toggled by profile (`git-approval`); default stays plain-file mode so tests and quick demos don't need a repo dance. A GitHub PR adapter (open PR instead of local merge) is a Phase 2 drop-in behind the same port.
- The artifact workspace becomes its own nested git repository (`workspace/.git`), separate from the source repo — agents version artifacts, not platform code.

## 9. (1B) Intent agent — `agents/agent-intent`

**Flow (FR-INT-1..4, UC-0001):**
1. `IngestInboxUseCase` scans `workspace/inbox/*.{md,txt}`; each unprocessed file (tracked by a `processed/` move after success) becomes one elicitation run.
2. `GenerateIntentUseCase` runs the `AgentLoop` with the inbox document as task and **one tool registered: `ask_human`** — schema `{question: string}`, executing `HumanInTheLoopPort.askClarifyingQuestion`. The system prompt instructs: ask when material ambiguity exists, never invent, cite the source for every item, list assumptions. Multi-turn elicitation thus reuses the existing loop + guardrails (max 12 iterations, cost ceiling) with zero new loop machinery.
3. The final JSON contains drafts: `goals[]`, `requirements[]` (kind: functional|nfr, moscow), `useCases[]` (actor, mainFlow, altFlows), each with `sourceQuotes[]` (verbatim grounding) and `assumptions[]`. Parser (`IntentDraftParser`) validates and maps to artifacts: `GOAL-xxxx`, `REQ-xxxx`/`NFR-xxxx`, `UC-xxxx`, all `PROPOSED`, `sourceRefs: ["inbox/<file>@<blobSha>", "clarification:<n>"]` (clarification Q/A pairs are appended to the artifact body for auditability).
4. **Duplicate/conflict pass (FR-INT-2):** a second, cheap LLM call comparing new drafts against existing non-deprecated `Goal`/`Requirement`/`UseCase` titles+summaries from the graph; output proposes `DUPLICATES`/`CONFLICTS_WITH` edges (with reasons), recorded as edges with `establishedBy: agent-intent@v1` and surfaced in the artifact body under `## Review flags`. Edges are advisory; humans resolve at approval time.
5. Per-artifact approval through the existing gate; `RevalidationRequested`/propagation apply from day one because intent artifacts are upstream of everything.

**Hierarchy:** requirements emit `DERIVES_FROM` to their goal; use cases `DERIVES_FROM` requirements (or goals when no requirement fits). The agent proposes the linkage in its JSON; the use case validates ids exist before linking.

## 10. (1B) Design agent — `agents/agent-design`

**Flow (FR-DES-1..3):**
1. Input: one APPROVED `Specification`. Context assembly: the spec body, its upstream requirement/use-case bodies, plus **summaries of all existing APPROVED `DesignElement`/`ADR`/`ApiContract` artifacts** (id + title + one-liner from the graph) so the model stays consistent with prior decisions (FR-DES-2).
2. Output JSON: `designElements[]` (component descriptions), `adrs[]` (title, context, decision, **alternatives[] with trade-offs**, consequences — FR-DES-3's "present alternatives" is satisfied structurally: an ADR without ≥2 considered alternatives is rejected by the parser), `apiContracts[]` (endpoint/message shapes, rendered as the artifact body).
3. Artifacts: `DES-xxxx`, `ADR-xxxx`, `API-xxxx`, all `PROPOSED`, `DERIVES_FROM` the spec; ADRs may `CONSTRAINS` other artifacts. Same approval gate.
4. Existing-system awareness beyond the graph (reading actual source code) is explicitly **out of scope** for Phase 1 — it requires the sandboxed shell/git tooling planned for Phase 2. The graph summaries are the Phase 1 interpretation of FR-DES-2, recorded as an assumption in each artifact's provenance.

## 11. (1B) Backlog agent — `agents/agent-backlog`

**Flow (FR-BL-1..3):**
1. Input: an APPROVED `Specification` + its APPROVED design artifacts.
2. Output JSON: `epics[]` → `stories[]` → `tasks[]`, each with title, description, acceptance hook (which Gherkin scenario it serves), `dependsOn[]` (within the batch), and an estimate (`XS|S|M|L|XL`).
3. `BacklogPort` (in `agent-core`): `upsert(BacklogItem)`, `find(externalRef)`, `listOpen()`. Phase 1 adapter: `FileBacklogAdapter` writing `STORY-xxxx.md` (and `EPIC-`/`TASK-` prefixed ids, all node type `BACKLOG_ITEM`; the level — epic/story/task — is carried in frontmatter like other per-type extras, not on the generic Node record) under `workspace/backlog/`, frontmatter like every artifact — so backlog items ARE graph nodes and propagation just works. Vendor adapters (Jira/Linear/ADO) are later implementations of the same port; `externalRef` field reserved now.
4. Edges: stories `DERIVES_FROM` the spec (and `DERIVES_FROM` design artifacts where the model attributes them); `DEPENDS_ON` between items. Orphan flagging (FR-BL-3): deprecating an upstream artifact marks inbound edges `ORPHANED` (existing graph semantics) — a `backlog report` CLI command lists orphaned/stale items.
5. Estimates and decomposition go through the same per-artifact approval gate (epics approve as a batch with their children listed in the summary).

## 12. (1B) Closing the loop — Phase 1 E2E

One test (and a mirroring CLI demo script) proving UC-0001→UC-0004 plus propagation:
1. Drop `inbox/payment-notes.md` → Intent agent (scripted fake model incl. one `ask_human` round-trip) → GOAL/REQ/UC artifacts → approve.
2. Spec agent (Phase 0) on the approved REQ/UC → approve.
3. Design agent → ADR with two alternatives → approve.
4. Backlog agent → epic + stories with dependencies → approve.
5. Edit the inbox-derived REQ → assert the **entire chain** (spec, design artifacts, stories) flags `NEEDS_REVALIDATION` with events, transitively.
6. Restart: rebuild projection from files alone → assert the staleness picture is identical (sha-pinning proof) and all approvals survived.
Run against in-memory graph in the standard build; the same test runs against Postgres via the contract-test tag in a `postgres`-profile CI job.

## 13. Requirement coverage map

| Requirement | Where |
|---|---|
| FR-INT-1..4 | §9 (inbox, drafts, grounding, ask_human tool) |
| FR-DES-1..3 | §10 (artifacts, graph-context consistency, structural alternatives) |
| FR-BL-1..3 | §11 (decomposition+estimates, BacklogPort file adapter, orphan report) |
| FR-TRACE-1..4 | §5 (restart-safe), §6 (Postgres projection) |
| FR-HITL-1..2 | existing gate; §9 ask_human implements FR-HITL-2 in production |
| FR-SAC-1..2 | §8 (git branches + merge-on-approve) |
| NFR-OBS, NFR-COST | §7 (OTel spans, pricing, meaningful cost ceiling) |
| NFR-PERF | §6 (indexed Postgres, CTE impact query; target <200ms verified by a contract perf test at 1k nodes) |
| NFR-TEST/PORT/GROUND/EXT/RELY | inherited Phase 0 mechanisms, extended to new modules (ArchUnit §3) |

## 14. Out of scope (Phase 2+)

Real message bus (Kafka/NATS) + consumer idempotency offsets; GitHub/GitLab PR adapter; Jira/Linear/ADO backlog adapters; code-reading Design agent (sandboxed tools); spec-tacular import adapter; downstream delivery agents; eval harness; RBAC/Vault/audit substrate (brief §10 assemble-don't-build).

## 15. Risks

- **Spring AI 2.0 still milestone** — same confinement strategy as Phase 0 (adapter-only exposure); re-pin at GA.
- **ask_human as a tool** relies on the model choosing to call it; mitigated by prompt instruction + the E2E scripting one round-trip; a `--max-questions` style guardrail is the existing iteration ceiling.
- **Postgres/in-memory drift** — prevented structurally by the shared contract test class.
- **Workspace-as-nested-git-repo** (§8) complicates the dev repo; mitigated: only active under the `git-approval` profile, and the workspace path is configurable.
