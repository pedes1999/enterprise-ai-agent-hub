# Sandbox sidecar

Internal HTTP service wrapping E2B's official JS SDK, called by
`agent-runtime`'s `SandboxClientHttpImpl` (see that class's javadoc for
*why* this exists as a sidecar instead of a native Java client). Not a
public API — nothing outside `gateway-api`/`agent-runtime` should ever call
this directly.

**Status: written, not run.** No Node.js was available in the environment
this was built in, so this has not been `npm install`ed, started, or
exercised against a real E2B account. Before trusting it:

1. `npm install`
2. Copy `.env.example` to `.env`, fill in a real `E2B_API_KEY`
3. `npm start`
4. Confirm `GET /health` responds, then run `agent-runtime`'s
   `LlmEngineFactoryManualIT`-style manual integration test (see that
   module's tests for the pattern — gated behind an env var, never runs in
   CI) against a real sandbox.
5. Double-check the E2B SDK calls in `server.js` (`Sandbox.create`,
   `sandbox.commands.run`, `sandbox.files.write/read`, `sandbox.kill`)
   still match the current SDK — these were written from documented
   behavior, not verified against a running install.

## Contract

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/sandboxes` | `{tenantId, executionId, credentials, maxLifetimeSeconds, maxOutputBytes}` | `{sandboxId}` |
| POST | `/sandboxes/{id}/commands` | `{command, timeoutSeconds}` | `{exitCode, stdout, stderr, truncated, durationMs}` |
| PUT | `/sandboxes/{id}/files` | `{path, contentBase64}` | 204 |
| GET | `/sandboxes/{id}/files?path=...` | — | `{contentBase64}` |
| DELETE | `/sandboxes/{id}` | — | 204 (idempotent — unknown/already-gone id is not an error) |
| GET | `/health` | — | `{status, liveSandboxes}` |

Every 4xx/5xx response body is `{error: "..."}`. `SandboxClientHttpImpl`
treats any status ≥ 300 as a `SandboxException`, except `destroy()`, which
swallows failures by contract (idempotent, never throws).

## Deployment model (not yet decided)

Currently a single stateful process (in-memory `Map` of live sandboxes —
see the comment in `server.js`). Fine for one `gateway-api` instance
talking to one sidecar instance. If this ever needs to scale to multiple
sidecar replicas, that in-memory registry needs to move to shared storage
(Redis) or requests need sticky routing back to whichever replica created a
given sandbox — not a problem worth solving before the single-instance
version has been validated operationally.
