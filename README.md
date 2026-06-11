# SDLC Agents

LLM-powered SDLC agents on a hexagonal Java monorepo. The first agent, **agent-spec**,
turns requirements (`workspace/requirements/REQ-*.md`) into specification drafts with
acceptance criteria, keeps a traceability graph, and asks a human to approve the result.

## Build

```bash
./gradlew build   # all modules, unit + ArchUnit + integration tests
```

## Run the demo

The demo generates a spec from one or more requirement IDs found in `./workspace`,
then prompts on stdin for approval.

### Anthropic (default)

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./gradlew :agents:agent-spec:bootRun --args="REQ-0001"
```

Model and options live in `agents/agent-spec/src/main/resources/application.yaml`
(default: `claude-sonnet-4-6`, max 4096 tokens).

### Any OpenAI-compatible endpoint (`openai` profile)

Activate the `openai` Spring profile and point `OPENAI_BASE_URL` at the server.
The client calls `<OPENAI_BASE_URL>/v1/chat/completions` (verified by the
integration test's stub), so give the **root** URL — without a `/v1` suffix.

```bash
export SPRING_PROFILES_ACTIVE=openai
export OPENAI_BASE_URL=https://api.openai.com   # default if unset
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-5.2                     # default if unset
./gradlew :agents:agent-spec:bootRun --args="REQ-0001"
```

Works with anything that speaks the OpenAI chat-completions API —
Ollama, vLLM, LM Studio, LiteLLM. Ollama example:

```bash
export SPRING_PROFILES_ACTIVE=openai
export OPENAI_BASE_URL=http://localhost:11434   # root URL; /v1/chat/completions is appended
export OPENAI_API_KEY=ollama                    # any non-empty value
export OPENAI_MODEL=qwen3
./gradlew :agents:agent-spec:bootRun --args="REQ-0001"
```

Provider selection is done with `spring.ai.model.chat` (`anthropic` by default,
flipped to `openai` by the profile), so exactly one `ChatModel` bean exists even
though both Spring AI starters are on the classpath.

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
