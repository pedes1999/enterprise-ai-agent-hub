# Code Walkthrough (Developer Reference)

Personal reference doc: what each class does, and — most importantly — the exact method
call stack for the two most complex requests in the system, so a bug can be localized to a
layer fast instead of re-reading everything from scratch. Written for *you*, not third
parties; it assumes you already know the business goal and cares about mechanics.

Package prefix `com.enterprisehub.` is dropped everywhere below for brevity
(e.g. `gateway.agent.AgentPingController` means `com.enterprisehub.gateway.agent.AgentPingController`).

---

## 1. Module map (who is allowed to know what)

```
common-dto     <- request/response records only. No logic, no framework.
     ^
agent-core     <- LangChain4j lives here ONLY. Provider-agnostic LLM + tool-calling loop.
     ^
agent-runtime  <- Sandboxed tool implementations. Knows agent-core's AgentTool interface,
                  knows NOTHING about Spring, JPA, or HTTP controllers.
     ^
gateway-api    <- The actual Spring Boot app. Wires everything together: DB, security,
                  controllers, and the concrete SandboxClient/CredentialResolver/
                  ToolExecutionListener implementations that agent-runtime's interfaces need.
```

The dependency arrow only ever points "up" this list. `agent-core` cannot import Spring.
`agent-runtime` cannot import JPA. If you ever find yourself wanting to import something
downward, that's a sign the abstraction boundary is being violated — stop and reconsider.

---

## 2. Class-by-class reference

### Security / request entry (`gateway-api/.../security`, `.../config`, `.../tenant`)

| Class | Responsibility |
|---|---|
| `security.JwtService` | Issues JWTs on login/register (`issueToken`), validates them on every request (`parseAndValidate`). Returns `Optional.empty()` on any failure — never throws to callers. |
| `security.JwtAuthFilter` | Servlet filter. Reads the `Authorization: Bearer <jwt>` header, calls `JwtService.parseAndValidate`, and if valid, populates Spring Security's `SecurityContextHolder` with a `PlatformPrincipal`. No-ops silently on a missing/bad header (doesn't reject — that's Spring Security's job downstream). |
| `security.PlatformPrincipal` | `record(userId, tenantId, role)` — the authenticated identity for the rest of the request. |
| `security.TenantResolvingFilter` | Runs *after* `JwtAuthFilter`. Reads the `PlatformPrincipal` off the `SecurityContext` and calls `TenantContext.set(tenantId)`. Always clears it in a `finally` block (thread-pool reuse safety). |
| `tenant.TenantContext` | A `ThreadLocal<String>` holding the current request's tenant id. Set by `TenantResolvingFilter`, read by `TenantAwareDataSource`. |
| `tenant.TenantAwareDataSource` | Wraps the real `DataSource`. On **every JDBC connection checkout** (`getConnection()`), runs `SELECT set_config('app.current_tenant_id', ?, false)` with whatever `TenantContext.get()` currently holds (empty string if unset). This is what Postgres RLS policies actually key off. This is the enforcement point for every tenant-isolation guarantee in the system — if you're debugging a cross-tenant leak, start here, not in application code. |
| `config.SecurityConfig` | Wires the filter chain: `JwtAuthFilter` before `UsernamePasswordAuthenticationFilter`, `TenantResolvingFilter` after it. `/auth/**` and `/actuator/health` are public; everything else requires authentication by default. |
| `config.MethodSecurityConfig` | Enables `@PreAuthorize` method-level checks (role enforcement lives as annotations on controllers, e.g. `@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")`). |

**Order on every authenticated request:** `JwtAuthFilter` → `TenantResolvingFilter` →
Spring Security's `authorizeHttpRequests` check → `@PreAuthorize` on the controller method →
controller method body.

### LLM abstraction (`agent-core`)

| Class | Responsibility |
|---|---|
| `llm.LlmProvider` | Enum: `ANTHROPIC`, `OPENAI`, `GEMINI`. |
| `llm.LlmEngineFactory` | `create(provider, apiKey, modelName)` → LangChain4j `ChatLanguageModel`. Only `ANTHROPIC` is implemented; the other two branches throw `UnsupportedOperationException` deliberately (no half-built providers). |
| `SharedExecutionContext` | Bundles `tenantId`, `executionId`, a ready-to-call `ChatLanguageModel`, the list of `AgentTool`s, and an internal `ToolCallingChatEngine`. One instance per request; nothing about it is persisted. `chat(prompt)` is the single entry point callers use. |
| `SharedExecutionContextFactory` | `create(tenantId, executionId, provider, apiKey, modelName, tools)` → builds the `ChatLanguageModel` via `LlmEngineFactory` and wraps it in a new `SharedExecutionContext`. This is the only place `agent-core` and `gateway-api` meet for context assembly. |
| `tool.AgentTool` | The interface every tool (real or trivial) implements: `name()`, `description()`, `parameterDescriptions()`, `execute(ToolExecutionContext, Map<String,String> arguments)`. Deliberately our own interface, not LangChain4j's `@Tool` annotation — see its javadoc. |
| `tool.ToolExecutionContext` | `record(tenantId, executionId)` — passed explicitly into every `execute()` call instead of a `ThreadLocal`, because sandboxed execution goes over HTTP to a sidecar and may eventually be asynchronous; `ThreadLocal`s don't survive a thread hop. |
| `tool.ToolCallingChatEngine` | The actual tool-calling loop. See §3 below for the full call sequence. |

### Sandboxed tool execution (`agent-runtime`)

| Class | Responsibility |
|---|---|
| `sandbox.SandboxSpec` | `record(tenantId, executionId, credentials, maxLifetime, maxOutputBytes)` — validated in its compact constructor. What a tool asks for when it wants a sandbox. |
| `sandbox.SandboxHandle` | `record(id)` — an opaque reference to a live sandbox. |
| `sandbox.CommandResult` | `record(exitCode, stdout, stderr, outputTruncated, duration)` — result of running one command. |
| `sandbox.SandboxClient` | Interface: `create(spec)`, `runCommand(handle, command, timeout)`, `writeFile`, `readFile`, `destroy(handle)`. Vendor-neutral — knows nothing about E2B. |
| `sandbox.http.SandboxClientHttpImpl` | The only implementation. Talks HTTP (JDK's `java.net.http.HttpClient`) to the Node sidecar. Pure translation layer — no retry/timeout policy of its own beyond a connect timeout; the sidecar owns real enforcement. |
| `sandbox.SandboxRunner` | `withSandbox(spec, work)` — guarantees `create → work.apply(handle) → destroy`, even if `work` throws. Used by every sandboxed tool so a tool implementation can't forget cleanup. |
| `tools.AbstractSandboxedTool` | Base class every real tool extends. Its `execute()` is `final` — wraps a subclass's `doExecute()` with audit logging (success and failure paths, via `ToolExecutionListener`) so that logic lives in exactly one place. |
| `tools.RunShellCommandTool` | `doExecute()`: builds a `SandboxSpec` with no credentials, runs the given `command` via `withSandbox`, formats `CommandResult` into a string the LLM can read. |
| `tools.GitCloneTool` | `doExecute()`: validates the URL is `https://` and doesn't start with `-` (argument-injection defense), calls `CredentialResolver.resolve(tenantId, "GIT")`, builds a `git clone` command (with `git -c http.extraHeader=...` if a token was resolved), runs it the same way as `RunShellCommandTool`. |
| `credential.CredentialResolver` | Interface: `resolve(tenantId, credentialKind) -> Map<String,String>` (env-var-name → value). agent-runtime doesn't know how or where credentials are stored. |
| `audit.ToolExecutionListener` | Interface: `onToolExecuted(ToolExecutionAuditRecord)`. agent-runtime doesn't know how or where audit records are persisted. |

### gateway-api's concrete implementations of agent-runtime's SPIs

| Class | Responsibility |
|---|---|
| `credential.JpaCredentialResolver` | Implements `CredentialResolver`. Looks up the tenant's active `tool_credentials` row for the given kind via `ToolCredentialService.decryptActiveValue`, maps `"GIT" -> "GIT_TOKEN"` (see `envVarNameFor`). |
| `audit.JpaToolExecutionListener` | Implements `ToolExecutionListener`. Persists a `ToolExecution` entity via `ToolExecutionRepository.save`. Runs synchronously on whichever thread calls it — for `/agents/ping-with-tools` that's the request thread; for `POST /agents/execute` (async, see §8) it's `AgentJobWorker`'s poll thread, which is fine **only because** `AgentJobWorker` explicitly sets `TenantContext` to the job's real tenant before running anything — this listener itself has no idea which thread it's on. |
| `config.SandboxConfig` / `SandboxProperties` | Wires the `SandboxClientHttpImpl` bean from `app.sandbox.sidecar-url`. |

### Agent-facing controllers/services

| Class | Responsibility |
|---|---|
| `agent.AgentPingController` | `POST /agents/ping` and `POST /agents/ping-with-tools`. `@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")`. Pulls `tenantId` off the authenticated `PlatformPrincipal` — **never** trusts a tenant id in the request body. The **synchronous spike** path. |
| `agent.AgentPingService` | Thin orchestration behind both spike endpoints; delegates the actual tool-calling work to `AgentPromptRunner`. See §3 for the full call sequence. |
| `agent.AgentPromptRunner` | The real "run this prompt with tools for this tenant" logic (resolve credential, assemble tools, build `SharedExecutionContext`, call `chat()`) — extracted so both `AgentPingService` (sync) and `AgentJobWorker` (async) share one implementation instead of two that could drift apart. |
| `agent.tools.CurrentDateTimeTool` | Trivial, non-sandboxed `AgentTool` used to prove the tool-calling wiring works independent of the sandbox infrastructure. |
| `agent.AgentExecutionController` | `POST /agents/execute` (ADMIN/DEVELOPER) and `GET /agents/executions/{id}` (ADMIN/DEVELOPER/READONLY) — the **real, durable, async** path. See §8. |
| `agent.AgentExecutionService` | Every state transition of an `agent_executions` row: `enqueue()`, `claimNext()` (flips `QUEUED`→`RUNNING`), `complete()`/`fail()`, `findForTenant()`. Each is its own short `@Transactional` method — `claimNext()` in particular must commit fast so its row lock isn't held for the whole agent run that follows. |
| `agent.AgentJobWorker` | `@Scheduled` poll loop (`app.job-worker.poll-interval-ms`, default 2000ms). The only place `TenantContext.SYSTEM_WORKER_TENANT_ID` is ever set. See §8. Absent as a bean entirely (not just inert) when `app.job-worker.enabled=false` — set in `application-test.yml` so integration tests don't race it. |

---

## 3. Full call stack: `POST /agents/ping-with-tools`

This is the most complex flow in the system today — it touches every layer. Trace it top to
bottom when debugging anything tool-related.

```
HTTP POST /agents/ping-with-tools
Authorization: Bearer <jwt>
{ "prompt": "clone https://github.com/org/repo.git and tell me what's in it" }
```

**1. Servlet filter chain**
- `JwtAuthFilter.doFilterInternal()`
  → `extractToken(request)` — strips `Bearer ` prefix
  → `JwtService.parseAndValidate(token)` — verifies signature + expiry, extracts `userId`/`tenantId`/`role` claims
  → builds `PlatformPrincipal`, puts it on `SecurityContextHolder` with authority `ROLE_<role>`
- `TenantResolvingFilter.doFilterInternal()`
  → reads the `PlatformPrincipal` off `SecurityContext`
  → `TenantContext.set(principal.tenantId())` — this is now visible to any DB call on this thread for the rest of the request
  → (request proceeds; `TenantContext.clear()` runs in `finally` once the whole request finishes)
- Spring Security's `authorizeHttpRequests` — request is authenticated, passes
- `@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")` on `AgentPingController` — checked against the `ROLE_*` authority set in step 1

**2. Controller**
- `AgentPingController.pingWithTools(principal, request)`
  → `agentPingService.pingWithTools(UUID.fromString(principal.tenantId()), request.prompt())`
  — note: tenant id comes from the **validated JWT principal**, never from the request body

**3. Service orchestration — `AgentPingService.pingWithTools()`**
```java
validatePrompt(prompt)                     // rejects null/blank
String apiKey = resolveApiKey(tenantId)    // see 3a below
String executionId = UUID.randomUUID()...  // synthetic — no agent_executions row yet (spike)
List<AgentTool> tools = [CurrentDateTimeTool, RunShellCommandTool, GitCloneTool]
SharedExecutionContext context = sharedExecutionContextFactory.create(...)   // see 3b
ToolChatResult result = context.chat(prompt)                                  // see §4 (the loop)
return new AgentToolPingResponse(...)
```

**3a. `resolveApiKey(tenantId)`**
- `vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")`
  — this JPA call triggers a connection checkout → `TenantAwareDataSource.getConnection()` fires `set_config('app.current_tenant_id', tenantId, false)` first → RLS now scopes every query on this connection to this tenant
- `.filter(VendorCredential::isActive)` — inactive/rotated-out credentials are invisible
- if absent → throws `AgentException(400, "No active ANTHROPIC credential...")`
- `vendorCredentialService.decryptToken(credential)` → `LocalAesGcmCredentialEncryptor.decrypt()` — AES-256-GCM, keyed by `CREDENTIAL_LOCAL_KEY`

**3b. `sharedExecutionContextFactory.create(...)`**
- `LlmEngineFactory.create(ANTHROPIC, apiKey, modelName)` → `AnthropicChatModel.builder()...build()` — a real, ready-to-call LangChain4j client
- `new SharedExecutionContext(tenantId, executionId, chatModel, tools)`
  → internally builds `new ToolCallingChatEngine(chatModel, tools, new ToolExecutionContext(tenantId, executionId))` — this is where the explicit context object gets created and frozen for the whole request

---

## 4. The tool-calling loop itself — `ToolCallingChatEngine.chat(prompt)`

This is the part worth understanding cold, since it's where "why didn't the model use my
tool" or "why did the tool get the wrong arguments" bugs live.

```
chat(userMessage)
 │
 ├─ messages = [UserMessage(userMessage)]
 │
 ├─ response = chatModel.generate(messages, toolSpecifications)     // <- REAL Anthropic API call #1
 │       toolSpecifications was built once in the constructor:
 │       toSpecification(tool) for each AgentTool -> ToolSpecification
 │       (name, description, and EVERY parameter typed as a plain string —
 │        see AgentTool's javadoc for why: no nested/typed params yet)
 │
 ├─ aiMessage = response.content()
 │
 ├─ if !aiMessage.hasToolExecutionRequests():
 │       return ToolChatResult(aiMessage.text(), toolWasUsed=false)   // model answered directly, no tool needed
 │
 ├─ (model wants to call one or more tools)
 ├─ messages.add(aiMessage)
 ├─ for each ToolExecutionRequest in aiMessage.toolExecutionRequests():
 │       result = executeTool(request)          // see below
 │       messages.add(ToolExecutionResultMessage.from(request, result))
 │
 ├─ finalResponse = chatModel.generate(messages, toolSpecifications)  // <- REAL Anthropic API call #2, now WITH tool results in context
 │
 └─ return ToolChatResult(finalResponse.content().text(), toolWasUsed=true)
```

`executeTool(request)`:
```
tool = toolsByName.get(request.name())      // null if the model hallucinated a tool name
if null: return "Error: no tool registered with name '...'"
try:
    arguments = parseArguments(request.arguments())   // JSON string -> Map<String,String>
    return tool.execute(executionContext, arguments)  // <- THIS is where GitCloneTool/RunShellCommandTool run
catch Exception e:
    return "Error executing tool '...': " + e.getMessage()   // NEVER thrown up — fed back to the model as text
```

**Important debugging fact:** a tool that throws does *not* fail the HTTP request. The
exception is caught right here and turned into an error string that becomes part of the next
LLM message. If a tool is silently "not working," check the final model reply text for an
`"Error executing tool..."` string before assuming the tool itself is broken — the tool may
have thrown exactly as designed and the model just didn't surface that clearly in its final
answer.

**Known limitation (by design, not yet a bug to fix):** this loop only executes **one round**
of tool calls. If the model's first response asks for tool A, and it would need tool B's
result to decide on a next step, that second round never happens — the loop always ends after
one "call tools, then answer" cycle. A prompt like *"clone the repo, then list its files"*
will typically only execute the clone.

---

## 5. Inside a tool: `GitCloneTool.doExecute()` full stack

Picking up from `tool.execute(executionContext, arguments)` above, when the tool is
`GitCloneTool`:

```
AbstractSandboxedTool.execute(context, arguments)      // final, not overridable
 │  start = Instant.now()
 │  try:
 │      result = doExecute(context, arguments)          // GitCloneTool's real logic, below
 │      listener.onToolExecuted(AuditRecord(..., SUCCESS, null))   // -> JpaToolExecutionListener.onToolExecuted()
 │                                                                  //    -> repository.save(ToolExecution)
 │                                                                  //    -> RLS-scoped INSERT (TenantAwareDataSource again)
 │      return result
 │  catch RuntimeException e:
 │      listener.onToolExecuted(AuditRecord(..., FAILURE, e.getMessage()))
 │      throw e     // <- caught one level up, in ToolCallingChatEngine.executeTool()
 │
 └─ GitCloneTool.doExecute(context, arguments)
     │  repositoryUrl = arguments.get("repositoryUrl")
     │  validateRepositoryUrl(repositoryUrl)             // must start with https://, must not start with '-'
     │
     │  credentials = credentialResolver.resolve(context.tenantId(), "GIT")
     │       -> JpaCredentialResolver.resolve()
     │          -> ToolCredentialService.decryptActiveValue(tenantId, "GIT")
     │             -> toolCredentialRepository.findByTenantIdAndCredentialKindAndIsActiveTrue(...)  // RLS-scoped
     │             -> LocalAesGcmCredentialEncryptor.decrypt()  (same encryptor as vendor credentials)
     │          -> Map.of("GIT_TOKEN", <decrypted token>)   (or Map.of() if none configured)
     │
     │  command = buildCloneCommand(repositoryUrl, hasCredential)
     │       "mkdir -p '/tmp/workspace' && git clone <quoted-url> /tmp/workspace/repo"
     │       or, with a credential:
     │       "mkdir -p '/tmp/workspace' && git -c http.extraHeader=\"AUTHORIZATION: basic $(printf '%s' \"x-access-token:$GIT_TOKEN\" | base64 -w0)\" clone <quoted-url> /tmp/workspace/repo"
     │
     │  spec = SandboxSpec(tenantId, executionId, credentials, maxLifetime=3min, maxOutputBytes=64KB)
     │
     │  result = withSandbox(spec, handle -> sandboxClient.runCommand(handle, command, timeout=60s))
     │       │
     │       └─ SandboxRunner.withSandbox(spec, work)
     │            handle = sandboxClient.create(spec)
     │                 -> SandboxClientHttpImpl.create()
     │                    -> POST http://<sidecar>/sandboxes  {tenantId, executionId, credentials, maxLifetimeSeconds, maxOutputBytes}
     │                    -> sidecar: Sandbox.create({apiKey: E2B_API_KEY, envVars: credentials, timeoutMs, metadata})
     │                       -> REAL E2B API CALL -- a real Firecracker microVM boots here
     │                    <- {sandboxId}
     │            try:
     │                result = work.apply(handle)   // = sandboxClient.runCommand(handle, command, 60s)
     │                    -> SandboxClientHttpImpl.runCommand()
     │                       -> POST http://<sidecar>/sandboxes/{id}/commands  {command, timeoutSeconds}
     │                       -> sidecar: entry.sandbox.commands.run(command, {timeoutMs})
     │                          -> REAL E2B API CALL -- command runs inside the microVM
     │                          -> NOTE: E2B's SDK THROWS on non-zero exit, not returns --
     │                             sidecar catches this, checks err.exitCode, and still
     │                             responds 200 with the real exitCode/stdout/stderr
     │                             (this was bug #1 found via live testing -- see README)
     │                       <- {exitCode, stdout, stderr, truncated, durationMs}
     │            finally:
     │                sandboxClient.destroy(handle)
     │                    -> SandboxClientHttpImpl.destroy()
     │                       -> DELETE http://<sidecar>/sandboxes/{id}
     │                       -> sidecar: entry.sandbox.kill()  (idempotent, never throws to the caller)
     │
     └─ formatResult(result) -> "exit_code: 0\nRepository cloned to /tmp/workspace/repo.\nstdout:\n..."
```

The formatted string above is exactly what gets fed back into `ToolCallingChatEngine`'s
second `chatModel.generate()` call as the tool's result.

---

## 6. The real (async, durable) path: `POST /agents/execute` → `AgentJobWorker`

Everything in §3-§5 above is the **synchronous spike** (`/agents/ping-with-tools`) — it blocks
the HTTP thread for the whole run and persists nothing if the app crashes mid-request. This is
the actual job-queue path (Weeks 9-10), and it reuses `AgentPromptRunner` (§3's step 3b/4/5 —
tool assembly, `SharedExecutionContext`, the tool-calling loop) for the real work; what's
different here is everything *around* that call.

**6a. The enqueue request returns immediately, before any LLM call happens**

```
POST /agents/execute  { "prompt": "..." }
 -> JwtAuthFilter / TenantResolvingFilter (same as any request, see §3 step 1)
 -> AgentExecutionController.execute(principal, request)
      validates prompt is non-blank
      -> AgentExecutionService.enqueue(tenantId, prompt)
           new AgentExecution(tenantId, agentType="PROMPT_WITH_TOOLS", triggerSource="API",
                               llmProvider="ANTHROPIC", prompt, status="QUEUED")
           -> repository.save(...)   // RLS-scoped INSERT, ordinary tenant context, nothing special here
      <- 202 Accepted  { executionId, status: "QUEUED" }
```

No Anthropic call has happened yet. The caller now polls `GET /agents/executions/{id}`
(RLS + `findByIdAndTenantId` — same tenant-scoping pattern as everything else) until `status`
is `SUCCEEDED` or `FAILED`.

**6b. `AgentJobWorker.pollAndProcessOne()` — runs on a `@Scheduled` thread, NOT a request thread**

This is the one piece of the whole codebase that deliberately breaks the "tenant context comes
from an authenticated request" assumption everything else relies on. Read this closely if
you're debugging anything queue-related.

```
pollAndProcessOne()                                    // fires every app.job-worker.poll-interval-ms (default 2000ms)
 │
 ├─ claimNext()
 │    TenantContext.set(TenantContext.SYSTEM_WORKER_TENANT_ID)   // "__agent_job_worker__" -- see its javadoc
 │    try:
 │        AgentExecutionService.claimNext()
 │          -> repository.claimNextQueued()
 │               SELECT * FROM agent_executions WHERE status='QUEUED'
 │               ORDER BY created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED
 │               -- RLS's tenant_isolation_agent_executions policy has an OR clause recognizing
 │               -- this exact sentinel string (V5__agent_execution_queue.sql) -- THIS is the only
 │               -- reason this query can see rows across every tenant. Under any other
 │               -- TenantContext value (a real tenant, or unset/empty), it sees nothing.
 │               -- FOR UPDATE SKIP LOCKED means concurrent workers never double-claim: each one
 │               -- just skips whatever row another worker's open transaction already has locked.
 │             sets status="RUNNING", startedAt=now()   // same short transaction -- commits fast,
 │             return execution                          // releasing the row lock before the slow part starts
 │    finally: TenantContext.clear()
 │
 │  if no job claimed: return (nothing to do this tick)
 │
 └─ runClaimedJob(job)
      TenantContext.set(job.getTenantId().toString())   // <-- switches OFF the sentinel, onto the
      try:                                                //     job's REAL tenant, before anything else runs
          result = AgentPromptRunner.run(job.tenantId, job.id.toString(), job.prompt)
               -- IDENTICAL to §3 step 3b/§4's tool-calling loop -- resolves this tenant's
               -- Anthropic credential (RLS-scoped, now correctly, since TenantContext is the
               -- real tenant), builds the same 3-tool list, runs chat() to completion,
               -- including any sandboxed tool execution and its own audit logging.
          AgentExecutionService.complete(job.id, result.reply(), result.toolWasUsed())
               -- sets status="SUCCEEDED", reply, toolWasUsed, completedAt -- RLS-scoped UPDATE,
               -- correctly scoped because TenantContext is still the real tenant here.
      catch RuntimeException e:
          AgentExecutionService.fail(job.id, e.getMessage())
               -- status="FAILED", errorMessage, completedAt. Never rethrown -- a failed agent
               -- run must not crash the poll loop; the NEXT tick just claims the next job.
      finally: TenantContext.clear()
```

**Why the sentinel switch matters (the bug this design prevents):** if `AgentJobWorker` ran
`AgentPromptRunner`/`AgentExecutionService.complete()` while `TenantContext` was still set to
the sentinel (or never switched it at all), every credential lookup, tool execution, audit
insert, and the final status UPDATE would be running with the wrong (or no) tenant scope —
either failing outright against `FORCE ROW LEVEL SECURITY`, or worse, silently touching the
wrong tenant's data if the sentinel accidentally matched something. The `runClaimedJob` switch
is not a style choice; it's the actual security boundary between "the worker claiming across
tenants" and "the worker acting as one specific tenant."

**Only one job runs at a time per app instance:** `@Scheduled(fixedDelay=...)` (not
`fixedRate`) means Spring won't start the next poll tick until the current one — including the
full agent run inside it — has returned. Multiple jobs in parallel would mean either running
multiple app instances (each with its own poller, safely coordinated by `FOR UPDATE SKIP
LOCKED`) or increasing the scheduler's thread pool, neither of which has been done yet.

---

## 7. Where to look, by symptom

| Symptom | Start here |
|---|---|
| "User from Tenant A sees Tenant B's data" | `TenantAwareDataSource` (is `set_config` actually running?), then check the specific migration for `FORCE ROW LEVEL SECURITY` — `ENABLE` alone is not enough, superuser/table-owner connections bypass RLS silently otherwise. |
| "401 on every request" / "role check fails unexpectedly" | `JwtAuthFilter` → `JwtService.parseAndValidate` (expired? wrong `JWT_SECRET` between issuing and validating environments?) → check the `ROLE_<role>` authority actually matches what `@PreAuthorize` expects (case-sensitive). |
| "Model never uses the tool I expect" | Check `toolSpecifications` actually includes it (was it added to the `tools` list in `AgentPingService.pingWithTools`?) — then check the tool's `description()`/`parameterDescriptions()` are clear enough for the model to choose it. Not a code bug most of the time — a prompt-engineering one. |
| "Tool result looks like an error string in the final answer" | That's `ToolCallingChatEngine.executeTool()`'s catch block — the tool threw. Go to the specific tool's `doExecute()` and work backward; the real exception message is in that string. |
| "Sandbox call fails / times out" | `SandboxClientHttpImpl` → is `app.sandbox.sidecar-url` correct and is the sidecar actually running? Then sidecar logs (`agent-runtime/sidecar/server.js` console output) for the real E2B-side error — HTTP 502 from the sidecar means the *sidecar or E2B* failed, not the command itself (a non-zero exit code from the command is a normal 200, see §5). |
| "Credential resolves to nothing / clone runs unauthenticated" | `JpaCredentialResolver.resolve()` → `ToolCredentialService.decryptActiveValue` → is there actually an **active** `tool_credentials` row for this tenant + `GIT` kind? (`PUT /tool-credentials` sets it; a soft-deleted/inactive row won't be found.) |
| "Audit row missing for a tool call" | `AbstractSandboxedTool.execute()` should have called the listener on both success and failure paths — if truly missing, check whether the tool bypassed `AbstractSandboxedTool` entirely (only sandboxed tools get audited automatically; a hypothetical non-sandboxed real tool would need its own audit call). |
| "New tenant's first request looks unauthenticated" | Registration flow: `AuthController.register` → `AuthService.register` — confirm a JWT is actually returned in `AuthResponse` and the client is sending it as `Authorization: Bearer <token>` on the next call, not treating registration as also logging in via a session/cookie (there are none — the platform is fully stateless). |
| "Execution stays QUEUED forever, never picked up" | Is `AgentJobWorker` even running? Check `app.job-worker.enabled` (it's a `@ConditionalOnProperty` — if false, there's no bean at all, not just an idle one) and confirm `@EnableScheduling` is still on `GatewayApplication`. If the bean exists, check for an exception in the previous poll tick's logs — an uncaught exception in `pollAndProcessOne()` itself (as opposed to inside the try/catch around `AgentPromptRunner.run`) would silently kill future scheduled invocations of that method. |
| "Execution jumps straight to FAILED with a credential error" | That's `AgentPromptRunner.resolveApiKey()` throwing inside `AgentJobWorker.runClaimedJob()` — check `errorMessage` on the row (`GET /agents/executions/{id}`); "No active ANTHROPIC credential..." means exactly what it says, `PUT /vendor-credentials` for that tenant first. This is expected, correct behavior, not a bug — see the live-verification note in the README. |
| "Two workers claimed the same job" / "a job got processed twice" | Should be structurally impossible — `claimNextQueued()`'s `FOR UPDATE SKIP LOCKED` guarantees only one transaction can hold a given row. If you see this, check whether `AgentExecutionService.claimNext()` is being called **outside** a real `@Transactional` context (e.g. a test double or a refactor that lost the annotation) — without an active transaction, the `FOR UPDATE` lock isn't actually held. |
| "Worker query sees nothing even though rows are QUEUED" | Check `TenantContext.get()` at the moment `claimNextQueued()` runs — it must be exactly `TenantContext.SYSTEM_WORKER_TENANT_ID`. If `AgentJobWorker.claimNext()`'s `TenantContext.set(...)` call was ever removed, refactored away, or reordered after the repository call, the RLS policy's sentinel OR-clause won't match and the query returns nothing for every tenant, silently. |

---

## 8. Quick reference: the two Anthropic API calls per `ping-with-tools` request

Easy to forget when reading logs/costs: a single `/agents/ping-with-tools` call that ends up
using a tool makes **two** real calls to Anthropic, not one — one to decide whether/which
tool to call, one to produce the final answer after seeing the tool's result. A request that
doesn't need a tool makes only the first.
