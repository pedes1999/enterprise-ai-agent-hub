# Enterprise AI Agent Hub — User Stories

**Document purpose:** a functional overview of the platform as built to date, expressed as
user stories with acceptance criteria. Intended for stakeholders, prospective customers, and
partners evaluating the product — it describes *what the system does and for whom*, not how
it is implemented.

**Status:** covers everything shipped through Weeks 1–10 of the build plan, plus the
multi-step single-agent execution depth, the agent/tool catalog, and pull-request delivery
built on top (multi-tenant foundation, auth & roles, encrypted credential management, LLM
integration, multi-round sandboxed agent tool execution with a persistent per-task workspace,
durable async job execution, a browsable library of named agents each with their own
capabilities, and a verified-work-only path to a real pull request). A single agent can now go
from "here's a task" to "here's a tested, opened pull request" end to end. This is a
deliberate step toward the platform's actual end goal — a multi-agent "Ticket → PR" pipeline —
which, along with CLI/webhook triggers and the first fully autonomous agent, is on the roadmap
but not yet built (see the last section).

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

### US-5.1 — Let the model use tools, across several steps, to answer a request
*As a Developer, I want an agent to be able to decide, on its own, whether a task needs one or
more tools (running a command, cloning a repository, reading or writing a file, checking the
date/time) and to use them automatically across as many steps as the task genuinely needs, so
that I don't have to manually orchestrate a multi-step task myself.*

**Acceptance criteria**
- The agent is given a set of tools it's allowed to use and decides for itself whether any are
  needed to answer the prompt, and whether it needs to use more than one in sequence (e.g.
  clone a repository, then read a file from it, then act on what it read).
- A task requiring several sequential tool uses is capped at a bounded number of steps, so a
  request can never loop indefinitely — if that cap is reached, the agent is asked to give its
  best answer with what it has rather than continuing forever.
- The final response indicates whether a tool was actually used, for transparency.

### US-5.2 — Run arbitrary shell commands safely
*As a Developer, I want an agent to be able to run a shell command (list files, run a build,
inspect output) without that command ever touching my own infrastructure, so that a bad or
even malicious command can't damage anything real.*

**Acceptance criteria**
- Every command runs inside an isolated, disposable virtual machine.
- Within one triggered task, an agent's own earlier actions in that same task (a repository it
  cloned, a file it wrote) are visible to its later steps — a multi-step task like "clone,
  then inspect, then edit" genuinely works. Between two different triggered tasks, there is no
  shared state whatsoever: each starts from a completely clean slate, and the disposable
  environment is destroyed once the whole task finishes.
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
- Cloning happens inside the same disposable environment as every other action in that task —
  a repository's contents, however untrusted, never touch platform infrastructure directly.

### US-5.4 — Read and write files in a cloned repository
*As a Developer, I want an agent to be able to read a specific file's contents and to create
or modify a file, so that it can actually inspect and change code, not just run commands
against it.*

**Acceptance criteria**
- A file can be read by a path relative to the repository, and its contents are returned to
  the agent to reason about.
- A file can be written (created, or overwritten if it already exists), with any necessary
  containing folders created automatically.
- An agent cannot read or write anything outside the task's own working area, regardless of
  what path it's given.
- An oversized file (read or written) is rejected or trimmed rather than silently accepted
  without limit.

### US-5.5 — Full audit trail of every tool action
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
- Which agent to run can be specified explicitly (see Epic 7); if omitted, a sensible
  general-purpose default is used.

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

## Epic 7 — A Library of Agents and Tools

### US-7.1 — Different agents for different jobs, from one shared catalog
*As a Developer, I want to choose which agent handles my task — a general-purpose one, or a
specialized one with access to repository tools — from a catalog the platform maintains, so
that I'm not stuck with one fixed set of capabilities for every request.*

**Acceptance criteria**
- Every tenant selects from the same shared catalog of agents (a platform-maintained library,
  not something each tenant builds themselves).
- Each agent in the catalog has its own persona/instructions and its own specific set of
  capabilities (tools) — one agent might only be able to answer questions, another might be
  able to clone a repository and edit files.
- An agent only ever has access to the capabilities explicitly granted to it — asking a
  general-purpose agent to do something outside its granted tools results in it correctly
  reporting that it can't, not a silent failure or a capability it was never meant to have.
- Requesting an agent that doesn't exist (or is no longer available) is rejected clearly,
  before any work begins.

### US-7.2 — Discover what agents are available
*As a Developer, I want to see the full list of agents I can choose from — what each one does
and what it's capable of — so that I can pick the right one for my task without reading
source code or guessing.*

**Acceptance criteria**
- A caller can retrieve the current catalog: every available agent's name, description, and
  the capabilities (tools) it has access to.
- Read-only users can browse the catalog (consistent with other "view" access), the same as
  Admins and Developers.

---

## Epic 8 — Delivering a Pull Request

### US-8.1 — An agent's finished work becomes a real pull request
*As a Developer, I want an agent that's finished a change to commit it, push it, and open an
actual pull request, so that the output of an agent run is something a human can review and
merge, not just a description of a change that happened inside a disposable sandbox.*

**Acceptance criteria**
- Given a repository credential is configured, an agent can commit the current state of its
  work, push it to a new branch, and open a pull request against a chosen base branch.
- The pull request has a real, working title and description reflecting what changed.
- Opening the pull request is the final step of a task, not an intermediate one — the change
  isn't "in progress" from the platform's point of view once this succeeds.

### US-8.2 — A pull request is never opened on unverified work
*As an Admin, I want a guarantee that an agent never opens a pull request for a change that
hasn't actually been tested, so that "there's an open PR" reliably means "this was verified,"
not "the agent said it was probably fine."*

**Acceptance criteria**
- Opening a pull request always requires a verification step (e.g. a test suite, a build, a
  lint check) to be specified.
- That verification step is independently re-run, for real, immediately before anything is
  committed or pushed — the agent's own claim that it already verified the work is never
  trusted on its own.
- If verification fails, nothing is committed, nothing is pushed, and no pull request is
  opened — the failure (and why) is reported back instead.

---

## Roadmap (not yet built)

The following are on the plan but intentionally out of scope for what's described above. The
long-term product direction is a **multi-agent "Ticket → PR" pipeline** — a sequence of agent
runs (e.g. a planning step, a coding step, a review step) that together take a task description
and produce a real, working pull request. Everything below the pipeline item itself is being
built as a deliberate prerequisite toward that, not as a detour from it — a reliable multi-step
single agent, with a real catalog behind it, that can deliver a verified pull request as its
final step (Epics 5, 7, and 8) is what a multi-agent pipeline is actually built out of.

- **Self-service agent authoring** — today the catalog (Epic 7) is maintained by the platform
  team, not editable by tenants themselves; a management interface for creating/editing agents
  without a deployment is a natural later addition.
- **CLI client, GitHub Actions integration, and webhook triggers** for kicking off agent runs
  from outside the platform's own API.
- **The first purpose-built agent**: automated security patching — detect a vulnerability,
  have an agent produce and verify a fix, and open a pull request (Epic 8 provides the
  "verify and open a PR" half of this; detecting the vulnerability in the first place is not
  built).
- **Additional LLM providers** (OpenAI, Gemini) behind the existing provider-agnostic
  interface.
- **The multi-agent "Ticket → PR" pipeline itself**: chaining several agent runs together with
  different roles/responsibilities and passing structured output from one stage into the next.
