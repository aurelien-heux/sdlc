# SDLC Agents

LLM-powered SDLC agents on a hexagonal Java monorepo. Five agents close the loop:
**agent-intent** turns stakeholder notes into grounded goals/requirements/use cases,
**agent-spec** turns requirements into specification drafts with acceptance criteria,
**agent-design** produces ADRs with mandatory alternatives, **agent-backlog** decomposes
approved specs and designs into an estimated backlog, and **agent-testgen** turns approved
specs into executable Cucumber test artifacts with `VERIFIES` traceability. Every artifact
is human-gated, written as a markdown file with YAML frontmatter, and linked in a shared
traceability graph.

## Build

```bash
./gradlew build   # all modules, unit + ArchUnit + integration tests
```

## The upstream loop

Run the agents in order. Each agent writes PROPOSED artifacts; a human approves them on
stdin before the next agent in the chain can run.

> **First time?** Follow the step-by-step session in
> [docs/live-walkthrough.md](docs/live-walkthrough.md) — it includes the expected
> console output, what to look at in the produced files, and the change-propagation demo.

### 1. Intent — inbox notes to goals/requirements/use cases

```bash
# Drop a stakeholder document in workspace/inbox/ first (see demo seed below),
# then run agent-intent with no arguments — it processes every *.md in the inbox.
export ANTHROPIC_API_KEY=sk-ant-...
./gradlew :agents:agent-intent:bootRun
```

The agent may ask one clarifying question per ambiguity via the `ask_human` tool;
answers are appended to each artifact under `## Clarifications` for auditability.
Approve each GOAL/REQ/NFR/UC on stdin when prompted. Produces ids like `REQ-0001`.

### 2. Spec — requirements to specifications

```bash
./gradlew :agents:agent-spec:bootRun --args="REQ-0001"
```

Pass one or more approved requirement ids. Approve the resulting `SPEC-xxxx` on stdin.

### 3. Design — spec to ADR + design artifacts

```bash
./gradlew :agents:agent-design:bootRun --args="SPEC-0001"
```

The design agent enforces at least two alternatives in every ADR. Approve `ADR-xxxx`
and any `DES-xxxx` artifacts on stdin.

### 4. Backlog — spec + design to epics/stories/tasks

```bash
./gradlew :agents:agent-backlog:bootRun --args="SPEC-0001 ADR-0001"
```

Produces `EPIC-xxxx`, `STORY-xxxx`, and `TASK-xxxx` items with estimates and
`DEPENDS_ON` edges. Approve each item on stdin.

### 5. Testgen — spec to executable test artifacts

```bash
./gradlew :agents:agent-testgen:bootRun --args="SPEC-0001"
```

Produces two `TEST-xxxx` artifacts per spec: a Cucumber `.feature` file transcribed
**deterministically** from the spec's acceptance criteria (no LLM, no hallucination
surface) and an LLM-generated step-definition skeleton (`SDLC_TEST_LANGUAGE`, default
`java`). Both `VERIFIES` the spec; the feature also `VERIFIES` every story whose
`acceptanceHook` names one of its scenarios. Approve each on stdin. With the
`target-repo` profile the artifacts are then placed into a configured repository and the
**configured** test command runs (never model-chosen); pass/fail lands in the artifacts'
frontmatter as `lastRun`/`lastRunAt`:

```bash
export SPRING_PROFILES_ACTIVE=target-repo
export SDLC_TARGET_REPO=/path/to/your/repo            # required
export SDLC_TEST_CMD="./gradlew test"                 # default if unset
export SDLC_FEATURES_DIR=src/test/resources/features  # default if unset
export SDLC_STEPS_DIR=src/test/java                   # default if unset
./gradlew :agents:agent-testgen:bootRun --args="SPEC-0001"
```

The agent prints what it wrote where before running the command.

### Inbox convention

Place any stakeholder document as a `*.md` file in `workspace/inbox/`. Agent-intent
reads every file there, processes it, then moves it to `workspace/inbox/processed/`
(successful) or leaves it in place (failure). Re-running agent-intent after a partial
failure is safe — only unprocessed files are picked up.

**Demo seed:** `workspace/inbox/payment-notes.md` contains a short stakeholder meeting
summary about cart abandonment at checkout and a regional-tax requirement — enough for
the full intent→spec→design→backlog→testgen chain without any edits.

### LLM provider

By default the agents use the Anthropic API (`ANTHROPIC_API_KEY`). For any
OpenAI-compatible endpoint, activate the `openai` profile:

```bash
export SPRING_PROFILES_ACTIVE=openai
export OPENAI_BASE_URL=https://api.openai.com/v1        # default if unset; include /v1
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-5.2                             # default if unset
export OPENAI_COMPLETIONS_PATH=/chat/completions        # default if unset; appended to the base URL
./gradlew :agents:agent-intent:bootRun
```

Works with Ollama, vLLM, LM Studio, LiteLLM, or any server that speaks the OpenAI
chat-completions API. The request URL is `OPENAI_BASE_URL` + `OPENAI_COMPLETIONS_PATH`,
so keep the `/v1` (or your gateway's prefix) in the base URL. See the Profiles table
below for all switches.

## Run the spec agent standalone

The spec agent can also be run on its own against pre-existing requirements:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./gradlew :agents:agent-spec:bootRun --args="REQ-0001"
```

Model and options live in `agents/agent-spec/src/main/resources/application.yaml`
(default: `claude-sonnet-4-6`, max 4096 tokens).

## Profiles

| Profile | Effect | Env vars |
|---|---|---|
| (none) | in-memory graph, plain files, console trace | ANTHROPIC_API_KEY |
| `openai` | OpenAI-compatible LLM endpoint | OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_MODEL, OPENAI_COMPLETIONS_PATH |
| `postgres` | durable graph projection | SDLC_DB_URL, SDLC_DB_USER, SDLC_DB_PASSWORD |
| `git-approval` | workspace is a git repo; proposals on branches, approval merges | — |
| `otel` | OTLP span export per agent run/step | OTEL_EXPORTER_OTLP_ENDPOINT |
| `target-repo` | agent-testgen places generated tests into a repo and runs the configured command | SDLC_TARGET_REPO (required), SDLC_TEST_CMD, SDLC_FEATURES_DIR, SDLC_STEPS_DIR |

Profiles combine: `SPRING_PROFILES_ACTIVE=openai,postgres,git-approval,otel`.

## Operator CLI

`tools/workspace-cli` is an LLM-free governance tool over the artifact
workspace (run from the repo root):

```bash
./gradlew :tools:workspace-cli:run --args="stale"                        # list NEEDS_REVALIDATION nodes
./gradlew :tools:workspace-cli:run --args="revalidate SPEC-0001 REQ-0001" # human re-blesses a deliberate upstream change
```

`revalidate` re-stamps the downstream artifact's `derivesFrom` pin to the
upstream's current sha and clears the staleness flag (status restored from
provenance). The workspace root is overridable with `-Dworkspace=<dir>`
(default `./workspace`) — works both with `gradlew run` (the property is
forwarded) and the installed dist.

## Layout

- `libs/domain-shared` — SDLC vocabulary (artifact IDs, statuses, provenance, events)
- `libs/agent-core` — provider-free agent loop, ports, guardrails
- `libs/traceability-graph` — requirement/spec graph + markdown frontmatter projection
- `agents/agent-spec` — the spec agent: domain, use cases, Spring AI adapter, bootstrap
- ArchUnit tests enforce the hexagonal boundaries (domain/application never touch Spring).
