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
| `tool.ToolCallingChatEngine` | The actual tool-calling loop -- bounded multi-round now (`MAX_TOOL_ROUNDS = 6`), not single-shot. See §3 below for the full call sequence. |

### Sandboxed tool execution (`agent-runtime`)

| Class | Responsibility |
|---|---|
| `sandbox.SandboxSpec` | `record(tenantId, executionId, credentials, maxLifetime, maxOutputBytes)` — validated in its compact constructor. What a tool asks for when it wants a sandbox. |
| `sandbox.SandboxHandle` | `record(id)` — an opaque reference to a live sandbox. |
| `sandbox.CommandResult` | `record(exitCode, stdout, stderr, outputTruncated, duration)` — result of running one command. |
| `sandbox.SandboxClient` | Interface: `create(spec)`, `runCommand(handle, command, timeout)`, `writeFile`, `readFile`, `destroy(handle)`. Vendor-neutral — knows nothing about E2B. |
| `sandbox.http.SandboxClientHttpImpl` | The only implementation. Talks HTTP (JDK's `java.net.http.HttpClient`) to the Node sidecar. Pure translation layer — no retry/timeout policy of its own beyond a connect timeout; the sidecar owns real enforcement. |
| `sandbox.SandboxRunner` | `withSandbox(spec, work)` — guarantees `create → work.apply(handle) → destroy`, even if `work` throws. Used by every sandboxed tool so a tool implementation can't forget cleanup. |
| `sandbox.SandboxSession` | Decorator around a `SandboxClient` that makes ONE real sandbox last for a whole execution instead of one tool call. Every tool still calls `create()`/`destroy()` on what it thinks is its own `SandboxClient` (via `SandboxRunner`, unmodified) — the session intercepts those: first `create()` from any tool actually provisions one (using the session's own pre-built spec, not the caller's), every later `create()` returns the cached handle, `destroy()` no-ops. `endSession()` is the real teardown, called once by whoever owns the session (`AgentPromptRunner`). See §6/§8 below. |
| `tools.Workspace` | Package-private constant: `ROOT = "/tmp/workspace/repo"` — the directory every sandboxed tool now agrees on, so `git_clone`, `run_shell_command`, `read_file`, and `write_file` can all see each other's work within one session. |
| `tools.WorkspacePath` | Resolves an LLM-supplied relative path against `Workspace.ROOT`, rejecting absolute paths and `..` traversal — the same "never trust tool arguments" posture as `GitCloneTool`'s URL validation. Used by `ReadFileTool`/`WriteFileTool`. |
| `tools.AbstractSandboxedTool` | Base class every real tool extends. Its `execute()` is `final` — wraps a subclass's `doExecute()` with audit logging (success and failure paths, via `ToolExecutionListener`) so that logic lives in exactly one place. |
| `tools.RunShellCommandTool` | `doExecute()`: builds a `SandboxSpec` with no credentials, prefixes the command with `mkdir -p <workspace> && cd <workspace> &&` so it runs from the shared workspace, runs it via `withSandbox`, formats `CommandResult` into a string the LLM can read. |
| `tools.GitCloneTool` | `doExecute()`: validates the URL is `https://` and doesn't start with `-` (argument-injection defense), calls `CredentialResolver.resolve(tenantId, "GIT")` (to decide whether to add an auth header — the actual env var, if any, was already injected when the session's sandbox was created, see `AgentPromptRunner`), builds a `git clone` command (with `git -c http.extraHeader=...` if a token was resolved), runs it the same way as `RunShellCommandTool`, cloning into `Workspace.ROOT`. |
| `tools.ReadFileTool` | `doExecute()`: resolves `path` via `WorkspacePath`, reads it via `SandboxClient.readFile`, truncates client-side at 64KB. |
| `tools.WriteFileTool` | `doExecute()`: resolves `path`, validates `content` isn't over 256KB, runs a `mkdir -p` for the parent directory first (same lesson as `GitCloneTool`'s original bug — never assume a directory exists), then `SandboxClient.writeFile`. |
| `credential.CredentialResolver` | Interface: `resolve(tenantId, credentialKind) -> Map<String,String>` (env-var-name → value). agent-runtime doesn't know how or where credentials are stored. |
| `audit.ToolExecutionListener` | Interface: `onToolExecuted(ToolExecutionAuditRecord)`. agent-runtime doesn't know how or where audit records are persisted. |

### gateway-api's concrete implementations of agent-runtime's SPIs

| Class | Responsibility |
|---|---|
| `credential.JpaCredentialResolver` | Implements `CredentialResolver`. Looks up the tenant's active `tool_credentials` row for the given kind via `ToolCredentialService.decryptActiveValue`, maps `"GIT" -> "GIT_TOKEN"` (see `envVarNameFor`). |
| `audit.JpaToolExecutionListener` | Implements `ToolExecutionListener`. Persists a `ToolExecution` entity via `ToolExecutionRepository.save`. Runs synchronously on whichever thread calls it — for `/agents/ping-with-tools` that's the request thread; for `POST /agents/execute` (async, see §8) it's `AgentJobWorker`'s poll thread, which is fine **only because** `AgentJobWorker` explicitly sets `TenantContext` to the job's real tenant before running anything — this listener itself has no idea which thread it's on. |
| `config.SandboxConfig` / `SandboxProperties` | Wires the `SandboxClientHttpImpl` bean from `app.sandbox.sidecar-url`. |

### Agent catalog (gateway-api) -- what "a library of hundreds of agents and tools" is actually built on

| Class | Responsibility |
|---|---|
| `entity.AgentDefinition` / `repository.AgentDefinitionRepository` | A platform-wide (no `tenant_id`, no RLS -- every tenant picks from the same catalog), named agent: `slug`, `name`, `description`, `systemPrompt`, `toolNames` (a Postgres `text[]`, mapped via Hibernate's `@JdbcTypeCode(SqlTypes.ARRAY)`). Added via migration (`V6__agent_definitions.sql`), no admin CRUD API yet. `findBySlugAndActiveTrue` is the one lookup that matters at runtime. |
| `agent.catalog.ToolFactory` | One `@Component` bean per `AgentTool` (`toolName()`, `category()`, `create(session, listener, credentialResolver)`). The seam that lets the tool side of the catalog self-assemble -- adding tool #101 means one new `AgentTool` + one new `ToolFactory`, nothing else changes. Five real implementations today: `CurrentDateTimeToolFactory`, `RunShellCommandToolFactory`, `GitCloneToolFactory`, `ReadFileToolFactory`, `WriteFileToolFactory` -- each a few lines wrapping one tool's constructor. |
| `agent.catalog.ToolCatalog` | Collects every `ToolFactory` Spring finds (`List<ToolFactory>` constructor injection -- no hardcoded names anywhere in this class) into a `Map<toolName, factory>`. `instantiate(toolNames, session, listener, credentialResolver)` builds the live `AgentTool` list for one `AgentDefinition`, all wired to the SAME `SandboxSession`. Throws `AgentException(500)` if a definition references a tool name with no matching factory -- a data-integrity problem between the DB row and the deployed code, not a caller error. |
| `agent.AgentDefinitionService` | Thin read-only wrapper (`listActive()`) behind `GET /agents/definitions` -- maps `AgentDefinition` entities to the public `AgentDefinitionSummary` DTO (never exposes the entity or its `systemPrompt` directly... actually it does expose name/description/toolNames but keeps the entity itself out of the HTTP layer). |

### Agent-facing controllers/services

| Class | Responsibility |
|---|---|
| `agent.AgentPingController` | `POST /agents/ping` and `POST /agents/ping-with-tools`. `@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")`. Pulls `tenantId` off the authenticated `PlatformPrincipal` — **never** trusts a tenant id in the request body. The **synchronous spike** path. |
| `agent.AgentPingService` | Thin orchestration behind both spike endpoints; delegates the actual tool-calling work to `AgentPromptRunner`. `pingWithTools` defaults a missing/blank `agentSlug` to `AgentPromptRunner.DEFAULT_AGENT_SLUG` ("general-assistant") and re-throws an `AgentException` from the runner AS-IS (not relabeled as a 502) -- see §3. |
| `agent.AgentPromptRunner` | The real "run this NAMED agent's prompt, with its own tools, for this tenant" logic — extracted so both `AgentPingService` (sync) and `AgentJobWorker` (async) share one implementation instead of two that could drift apart. `run(tenantId, executionId, agentSlug, prompt)`: resolve the `AgentDefinition` by slug (400 if unknown/inactive), resolve the LLM credential, resolve only the credential kinds a tool actually IN that definition's `toolNames` might need (`buildSessionSpec` — today just `GIT`, gated on `"git_clone"` being present) and build one `SandboxSession` from it, build that definition's tools via `ToolCatalog.instantiate`, build `SharedExecutionContext` with the definition's `systemPrompt`, call `chat()`, and `session.endSession()` in a `finally` block regardless of outcome. See §3/§6. |
| `agent.tools.CurrentDateTimeTool` | Trivial, non-sandboxed `AgentTool` used to prove the tool-calling wiring works independent of the sandbox infrastructure. |
| `agent.AgentExecutionController` | `POST /agents/execute` (ADMIN/DEVELOPER), `GET /agents/executions/{id}` (ADMIN/DEVELOPER/READONLY) — the **real, durable, async** path, see §8 — and `GET /agents/definitions` (ADMIN/DEVELOPER/READONLY), the catalog listing. |
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
  → `agentPingService.pingWithTools(UUID.fromString(principal.tenantId()), request.prompt(), request.agentSlug())`
  — note: tenant id comes from the **validated JWT principal**, never from the request body; `agentSlug` is caller-supplied and validated downstream (unknown slug -> 400, see 3d)

**3. Service orchestration — `AgentPingService.pingWithTools()` → `AgentPromptRunner.run()`**
```java
// AgentPingService.pingWithTools() -- thin wrapper
validatePrompt(prompt)                     // rejects null/blank
resolvedSlug = agentSlug ?? "general-assistant"     // AgentPromptRunner.DEFAULT_AGENT_SLUG
String executionId = UUID.randomUUID()...  // synthetic -- no agent_executions row for this spike endpoint
try {
    ToolChatResult result = agentPromptRunner.run(tenantId, executionId, resolvedSlug, prompt)   // everything below
} catch (AgentException e) {
    throw e;   // preserve its real status (e.g. 400 unknown agent) -- don't relabel as 502
} catch (RuntimeException e) {
    throw new AgentException(502, "Anthropic API call failed: " + e.getMessage());
}

// AgentPromptRunner.run() -- the actual work
AgentDefinition definition = resolveAgentDefinition(agentSlug)   // see 3d -- FIRST, before touching credentials at all
String apiKey = resolveApiKey(tenantId)             // see 3a below -- same as before
SandboxSession session = new SandboxSession(sandboxClient, buildSessionSpec(tenantId, executionId, definition))  // see 3c
try {
    List<AgentTool> tools = toolCatalog.instantiate(definition.getToolNames(), session, listener, credentialResolver)
    SharedExecutionContext context = sharedExecutionContextFactory.create(..., tools, definition.getSystemPrompt())   // see 3b
    return context.chat(prompt)                                                  // see §4 (the multi-round loop)
} finally {
    session.endSession()   // real sandbox teardown, exactly once, regardless of how many tool calls happened
}
```

**3a. `resolveApiKey(tenantId)`**
- `vendorCredentialRepository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")`
  — this JPA call triggers a connection checkout → `TenantAwareDataSource.getConnection()` fires `set_config('app.current_tenant_id', tenantId, false)` first → RLS now scopes every query on this connection to this tenant
- `.filter(VendorCredential::isActive)` — inactive/rotated-out credentials are invisible
- if absent → throws `AgentException(400, "No active ANTHROPIC credential...")`
- `vendorCredentialService.decryptToken(credential)` → `LocalAesGcmCredentialEncryptor.decrypt()` — AES-256-GCM, keyed by `CREDENTIAL_LOCAL_KEY`

**3b. `sharedExecutionContextFactory.create(...)`**
- `LlmEngineFactory.create(ANTHROPIC, apiKey, modelName)` → `AnthropicChatModel.builder()...build()` — a real, ready-to-call LangChain4j client
- `new SharedExecutionContext(tenantId, executionId, chatModel, tools, systemPrompt)`
  → internally builds `new ToolCallingChatEngine(chatModel, tools, new ToolExecutionContext(tenantId, executionId), systemPrompt)` — this is where the explicit context object gets created and frozen for the whole request. `systemPrompt` (the `AgentDefinition`'s persona) becomes a `SystemMessage` prepended in `chat()` — see §4.

**3c. `buildSessionSpec(tenantId, executionId, definition)` — the credential-merging step SandboxSession needs**
- `if definition.getToolNames().contains("git_clone")`: `credentialResolver.resolve(tenantId, "GIT")` — resolved HERE, before any tool runs, not lazily inside `GitCloneTool` the way it used to be the only place this happened, and ONLY if this definition's tool list actually includes `git_clone` (a `general-assistant` run never touches `CredentialResolver` at all — see §7's symptom table if you're debugging why a credential lookup didn't happen)
- why up front: E2B only accepts env vars at sandbox **creation** time. If the session's sandbox got created by whichever tool happens to run first — say `RunShellCommandTool`, which asks for no credentials — a `GitCloneTool` call two rounds later would find no `GIT_TOKEN` in the already-running sandbox, silently. Resolving every known credential kind before the session even exists sidesteps that ordering trap entirely.
- `GitCloneTool` still calls `credentialResolver.resolve()` itself too, redundantly — not to get the token into the sandbox (already done here), but to decide whether to add the `http.extraHeader` flag to its `git clone` command at all.

**3d. `resolveAgentDefinition(agentSlug)` — the catalog lookup**
- `agentDefinitionRepository.findBySlugAndActiveTrue(agentSlug)` — `agent_definitions` has no RLS (platform-wide catalog, not tenant data), so this isn't scoped by `TenantContext` at all
- if absent → throws `AgentException(400, "Unknown or inactive agent: " + agentSlug)` — deliberately BEFORE `resolveApiKey`, so a bad agent slug fails fast without even checking whether the tenant has an LLM credential configured
- `POST /agents/execute`'s path validates this even earlier — `AgentExecutionService.enqueue()` does the same lookup before persisting a row at all, so an unknown agent never even reaches `QUEUED` (see §6)

---

## 4. The tool-calling loop itself — `ToolCallingChatEngine.chat(prompt)`

This is the part worth understanding cold, since it's where "why didn't the model use my
tool" or "why did the tool get the wrong arguments" bugs live.

```
chat(userMessage)
 │
 ├─ messages = [UserMessage(userMessage)]
 ├─ toolWasUsed = false
 │
 ├─ for round in 0 until MAX_TOOL_ROUNDS (6):                         // <- the multi-round loop
 │     response = chatModel.generate(messages, toolSpecifications)    // <- REAL Anthropic API call
 │         toolSpecifications was built once in the constructor:
 │         toSpecification(tool) for each AgentTool -> ToolSpecification
 │         (name, description, and EVERY parameter typed as a plain string —
 │          see AgentTool's javadoc for why: no nested/typed params yet)
 │
 │     aiMessage = response.content()
 │
 │     if !aiMessage.hasToolExecutionRequests():
 │         return ToolChatResult(aiMessage.text(), toolWasUsed)   // model settled on a final answer THIS round -- done
 │
 │     toolWasUsed = true
 │     messages.add(aiMessage)
 │     for each ToolExecutionRequest in aiMessage.toolExecutionRequests():
 │         result = executeTool(request)          // see below -- may run a sandboxed tool for real
 │         messages.add(ToolExecutionResultMessage.from(request, result))
 │     // loop back to the top -- model gets another turn, now seeing this round's tool results too
 │
 └─ (round cap hit -- model never stopped asking for tools on its own)
    finalResponse = chatModel.generate(messages, [])   // NO tool specs -- forces a text answer, not another round
    return ToolChatResult(finalResponse.content().text(), toolWasUsed)
```

Each round is a real Anthropic API call — a 3-round exchange (clone, read, final answer) costs
3 calls, not 2. Easy to undercount when estimating latency/cost from this loop; see §8.

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

**Formerly a known limitation, now fixed:** this loop used to execute only one round of tool
calls, so a prompt like *"clone the repo, then list its files"* would only execute the clone.
It's now bounded-multi-round (`MAX_TOOL_ROUNDS = 6`) specifically to support sequences like
that. The remaining reason a multi-step prompt might still only do the first step: the model
itself decides not to continue (e.g. it hedges after the first tool result instead of pushing
forward) — that's a prompt-engineering question, not a loop-mechanics one anymore. If you're
debugging "it stopped after one tool call," check whether `aiMessage.hasToolExecutionRequests()`
was actually false on round 2 (the model chose to answer) versus the round cap being hit (6
rounds of genuine back-and-forth is a lot for most tasks — hitting it usually means the model is
stuck in some kind of retry loop, worth looking at directly).

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
     │       │  NOTE: `sandboxClient` here is a SandboxSession, not SandboxClientHttpImpl
     │       │  directly -- GitCloneTool has no idea, and doesn't need to (see AbstractSandboxedTool's
     │       │  javadoc / SandboxSession's own javadoc).
     │       │
     │       └─ SandboxRunner.withSandbox(spec, work)
     │            handle = sandboxClient.create(spec)      // = SandboxSession.create(spec) -- spec here is IGNORED
     │                 IF this is the first sandboxed tool call in this execution:
     │                     -> delegate.create(session's OWN spec, built by AgentPromptRunner.buildSessionSpec --
     │                                         NOT the spec GitCloneTool just built two lines up)
     │                        -> SandboxClientHttpImpl.create()
     │                           -> POST http://<sidecar>/sandboxes  {tenantId, executionId, credentials, maxLifetimeSeconds, maxOutputBytes}
     │                           -> sidecar: Sandbox.create({apiKey: E2B_API_KEY, envVars: credentials, timeoutMs, metadata})
     │                              -> REAL E2B API CALL -- a real Firecracker microVM boots here
     │                           <- {sandboxId}
     │                 ELSE (a RunShellCommandTool/ReadFileTool/etc. call already created one this execution):
     │                     -> returns the SAME cached handle, no HTTP call, no new sandbox
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
     │                sandboxClient.destroy(handle)   // = SandboxSession.destroy(handle) -- a NO-OP
     │                    -- the real DELETE http://<sidecar>/sandboxes/{id} does NOT happen here
     │                    -- SandboxRunner (and every tool) thinks it just cleaned up after itself,
     │                    -- but the session is still alive for the next tool call in this execution.
     │                    -- The REAL destroy happens exactly once, in AgentPromptRunner's finally
     │                    -- block, via session.endSession(), after the whole multi-round loop (§4)
     │                    -- has completely finished -- see §3's step-by-step.
     │
     └─ formatResult(result) -> "exit_code: 0\nRepository cloned to /tmp/workspace/repo.\nstdout:\n..."
```

The formatted string above is exactly what gets fed back into `ToolCallingChatEngine`'s
next `chatModel.generate()` call as the tool's result — which, per §4, might trigger another
round of tool calls (e.g. `read_file` or `run_shell_command` next) rather than a final answer.
Those calls go through this exact same stack, hitting the "already created, return cached
handle" branch instead of provisioning a second sandbox.

---

## 6. The real (async, durable) path: `POST /agents/execute` → `AgentJobWorker`

Everything in §3-§5 above is the **synchronous spike** (`/agents/ping-with-tools`) — it blocks
the HTTP thread for the whole run and persists nothing if the app crashes mid-request. This is
the actual job-queue path (Weeks 9-10), and it reuses the ENTIRE `AgentPromptRunner.run()` call
from §3 (session creation, tool assembly, `SharedExecutionContext`, the multi-round loop, and
`endSession()`) unchanged for the real work; what's different here is everything *around* that
one call.

**6a. The enqueue request returns immediately, before any LLM call happens**

```
POST /agents/execute  { "prompt": "...", "agentSlug": "coding-agent" }
 -> JwtAuthFilter / TenantResolvingFilter (same as any request, see §3 step 1)
 -> AgentExecutionController.execute(principal, request)
      validates prompt is non-blank
      resolvedSlug = request.agentSlug() ?? "general-assistant"
      -> AgentExecutionService.enqueue(tenantId, prompt, resolvedSlug)
           agentDefinitionRepository.findBySlugAndActiveTrue(resolvedSlug)
                .orElseThrow(-> AgentException(400, "Unknown or inactive agent: ..."))
                -- validated BEFORE persisting anything -- an unknown agent never
                -- becomes a QUEUED row AgentJobWorker would only discover was
                -- doomed once it claimed it
           new AgentExecution(tenantId, agentType=resolvedSlug, triggerSource="API",
                               llmProvider="ANTHROPIC", prompt, status="QUEUED")
                -- agentType now holds the resolved AgentDefinition slug, repurposed
                -- from its original Week 1 meaning -- see AgentExecution's javadoc
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
          result = AgentPromptRunner.run(job.tenantId, job.id.toString(), job.agentType, job.prompt)
               -- job.agentType is the resolved agent slug set at enqueue time (see 6a) --
               -- already validated to exist, so resolveAgentDefinition() inside run() below
               -- will succeed (barring a race where the definition was deactivated between
               -- enqueue and claim, which would surface as a normal FAILED status here)
               -- IDENTICAL to §3's full run() breakdown -- resolves this tenant's Anthropic
               -- credential (RLS-scoped, now correctly, since TenantContext is the real tenant),
               -- builds a fresh SandboxSession + all five tools, runs chat()'s multi-round loop
               -- to completion (§4), including any sandboxed tool execution and its own audit
               -- logging, and tears the session down in its own finally block.
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
| "A tool that ran later can't see what an earlier tool did" (e.g. `read_file` says the file doesn't exist right after `git_clone`) | Check that ALL sandboxed tools in that execution were constructed against the SAME `SandboxSession` instance in `AgentPromptRunner.run()` — if a future change constructs one tool with the raw `sandboxClient` instead of `session`, that tool gets its own private, empty sandbox instead of sharing the real one. Also check `Workspace.ROOT` is the same literal in every tool (`GitCloneTool`, `RunShellCommandTool`, `WorkspacePath`) — a typo'd path would make them technically share a sandbox but still miss each other's files. |
| "A tool needing a credential doesn't get one, even though it's configured" | Check `AgentPromptRunner.buildSessionSpec()` — it must resolve that credential kind BEFORE the session's sandbox is created. A credential kind added later (e.g. a future `GITHUB` PAT for a "open a PR" tool) that isn't added to `buildSessionSpec` will silently never make it into the sandbox's env, no matter how correctly the tool itself calls `CredentialResolver` — see SandboxSession's javadoc for why this can't be fixed lazily per-tool-call. |
| "Sandbox lives way longer / shorter than expected" | Check `AgentPromptRunner.SESSION_MAX_LIFETIME` (10 min, session-wide) — NOT the `maxLifetime` field on the `SandboxSpec` an individual tool builds for itself, which `SandboxSession.create()` ignores entirely once a sandbox already exists for this execution (see §5's trace). |
| "Multi-step prompt only did the first step" | First check whether the model actually requested a second tool call at all — `ToolCallingChatEngine`'s loop is bounded but genuinely multi-round now (§4); if round 2's `aiMessage.hasToolExecutionRequests()` was false, the model chose to stop, which is a prompt-engineering problem, not a loop bug (this happens for real -- the model narrates "now let me..." without actually attaching a tool call that turn). If it's hitting `MAX_TOOL_ROUNDS`, that usually means the model is stuck retrying something, not that the cap is too low. |
| "Model says it doesn't have a tool I expect it to have" | Check which `agentSlug` was actually used (defaults to `general-assistant` if omitted, which only has `get_current_date_time`) — `GET /agents/definitions` shows every active definition's real `toolNames`. This is very likely correct behavior, not a bug: the whole point of the catalog is that different agents genuinely have different tool access. |
| "Unknown or inactive agent" error for a slug you're sure exists | Check `is_active` on that `agent_definitions` row, and that the slug match is exact (case-sensitive) -- `findBySlugAndActiveTrue` is a straight equality lookup, no normalization. Also check you're hitting the same database you seeded (`agent_hub` for `mvn spring-boot:run`, `agent_hub_test` for `mvn test` -- V6's seed rows are inserted by the same migration in both). |
| "Agent definition references a tool that doesn't exist" (500 from `ToolCatalog`) | A data-integrity problem, not a caller error -- either a typo in that `agent_definitions.tool_names` row, or a `ToolFactory` bean got removed/renamed without updating the rows that reference it. Check `ToolCatalog.all()` (or the exception message, which names the missing tool) against the definition's actual `tool_names` array. |

---

## 8. Quick reference: API call cost per round, and per-execution sandbox cost

Easy to forget when reading logs/costs: **every round** of `ToolCallingChatEngine`'s loop
(§4) is a real call to Anthropic — a 3-round exchange (e.g. clone → read → final answer) is 3
API calls, not 2, and a prompt that never needs a tool still makes exactly 1. Multiply rounds
by however many tool calls happened in each round if you're estimating total request volume.

Separately: since `SandboxSession` (§5/§6) keeps one sandbox alive for the WHOLE execution now
(up to `SESSION_MAX_LIFETIME`, 10 minutes) instead of one sandbox per tool call lasting a few
minutes each, a multi-tool-call execution's E2B bill is roughly "however long the whole
execution actually took," not "sum of each tool's own short-lived sandbox." A stuck or slow
execution now has a correspondingly larger cost exposure than before this change — worth
knowing if you're watching E2B usage.
