# SDLC Agents

LLM-powered SDLC agents on a hexagonal Java monorepo. Four agents close the upstream
loop: **agent-intent** turns stakeholder notes into grounded goals/requirements/use cases,
**agent-spec** turns requirements into specification drafts with acceptance criteria,
**agent-design** produces ADRs with mandatory alternatives, and **agent-backlog** decomposes
approved specs and designs into an estimated backlog. Every artifact is human-gated,
written as a markdown file with YAML frontmatter, and linked in a shared traceability graph.

## Build

```bash
./gradlew build   # all modules, unit + ArchUnit + integration tests
```

## The upstream loop

Run the agents in order. Each agent writes PROPOSED artifacts; a human approves them on
stdin before the next agent in the chain can run.

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

### Inbox convention

Place any stakeholder document as a `*.md` file in `workspace/inbox/`. Agent-intent
reads every file there, processes it, then moves it to `workspace/inbox/processed/`
(successful) or leaves it in place (failure). Re-running agent-intent after a partial
failure is safe — only unprocessed files are picked up.

**Demo seed:** `workspace/inbox/payment-notes.md` contains a short stakeholder meeting
summary about cart abandonment at checkout and a regional-tax requirement — enough for
the full intent→spec→design→backlog chain without any edits.

### LLM provider

By default the agents use the Anthropic API (`ANTHROPIC_API_KEY`). For any
OpenAI-compatible endpoint, activate the `openai` profile:

```bash
export SPRING_PROFILES_ACTIVE=openai
export OPENAI_BASE_URL=https://api.openai.com   # default if unset
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-5.2                     # default if unset
./gradlew :agents:agent-intent:bootRun
```

Works with Ollama, vLLM, LM Studio, LiteLLM, or any server that speaks the OpenAI
chat-completions API. See the Profiles table below for all switches.

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
| `openai` | OpenAI-compatible LLM endpoint | OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_MODEL |
| `postgres` | durable graph projection | SDLC_DB_URL, SDLC_DB_USER, SDLC_DB_PASSWORD |
| `git-approval` | workspace is a git repo; proposals on branches, approval merges | — |
| `otel` | OTLP span export per agent run/step | OTEL_EXPORTER_OTLP_ENDPOINT |

Profiles combine: `SPRING_PROFILES_ACTIVE=openai,postgres,git-approval,otel`.

## Layout

- `libs/domain-shared` — SDLC vocabulary (artifact IDs, statuses, provenance, events)
- `libs/agent-core` — provider-free agent loop, ports, guardrails
- `libs/traceability-graph` — requirement/spec graph + markdown frontmatter projection
- `agents/agent-spec` — the spec agent: domain, use cases, Spring AI adapter, bootstrap
- ArchUnit tests enforce the hexagonal boundaries (domain/application never touch Spring).
