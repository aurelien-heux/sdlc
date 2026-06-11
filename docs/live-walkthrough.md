# Live walkthrough — running the upstream SDLC loop end to end

This guide takes you through one full pass of the platform with a real LLM:
stakeholder notes → **Intent** → **Spec** → **Design** → **Backlog** → **Tests**,
then a change-propagation demo where you edit the original requirement and watch
the platform refuse to build on stale ground.

```
inbox note ──intent──▶ GOAL/REQ ──spec──▶ SPEC ──design──▶ ADR/DES/API ──backlog──▶ EPIC/STORY ──testgen──▶ TEST
                 ▲ every arrow: you approve on the console; every artifact: a markdown file with frontmatter
```

Everything lands in `workspace/` as plain markdown files — the files **are** the
database (the graph is re-projected from them at every start).

---

## 1. Prerequisites

| What | Why | Check |
|---|---|---|
| Java 25 | toolchain | `java -version` |
| An Anthropic API key | the default LLM provider | `export ANTHROPIC_API_KEY=sk-ant-...` |
| Run from the repo root | the workspace path is relative (`./workspace`) | `ls workspace/inbox` |
| Docker (optional) | only for `./gradlew build` (Postgres contract tests) and the `postgres` profile | `docker info` |

No Docker? Use `./gradlew assemble` instead of `build` — the run itself doesn't need it.

> **OpenAI-compatible endpoint instead?** Prefix every command below with
> `SPRING_PROFILES_ACTIVE=openai` and set `OPENAI_BASE_URL`, `OPENAI_API_KEY`,
> `OPENAI_MODEL` (Ollama example: `OPENAI_BASE_URL=http://localhost:11434`).

Heads-up on cost: each agent run is one or two model calls with hard guardrails
(intent: 12 iterations / $1.00 ceiling; the others: 5 / $1.00; spec: 8 / $0.50).
A full pass costs a few cents on claude-sonnet-4-6. Every run prints its token
and cost counters (`[trace ...]` lines).

---

## 2. Step 1 — Intent: notes in, requirements out

A demo seed is already committed at `workspace/inbox/payment-notes.md`:

```markdown
# Payment notes — stakeholder meeting

Right now we lose carts at checkout. Tax must match the region.
Finance insists the rate applied at payment time follows the shipping region,
not the storefront default.
```

(Want your own scenario? Drop any `.md`/`.txt` file in `workspace/inbox/` —
each file is processed once and moved to `workspace/inbox/processed/` on success.)

Run the Intent agent (no arguments — it sweeps the inbox):

```bash
./gradlew :agents:agent-intent:bootRun
```

What you'll see, in order:

1. `[trace intent-...]` lines — the model call(s) with token/cost counters.
2. **Possibly a clarifying question.** The model has one tool, `ask_human`; if it
   finds the notes materially ambiguous it asks before extracting:
   ```
   [clarification needed] Should tax follow the shipping region even for digital goods?
   >
   ```
   Type an answer. Your Q&A is recorded verbatim in the produced artifacts
   (`## Clarifications` section + `clarification:1` in their provenance) — the
   elicitation *is* part of the audit trail.
3. **Possibly duplicate/conflict flags** (`[flag] REQ-0002 DUPLICATES REQ-0001: ...`)
   if you re-run with overlapping notes — also written into the artifact files
   under `## Review flags` so you see them at approval time.
4. **One approval prompt per artifact:**
   ```
   [approval requested] GOAL-0001 — Reduce cart abandonment at checkout
   approve? (y/n + optional feedback):
   ```
   Answer `y` (or `yes`). Reject with `n too vague` — the artifact returns to
   DRAFT with your feedback and the run continues.
5. `GOAL-0001 APPROVED`, `REQ-0001 APPROVED`, ...

Now look at what it made:

```bash
ls workspace/goals workspace/requirements workspace/usecases 2>/dev/null
cat workspace/requirements/REQ-0001.md
```

Note the frontmatter: `status: APPROVED`, `approvedBy: <your username>`,
`derivesFrom: ['GOAL-0001@<40-hex sha>']` — that `@sha` pin is what makes
staleness detection survive restarts — and `sourceRefs` pointing at the inbox
file *at the exact content version you ingested*, plus verbatim
`## Source quotes`. Nothing is invented without a trace.

> **Your ids may differ.** Ids are allocated in order (GOAL-0001, REQ-0001, …)
> but how many goals/requirements/use-cases the model extracts varies. **Read
> the ids off the console** and substitute them in every command below.

---

## 3. Step 2 — Specification

```bash
./gradlew :agents:agent-spec:bootRun --args="REQ-0001"
```

(Multiple inputs are fine: `--args="REQ-0001 UC-0001"`. The agent **refuses
non-APPROVED inputs** — that's the gate working.)

Approve at the prompt as before. Result: `workspace/specs/SPEC-0001.md` with
Gherkin acceptance criteria:

```bash
cat workspace/specs/SPEC-0001.md
```

Check the `## Acceptance criteria` section — these exact scenarios become the
test artifacts in step 5, and a `## Testability flags` section appears if the
model judged any requirement untestable.

---

## 4. Step 3 — Design

```bash
./gradlew :agents:agent-design:bootRun --args="SPEC-0001"
```

Approve each produced artifact. Look at the ADR:

```bash
cat workspace/adrs/ADR-0001.md
```

Every ADR **must** carry at least two considered alternatives with trade-offs —
that's enforced structurally (the domain model rejects single-alternative ADRs),
not by prompt hope. Also note the provenance assumption: design is grounded in
graph summaries, not source code — an honest Phase-scope marker.

---

## 5. Step 4 — Backlog

```bash
./gradlew :agents:agent-backlog:bootRun --args="SPEC-0001 ADR-0001"
```

Approve the epic and stories. Inspect:

```bash
cat workspace/backlog/STORY-0001.md
```

Frontmatter carries `level`, `estimate`, `acceptanceHook` (which Gherkin
scenario the story serves) and `dependsOn` — all canonical, all restart-safe.
The run ends with the stale report (`backlog stale report: nothing needs
revalidation` — remember it for step 7).

---

## 6. Step 5 — Tests

```bash
./gradlew :agents:agent-testgen:bootRun --args="SPEC-0001"
```

Two artifacts per spec:

- `workspace/tests/TEST-0001.feature.md` — a Cucumber feature, derived
  **deterministically** from the spec's Gherkin (no model call for this half:
  zero cost, zero hallucination surface).
- `workspace/tests/TEST-0002.steps.md` — model-generated step-definition
  skeletons (a review draft; its provenance says so).

Check the `verifies:` line in the feature's frontmatter — it names the spec
*and* every story whose `acceptanceHook` matched a scenario. The
intent→verification chain is closed.

**Optional — place and run against a real repo:**

```bash
SDLC_TARGET_REPO=/path/to/your/project \
SDLC_TEST_CMD="./gradlew test" \
SPRING_PROFILES_ACTIVE=target-repo \
./gradlew :agents:agent-testgen:bootRun --args="SPEC-0001"
```

It prints exactly what it writes where *before* running, executes only the
configured command (the model never chooses commands), and stamps
`lastRun: passed|failed` into the TEST artifacts' frontmatter.

---

## 7. Step 6 — The point of it all: change the requirement

Edit the body text of `workspace/requirements/REQ-0001.md` (change a sentence
below the closing `---`; leave the frontmatter alone). Then run any downstream
agent against the spec, e.g.:

```bash
./gradlew :agents:agent-design:bootRun --args="SPEC-0001"
```

It fails fast:

```
IllegalStateException: SPEC-0001 is NEEDS_REVALIDATION, expected APPROVED
```

That's the moat working: at startup the projection was rebuilt from files, the
spec's pinned `REQ-0001@<old-sha>` no longer matched the file you edited, and
the staleness cascaded transitively — spec, design artifacts, stories, tests,
the whole chain is `NEEDS_REVALIDATION`. Every downstream agent now refuses its
input the same way.

Two things worth noticing:

```bash
grep -rl "NEEDS_REVALIDATION" workspace     # finds nothing
git diff --stat                              # your edit is the only file change
```

Staleness *flags* are projection state, recomputed from the `@sha` pins at every
start — your artifact files were never touched, so there is nothing to clean up
and nothing that can drift. The flags exist wherever the graph is rebuilt, which
is everywhere, every time.

**Recover** by reverting the edit (`git checkout -- workspace/requirements/`)
— the pins match again and everything is clean. Re-stamping a *deliberate*
change as re-validated exists in the library (`RevalidateArtifactUseCase`,
used automatically by approvals) but has no CLI yet — an honest gap, tracked
for the next phase.

---

## 8. Variations

| Run with | Effect |
|---|---|
| `SPRING_PROFILES_ACTIVE=openai` + `OPENAI_*` env | any OpenAI-compatible endpoint (Ollama, vLLM, LM Studio…) |
| `SPRING_PROFILES_ACTIVE=postgres` + `SDLC_DB_URL/USER/PASSWORD` | durable graph projection (files stay canonical) |
| `SPRING_PROFILES_ACTIVE=git-approval` | the workspace becomes its own git repo: proposals on `proposal/<id>` branches, approval = merge to main |
| `SPRING_PROFILES_ACTIVE=otel` (+ `OTEL_EXPORTER_OTLP_ENDPOINT`) | run/step spans with `gen_ai.usage.*` token and cost attributes |

Profiles combine: `SPRING_PROFILES_ACTIVE=openai,postgres,git-approval,otel`.

---

## 9. Troubleshooting

- **The model returned prose / parse failure mid-run** — runs are safe to
  repeat: intent leaves the inbox file in place on failure (only success moves
  it to `processed/`); the other agents wrote nothing yet when parsing fails.
- **You rejected an artifact by mistake** — it's back in DRAFT; there is no
  re-propose CLI yet, so for a demo the simplest recovery is deleting the
  artifact file and re-running the producing agent.
- **`usage: bootRun --args=...`** — the agent needs the artifact ids as args
  (read them from the previous step's console output).
- **Approval prompt never appears / auto-rejects** — you're not running via
  `./gradlew ... bootRun` from a terminal (stdin must be attached; the build
  files wire this for bootRun).
- **Cost shows `$0.0000`** — the model id isn't in
  `libs/adapter-llm-spring/src/main/resources/pricing.yaml`; add it (config,
  not code — requires a rebuild).
- Want to start over? `git checkout -- workspace && git clean -fd workspace`
  restores the committed demo state.
