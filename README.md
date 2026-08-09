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
| `agent-runtime` | Sandboxed tool execution — filesystem, terminal, git. *(not started)* |
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
  knows LangChain4j exists; `agent-runtime`'s eventual filesystem/terminal/
  git tools (Weeks 6–8) implement `AgentTool` directly, no LangChain4j
  import needed. `ToolCallingChatEngine` handles the actual tool-calling
  round trip, and `SharedExecutionContext` is the object (tenant + LLM
  client + available tools) threaded through a single agent invocation.

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
| `POST /agents/ping-with-tools` | ADMIN, DEVELOPER | Spike endpoint: same as above, but through `SharedExecutionContext` with one demo tool registered — proves the tool-calling loop (model decides to call a tool, tool runs, result folded into a final answer) works end to end. |
| `GET /actuator/health` | none | Health check |

## Test

```bash
mvn test
```

Integration tests (`@SpringBootTest`, tagged `@ActiveProfiles("test")`) run
against `agent_hub_test`, a **separate** database, so test runs never
create or leave behind data in the dev DB (`agent_hub`). Flyway migrates it
automatically on first test run, same as the dev DB.

136 tests as of the last update — unit tests (mocked) for every
service/security/util class, plus integration tests that boot the real
Spring context, real security filter chain, and real Postgres RLS to catch
the class of bug mocks can't (e.g. cross-tenant isolation, RBAC denials,
and the RLS-enforcement bugs described below).

## Status

Following a self-imposed weekly build plan (~3.5h/day, 5 days/week):

- [x] **Week 1** — Multi-module Maven skeleton, RLS-backed tenant schema, stateless security baseline
- [x] **Week 2** — JWT auth, register/login, platform API key issuance
- [x] **Week 3** — Role-based `@PreAuthorize`, KMS-style (local AES-GCM) credential encryption
- [x] **Week 4** — `LlmEngineFactory` + real Anthropic round-trip proof (`/agents/ping`)
- [x] **Week 5** — `SharedExecutionContext`, `AgentTool` interface, tool-calling loop proven live (`/agents/ping-with-tools`)
- [ ] Weeks 6–8 — Sandboxed tool execution (filesystem/terminal/git) implementing `AgentTool` for real
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
