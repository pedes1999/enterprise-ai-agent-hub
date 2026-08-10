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
| `agent-runtime` | Sandboxed tool execution via E2B microVMs (through an internal sidecar — see below). One real tool so far: `RunShellCommandTool`. Filesystem/git tools not started. |
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
| `POST /agents/ping` | ADMIN, DEVELOPER | Spike endpoint: real round-trip to the tenant's configured LLM, proves the credential → LangChain4j → provider chain works. Not the real agent execution model. |
| `POST /agents/ping-with-tools` | ADMIN, DEVELOPER | Spike endpoint: through `SharedExecutionContext` with `CurrentDateTimeTool` (trivial demo) and `RunShellCommandTool` (real, sandboxed, via E2B) registered — proves the full tool-calling loop, including real sandbox execution and audit logging, works end to end. |
| `GET /actuator/health` | none | Health check |

## Test

```bash
mvn test
```

Integration tests (`@SpringBootTest`, tagged `@ActiveProfiles("test")`) run
against `agent_hub_test`, a **separate** database, so test runs never
create or leave behind data in the dev DB (`agent_hub`). Flyway migrates it
automatically on first test run, same as the dev DB.

188 automated tests as of the last update (14 `agent-core` + 33
`agent-runtime` + 141 `gateway-api`) — unit tests (mocked) for every
service/security/util class, plus integration tests that boot the real
Spring context, real security filter chain, and real Postgres RLS to catch
the class of bug mocks can't (e.g. cross-tenant isolation, RBAC denials,
audit-table RLS, and the RLS-enforcement bugs described below).

Two additional manual integration tests exist for `agent-runtime`
(`*ManualIT` naming — excluded from `mvn test` by Surefire's default
pattern, gated behind `SANDBOX_SIDECAR_URL` besides), verified passing
against a real E2B account both via a directly-run sidecar and the
Dockerized one:

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
- [x] **Weeks 6–8 (started)** — `SandboxClient` abstraction, E2B-backed sidecar (verified against real infra, Node-direct and Dockerized), `RunShellCommandTool` reachable end to end (`/agents/ping-with-tools` → real E2B sandbox → audited to `tool_executions`, RLS-scoped). Filesystem and git tools not started.
- [ ] Weeks 9–10 — Job orchestration (message queue, durable execution tracking)
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
