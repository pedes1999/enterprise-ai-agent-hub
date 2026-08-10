# Enterprise AI Agent Hub

Model-agnostic, multi-tenant platform for automating full-stack engineering
workflows via LLM-driven agents. A tenant registers, adds its own vendor API
credentials (Anthropic/OpenAI/Gemini), and triggers agents that read code,
call an LLM, and act on a real repository — with tenant isolation enforced
at the database layer (Postgres RLS), not just in application code.

## Module layout

| Module | Responsibility |
|---|---|
| `common-dto` | Shared request/response contracts. No framework dependency. |
| `agent-core` | Provider-agnostic LLM abstraction (LangChain4j). Framework-light — no Spring dependency. |
| `agent-runtime` | Sandboxed tool execution via E2B microVMs (through an internal sidecar — see below). Two real tools: `RunShellCommandTool`, `GitCloneTool`. Filesystem tools not started. |
| `gateway-api` | Spring Boot app: auth, tenant/user/credential management, agent invocation. |

## Architecture notes

- **Tenant isolation is enforced by Postgres Row-Level Security**, not
  application-level `WHERE tenant_id = ?` filtering — every tenant-scoped
  table has an RLS policy keyed off a `app.current_tenant_id` session
  variable, set on every JDBC connection checkout by `TenantAwareDataSource`.
  `FORCE ROW LEVEL SECURITY` is set on every such table so this applies even
  to the app's own connection role, not just other Postgres roles.
  `platform_api_keys` is the one deliberate exception: its `SELECT` policy
  is open, because looking up a key by its hash is how the app discovers
  *which* tenant a caller belongs to in the first place — reads on that one
  table are scoped by the app code instead (see comments in
  `PlatformApiKeyRepository`).
- **3-role model**: `ADMIN` (users, vendor credentials, platform API keys,
  trigger agents, view history), `DEVELOPER` (trigger agents, view history),
  `READONLY` (view history only). Enforced via Spring Method Security
  (`@PreAuthorize`).
- **Vendor credentials are encrypted at rest** (AES-256-GCM) behind a
  `CredentialEncryptor` interface — `LocalAesGcmCredentialEncryptor` today,
  swappable for real AWS KMS/Vault later without touching call sites.
- **LLM access is provider-agnostic** via `LlmEngineFactory` in `agent-core`
  — given a tenant's decrypted credential, returns a LangChain4j
  `ChatLanguageModel` regardless of vendor. Only Anthropic is wired up so
  far.
- **Tools are our own interface** (`AgentTool`), not LangChain4j's
  reflection-based `@Tool` annotation — `agent-core` is the only module that
  knows LangChain4j exists; `agent-runtime`'s filesystem/terminal/git tools
  implement `AgentTool` directly, no LangChain4j import needed.
  `ToolCallingChatEngine` handles the actual tool-calling round trip, and
  `SharedExecutionContext` is the object (tenant + LLM client + available
  tools) threaded through a single agent invocation. `AgentTool.execute()`
  takes an explicit `ToolExecutionContext` (tenantId, executionId) rather
  than a ThreadLocal — sandboxed execution goes over HTTP to a sidecar, and
  ThreadLocals don't survive a thread hop (the same bug class fixed in
  `TenantAwareDataSource`, this time for credential/compute access instead
  of a DB insert).
- **Sandboxed tool execution is vendor-neutral at the API boundary**:
  `SandboxClient` (`agent-runtime`) knows nothing about E2B specifically —
  `SandboxClientHttpImpl` calls an internal HTTP sidecar
  (`agent-runtime/sidecar/`, Node + E2B's official JS SDK, since E2B has no
  official Java SDK and its gRPC data-plane protocol isn't a documented
  external contract) rather than a hand-rolled Java gRPC client. Every
  execution gets a fresh, ephemeral sandbox (verified: state from one
  execution is genuinely invisible to the next), with an enforced timeout,
  output-size cap, and a scoped credential injected only where needed.
  Every sandboxed tool call is audited to `tool_executions`
  (`AbstractSandboxedTool` → `ToolExecutionListener` SPI →
  `JpaToolExecutionListener`), RLS-scoped like everything else.
- **Git operations run inside the sandbox too, never in-process** —
  `GitCloneTool` runs `git clone` as a sandboxed shell command via
  `SandboxClient`, the same way `RunShellCommandTool` does. `agent-runtime`
  deliberately has no JGit dependency: an in-process git library would
  clone LLM-directed, potentially-untrusted repository content directly
  onto our own host, defeating the sandbox entirely. It's also the first
  tool to use `CredentialResolver` (a dedicated `tool_credentials` table,
  separate from `vendor_credentials` — a git PAT has a different
  rotation/scoping story than an LLM API key) — the credential is injected
  as a sandbox env var and applied via `git -c http.extraHeader=...`
  rather than embedded in the clone URL, so it's never written to the
  cloned repo's own `.git/config`.
- **Agent execution is a real, durable, DB-backed job queue**
  (`POST /agents/execute` → `GET /agents/executions/{id}`), not just the
  synchronous spike endpoints. `agent_executions` (a Week 1 table that sat
  unused until now) is the queue itself: `AgentJobWorker` polls it with
  `SELECT ... FOR UPDATE SKIP LOCKED` (`AgentExecutionRepository.claimNextQueued`),
  durable across restarts and safe under multiple concurrent workers/app
  instances with no message broker needed at this scale. The one wrinkle:
  the worker is a system component, not acting for any one tenant, so it
  needs to see QUEUED rows across every tenant to claim one — rather than
  a second Postgres role with `BYPASSRLS` (a much bigger, harder-to-audit
  escape hatch), `agent_executions`' RLS policy has a narrow OR clause
  recognizing one reserved sentinel value (`TenantContext.SYSTEM_WORKER_TENANT_ID`)
  that only `AgentJobWorker` ever sets, and only for the claim step — it
  switches to the job's real tenant id before doing anything else (running
  the prompt, resolving credentials, sandboxed tool execution, audit
  logging), so everything past the claim is exactly as tenant-scoped as a
  real request. `AgentPromptRunner` holds the actual "run this prompt with
  tools for this tenant" logic, extracted out of `AgentPingService` so the
  synchronous spike and the async worker share one implementation instead
  of two copies drifting apart. See `CODE_WALKTHROUGH.md` for the full
  claim → run → complete call stack.

**Two real bugs were caught by live-testing `GitCloneTool` against actual
E2B infrastructure** (not by unit tests, which all passed throughout —
mocks can't catch either of these): E2B's SDK throws rather than returning
a result when a sandboxed command exits non-zero, which the sidecar was
originally turning into a fake 502 "infrastructure failure" instead of the
real exit code/stdout/stderr; and `/workspace` at the sandbox's filesystem
root isn't writable by its default user (`/tmp` is). Both fixed, both now
covered by tests that lock in the corrected behavior.

## Setup

Requires a local Postgres instance and a dedicated app role (never the
Postgres superuser — RLS is silently ignored for superusers/BYPASSRLS
roles):

```sql
CREATE ROLE hub_user LOGIN PASSWORD 'password';
CREATE DATABASE agent_hub OWNER hub_user;
CREATE DATABASE agent_hub_test OWNER hub_user;  -- used by integration tests only
```

Override `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` (see `application.yml`) if
you're not using these exact local defaults. `JWT_SECRET` and
`CREDENTIAL_LOCAL_KEY` also have dev-only defaults baked in — override both
in any shared/production environment.

For sandboxed tool execution (`RunShellCommandTool`, reached via
`/agents/ping-with-tools`), the sidecar also needs to be running — see
[`agent-runtime/sidecar/README.md`](agent-runtime/sidecar/README.md).
Quick version: `cd agent-runtime/sidecar`, set a real `E2B_API_KEY` in
`.env`, `npm start` (or `docker build`/`docker run` — both verified
working). Without it, `/agents/ping-with-tools` still works for prompts
that don't need the shell command tool; it only fails (502) if the model
tries to use it.

`AgentJobWorker` (the `/agents/execute` queue poller) runs automatically
on startup — `JOB_WORKER_ENABLED=false` disables it entirely (no bean at
all) if you want to inspect `agent_executions` rows without a background
process racing you; `JOB_WORKER_POLL_INTERVAL_MS` controls how often it
polls (default 2000ms).

## Build

```bash
mvn clean install
```

## Run (gateway-api)

```bash
cd gateway-api
mvn spring-boot:run
```

Flyway migrates `agent_hub` automatically on startup. First request should
be registering a tenant:

```bash
curl -X POST localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"tenantName":"Acme","tenantSlug":"acme","email":"admin@acme.com","password":"password123"}'
```

The response includes a JWT — use it as `Authorization: Bearer <token>` on
every other endpoint. A full request collection covering every endpoint
(including negative cases like RBAC denials and cross-tenant isolation) is
in [`postman/enterprise-ai-agent-hub.postman_collection.json`](postman/enterprise-ai-agent-hub.postman_collection.json) — import it into Postman.

## API surface (so far)

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /auth/register` | none | Create a tenant + its first (ADMIN) user |
| `POST /auth/login` | none | Get a JWT |
| `POST /users` · `GET /users` · `PATCH /users/{id}/role` · `DELETE /users/{id}` | ADMIN | Manage a tenant's users |
| `POST /api-keys` · `GET /api-keys` · `DELETE /api-keys/{id}` | ADMIN | Issue/revoke platform API keys (for future CI/CD & webhook triggers) |
| `PUT /vendor-credentials` · `GET /vendor-credentials` · `DELETE /vendor-credentials/{provider}` | ADMIN | Store/rotate/remove encrypted LLM vendor credentials |
| `PUT /tool-credentials` · `GET /tool-credentials` · `DELETE /tool-credentials/{credentialKind}` | ADMIN | Store/rotate/remove encrypted credentials sandboxed tools need (e.g. a git PAT) |
| `POST /agents/ping` | ADMIN, DEVELOPER | Spike endpoint: real round-trip to the tenant's configured LLM, proves the credential → LangChain4j → provider chain works. Not the real agent execution model. |
| `POST /agents/ping-with-tools` | ADMIN, DEVELOPER | Spike endpoint: through `SharedExecutionContext` with `CurrentDateTimeTool` (trivial demo), `RunShellCommandTool`, and `GitCloneTool` (both real, sandboxed, via E2B) registered — proves the full tool-calling loop, including real sandbox execution and audit logging, works end to end. Note: `ToolCallingChatEngine` is single-round only, so a prompt that wants to chain two tool calls (e.g. "clone, then list files") only executes the first. |
| `POST /agents/execute` | ADMIN, DEVELOPER | The real (durable, async) execution model: enqueues an `agent_executions` row (`QUEUED`) and returns its id immediately — `202 Accepted` — without waiting for the LLM or any tool. `AgentJobWorker` picks it up separately. |
| `GET /agents/executions/{id}` | ADMIN, DEVELOPER, READONLY | Poll a queued/running/finished execution's current status, and once `SUCCEEDED`/`FAILED`, its reply/error. Tenant-isolated the same way as everything else (RLS + an explicit `tenant_id` filter). |
| `GET /actuator/health` | none | Health check |

## Test

```bash
mvn test
```

Integration tests (`@SpringBootTest`, tagged `@ActiveProfiles("test")`) run
against `agent_hub_test`, a **separate** database, so test runs never
create or leave behind data in the dev DB (`agent_hub`). Flyway migrates it
automatically on first test run, same as the dev DB.

250 automated tests as of the last update (14 `agent-core` + 46
`agent-runtime` + 190 `gateway-api`) — unit tests (mocked) for every
service/security/util class, plus integration tests that boot the real
Spring context, real security filter chain, and real Postgres RLS to catch
the class of bug mocks can't (e.g. cross-tenant isolation, RBAC denials,
audit-table RLS, and the RLS-enforcement bugs described below).
`AgentExecutionQueueIntegrationTest` is the one covering the job queue
itself: the RLS worker-sentinel carve-out actually lets a claim see jobs
across tenants (and nothing else does), the claim → complete lifecycle,
and `GET /agents/executions/{id}`'s tenant isolation — all against real
Postgres, not mocks.

Two additional manual integration tests exist for `agent-runtime`
(`*ManualIT` naming — excluded from `mvn test` by Surefire's default
pattern, gated behind `SANDBOX_SIDECAR_URL` besides), verified passing
against a real E2B account both via a directly-run sidecar and the
Dockerized one -- including the sidecar bugfix described above, which was
found through exactly this kind of real-infrastructure test, not a mock:

```bash
SANDBOX_SIDECAR_URL=http://localhost:8090/ mvn test -pl agent-runtime -Dtest=RunShellCommandToolManualIT
```

## Status

Following a self-imposed weekly build plan (~3.5h/day, 5 days/week):

- [x] **Week 1** — Multi-module Maven skeleton, RLS-backed tenant schema, stateless security baseline
- [x] **Week 2** — JWT auth, register/login, platform API key issuance
- [x] **Week 3** — Role-based `@PreAuthorize`, KMS-style (local AES-GCM) credential encryption
- [x] **Week 4** — `LlmEngineFactory` + real Anthropic round-trip proof (`/agents/ping`)
- [x] **Week 5** — `SharedExecutionContext`, `AgentTool` interface, tool-calling loop proven live (`/agents/ping-with-tools`)
- [x] **Weeks 6–8 (started)** — `SandboxClient` abstraction, E2B-backed sidecar (verified against real infra, Node-direct and Dockerized), `RunShellCommandTool` + `GitCloneTool` reachable end to end (`/agents/ping-with-tools` → real E2B sandbox → audited to `tool_executions`, RLS-scoped). Dedicated `tool_credentials` table + `CredentialResolver` real implementation for tool-specific credentials (git PAT). Filesystem tools not started.
- [x] **Weeks 9–10** — Durable job orchestration: `POST /agents/execute` / `GET /agents/executions/{id}`, backed by a DB-polling queue (`AgentJobWorker` + `SELECT ... FOR UPDATE SKIP LOCKED` against `agent_executions`, no message broker needed at this scale — see the architecture notes above for the RLS worker-sentinel design). Verified live against the real dev DB: a queued job was picked up and completed by the background poller with no manual trigger involved.
- [ ] Week 11 — CLI client, GitHub Actions integration, webhook receiver
- [ ] Weeks 12–13 — Agent #1: automated security patching (SonarQube → LLM patch → verified PR)

Also worth knowing if you're reading the history: two real bugs were found
and fixed after the fact — `platform_api_keys`' RLS policy originally
blocked the very pre-auth lookup it exists for (fixed in Week 2), and RLS
was not actually being enforced at all for a while (Postgres exempts table
owners from their own RLS policies by default; fixed by adding
`FORCE ROW LEVEL SECURITY`, which then surfaced a second bug in how the
tenant session variable was being set — see `TenantAwareDataSource`'s
javadoc for the full story).
