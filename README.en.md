# ScriptForge

**Inspectable writing and autonomous review agents, powered by a durable Java runtime.**

[Live demo](https://coder1025.github.io/scriptforge/?demo=1) · [Source](https://github.com/coder1025/scriptforge) · [CI](https://github.com/coder1025/scriptforge/actions/workflows/ci.yml) · [中文说明](README.md)

ScriptForge separates creative decisions from execution guarantees. A Writer plans and edits scripts; an independent Reviewer reads frozen drafts, checks evidence and returns actionable issues. The Java host owns permissions, revisions, persistence, budgets and the final delivery gate.

The project is a greenfield implementation using synthetic examples. It does not include proprietary business code, digital-human vendor integrations, or production performance claims.

## Run

Requires JDK 21 and Maven 3.9+. No frontend build toolchain is required.

```bash
mvn clean verify
java -jar target/scriptforge-0.1.0.jar
```

Open [localhost:8080](http://localhost:8080). The default `DEMO` mode exercises the actual Java runtime and database using a deterministic model fixture. It demonstrates an unsupported claim, an evidence-bearing review, a targeted revision and a new review.

```bash
cp .env.example .env
docker compose up --build -d
```

Compose exposes the service on host loopback only. Data persists in a named volume.

## Three distinct experiences

| Mode | What actually runs |
| --- | --- |
| Static Pages / `?demo=1` | Read-only replay of a fixed synthetic fixture; no backend or model calls |
| Local `DEMO` | Real tools, persistence and execution host with a deterministic model substitute |
| `MODEL` | The same host calling a configured tool-capable OpenAI-compatible Chat Completions endpoint |

Set `SCRIPT_FORGE_MODE=MODEL`, `MODEL_BASE_URL`, `MODEL_NAME` and `MODEL_API_KEY` on the server for real generation. The model must support the configured tool-calling parameters. `ADMIN_TOKEN` is required when real-model mode listens on a non-loopback address, including inside Docker. Never ship credentials in the static site.

`DEMO_READY` identifies a completed fixture run. It is not evidence of real-model quality. `READY` identifies a real-model artifact that passed the configured host gates; semantic correctness still needs task-appropriate review.

## Engineering focus

- Independent Writer and Reviewer executions, prompts and tool permissions.
- Immutable draft versions; old reports cannot approve new drafts.
- Durable tool intents, local idempotency, bounded leases and conservative recovery of uncertain model calls.
- Shared task budgets across both agents.
- Dynamic context assembled from current state, exact facts and complete recent tool round trips.
- Review coverage over sections, adjacency and declared dependencies.
- A browser workspace for traces, reports, versions, checkpoints and Markdown export.

The implementation uses Java 21, Spring Boot 3.5, JDBC, Flyway, H2 and Java HttpClient. The UI uses plain HTML, CSS and JavaScript.

## Scope

This release is a single-workspace, single-instance application. Tasks are bounded to 30 minutes of target duration and 24 planned sections. Text-based timing is a heuristic, not a TTS guarantee. It does not implement multi-tenant isolation, unbounded 24-hour generation, internet-wide semantic deduplication, or production cluster orchestration.

A second agent adds an independent review opportunity and also adds cost and possible correlated mistakes. Quality improvements require measured comparison, not just a successful fixture run.

See [architecture](docs/architecture.md), [API and tools](docs/api-and-tools.md), [evaluation](docs/evaluation.md), [publishing](docs/publishing.md), [security](SECURITY.md) and [contributing](CONTRIBUTING.md). The detailed guides and UI are currently Chinese-first.

Licensed under [Apache-2.0](LICENSE).

