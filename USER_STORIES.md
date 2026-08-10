# Enterprise AI Agent Hub — User Stories

**Document purpose:** a functional overview of the platform as built to date, expressed as
user stories with acceptance criteria. Intended for stakeholders, prospective customers, and
partners evaluating the product — it describes *what the system does and for whom*, not how
it is implemented.

**Status:** covers everything shipped through Weeks 1–10 of the build plan (multi-tenant
foundation, auth & roles, encrypted credential management, LLM integration, sandboxed agent
tool execution, and durable async job execution). CLI/webhook triggers and the first fully
autonomous agent are on the roadmap but not yet built (see the last section).

---

## Personas

| Persona | Description |
|---|---|
| **Tenant** | A company or individual that has registered on the platform. All data — users, credentials, execution history — is scoped to a tenant and invisible to every other tenant. |
| **Admin** | A user within a tenant with full administrative rights: manages other users, credentials, and API keys. |
| **Developer** | A user within a tenant who can trigger agents and view execution history, but cannot manage users, credentials, or keys. |
| **Read-only user** | A user within a tenant who can view history only. |
| **CI/CD system or external caller** | An automated caller (build pipeline, webhook, script) that authenticates with a long-lived platform API key instead of a human login. |

---

## Epic 1 — Tenant Onboarding & Authentication

### US-1.1 — Register a new tenant
*As a prospective customer, I want to register my company (or myself as an individual) on
the platform, so that I get an isolated workspace with my own users, credentials, and data.*

**Acceptance criteria**
- A single registration call creates the tenant and its first user, who is automatically
  granted the Admin role.
- Registration requires a tenant name/slug, an admin email, and a password.
- Passwords are never stored in plaintext.
- No two tenants can share the same slug.
- Immediately after registering, the caller receives a session token — no separate login
  step is required to start using the platform.

### US-1.2 — Log in
*As a returning user, I want to log in with my email and password, so that I can access my
tenant's workspace.*

**Acceptance criteria**
- A successful login returns a time-limited session token (JWT).
- The token identifies which tenant and which role the user has, so every subsequent
  request is automatically scoped correctly without re-sending credentials.
- Invalid credentials are rejected without revealing whether the email or the password was
  the wrong part (no user enumeration).

### US-1.3 — Guaranteed data isolation between tenants
*As a tenant, I want an absolute guarantee that no other tenant can ever see, modify, or
enumerate my users, credentials, or execution history — even in the event of an application
bug — so that I can trust the platform with sensitive data.*

**Acceptance criteria**
- Every tenant-owned record (users, credentials, API keys, execution/audit history) is
  isolated at the database level, not only by application logic — isolation holds even if a
  future query forgets to filter by tenant.
- This has been verified with dedicated tests that attempt cross-tenant reads and confirm
  they return nothing.

---

## Epic 2 — User & Role Management

### US-2.1 — Invite and manage teammates
*As an Admin, I want to add, list, update the role of, and remove users within my tenant, so
that I can control who has access to what.*

**Acceptance criteria**
- Only Admins can create, list, change the role of, or remove users.
- A removed user immediately loses all access.

### US-2.2 — Role-appropriate access
*As a platform operator, I want three distinct access levels, so that customers can follow
least-privilege practice within their own team.*

**Acceptance criteria**
- **Admin**: full access — manage users, credentials, API keys; trigger agents; view history.
- **Developer**: trigger agents and view history; cannot manage users, credentials, or keys.
- **Read-only**: view history only; cannot trigger agents or manage anything.
- Every protected endpoint enforces its minimum required role; a request from an
  insufficiently privileged user is rejected before any business logic runs.

### US-2.3 — CI/CD and automation access
*As an Admin, I want to issue and revoke long-lived API keys separate from human logins, so
that build pipelines and webhooks can call the platform without a human's session token.*

**Acceptance criteria**
- API keys can be issued and revoked independently of user accounts.
- A revoked key stops working immediately.

---

## Epic 3 — Credential Management

### US-3.1 — Store an LLM provider credential
*As an Admin, I want to securely store my organization's LLM provider API key (e.g.
Anthropic), so that agents can call the model on my tenant's behalf without me re-entering
the key every time.*

**Acceptance criteria**
- Credentials are encrypted at rest; nothing that reads the database directly can recover
  the plaintext key.
- A credential can be rotated (replaced) or removed at any time.
- Only Admins can view (list metadata for), set, or remove credentials — the raw key value
  is never returned in any API response, including to the Admin who set it.

### US-3.2 — Store a tool-specific credential (e.g. a git access token)
*As an Admin, I want to separately store credentials that agent tools need — starting with a
git personal access token — so that an agent can clone a private repository without me
sharing my LLM provider key for an unrelated purpose.*

**Acceptance criteria**
- Tool credentials are stored and encrypted using the same standard as LLM credentials, but
  in a separate, dedicated store — an LLM key rotation and a git token rotation are
  independent operations.
- A credential is scoped to a "kind" (currently: `GIT`), so the platform can support more
  credential kinds later without redesigning storage.

---

## Epic 4 — Talking to an LLM

### US-4.1 — Provider-agnostic model access
*As a customer, I want to bring my own LLM provider credential and have the platform talk to
that provider on my behalf, so that I'm not locked into a single vendor.*

**Acceptance criteria**
- The platform's internal agent logic does not hard-code any specific vendor — it is
  written against a common interface that can be backed by different providers.
- Anthropic (Claude) is fully wired up today; the same interface is ready to support
  additional providers without changing agent logic.

### US-4.2 — Simple prompt round-trip
*As a Developer, I want to send a plain prompt to my tenant's configured LLM and get a reply
back, so that I can validate my credential and model configuration work correctly.*

**Acceptance criteria**
- Requires a Developer or Admin role.
- Fails clearly and immediately if no active LLM credential is configured for the tenant.

---

## Epic 5 — Agent Tool Use (Sandboxed Execution)

### US-5.1 — Let the model use tools to answer a request
*As a Developer, I want an agent to be able to decide, on its own, whether a task needs a
tool (running a command, cloning a repository, checking the date/time) and to use it
automatically, so that I don't have to manually orchestrate every step.*

**Acceptance criteria**
- The agent is given a set of tools it's allowed to use and decides for itself whether any
  are needed to answer the prompt.
- The final response indicates whether a tool was actually used, for transparency.

### US-5.2 — Run arbitrary shell commands safely
*As a Developer, I want an agent to be able to run a shell command (list files, run a build,
inspect output) without that command ever touching my own infrastructure, so that a bad or
even malicious command can't damage anything real.*

**Acceptance criteria**
- Every command runs inside a freshly created, fully isolated, disposable virtual machine
  that is destroyed immediately after the command finishes.
- Nothing about one execution (files written, environment state) is visible to the next —
  each run starts from a clean slate.
- A command that takes too long is automatically stopped; output is capped so a runaway
  command can't flood the response.

### US-5.3 — Clone a repository for the agent to work with
*As a Developer, I want an agent to be able to clone a git repository — including a private
one, using my stored credential — so that it can inspect or act on real code.*

**Acceptance criteria**
- Only secure (HTTPS) repository URLs are accepted.
- If the tenant has a git credential configured, it is used automatically and only for the
  duration of the clone; it is never written to disk in a way that would leak with the
  cloned content, and it is never visible in logs.
- If cloning fails (bad URL, missing repository, auth failure), the agent is told why, in a
  form it can reason about or relay back to the user, rather than the request silently
  erroring out.
- Cloning happens inside the same disposable sandbox as shell commands — a repository's
  contents, however untrusted, never touch platform infrastructure directly.

### US-5.4 — Full audit trail of every tool action
*As an Admin, I want a complete, tamper-evident record of every tool an agent has run on my
tenant's behalf — what ran, when, how long it took, and whether it succeeded — so that I can
review agent activity for security and compliance purposes.*

**Acceptance criteria**
- Every tool execution (success or failure) is recorded, including duration and an error
  message on failure.
- Audit records are isolated per tenant with the same database-level guarantee as all other
  tenant data (see US-1.3) — one tenant can never see another's agent activity.

---

## Epic 6 — Durable Agent Execution

### US-6.1 — Trigger an agent run without waiting for it to finish
*As a Developer, I want to kick off an agent run and get an immediate acknowledgment, rather
than my request hanging open until the model (and any tool it uses) finishes, so that
triggering an agent is fast and doesn't depend on how long the underlying work takes.*

**Acceptance criteria**
- Triggering an agent returns immediately with an id for that run and a status of "queued" —
  it does not wait for the LLM or any tool to complete.
- The run is picked up and processed automatically, without any further action from the
  caller.

### US-6.2 — Check on a run's status and result
*As a Developer, I want to check on a previously triggered agent run — whether it's still in
progress, finished successfully, or failed — and see its result once it's done, so that I can
build on top of agent runs asynchronously.*

**Acceptance criteria**
- A run's status is one of: queued, running, succeeded, or failed.
- Once succeeded, the agent's final answer is available; once failed, a clear error message
  is available.
- A run is visible only to the tenant that triggered it (same isolation guarantee as
  everything else — see US-1.3), including to an Admin or Developer who didn't personally
  trigger it, but not to any other tenant.
- Read-only users can check a run's status (consistent with "view history" access); only
  Admins and Developers can trigger new runs.

### US-6.3 — A crash doesn't silently lose a triggered run
*As an Admin, I want a triggered agent run to survive an application restart or crash, rather
than simply vanishing, so that "I asked for this to run" is a durable fact, not something that
depends on the server staying up the whole time.*

**Acceptance criteria**
- A triggered run is recorded durably (not held only in memory) before any work on it begins.
- If the application restarts, a run that was queued but not yet started is still queued
  afterward and gets picked up normally.

---

## Roadmap (not yet built)

The following are on the plan but intentionally out of scope for what's described above:

- **Multi-round agent reasoning** — the current tool-calling loop resolves one round of tool
  calls per request; chaining multiple dependent tool calls in a single agent turn (e.g.
  "clone, then list files, then summarize") is planned.
- **CLI client, GitHub Actions integration, and webhook triggers** for kicking off agent runs
  from outside the platform's own API.
- **The first purpose-built agent**: automated security patching — detect a vulnerability,
  have an agent produce and verify a fix, and open a pull request.
- **Filesystem tools** (read/write specific files within a cloned repository) beyond the
  current shell-command and git-clone tools.
- **Additional LLM providers** (OpenAI, Gemini) behind the existing provider-agnostic
  interface.
