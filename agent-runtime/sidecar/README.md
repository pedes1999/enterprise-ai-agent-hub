# Sandbox sidecar

Internal HTTP service wrapping E2B's official JS SDK, called by
`agent-runtime`'s `SandboxClientHttpImpl` (see that class's javadoc for
*why* this exists as a sidecar instead of a native Java client). Not a
public API — nothing outside `gateway-api`/`agent-runtime` should ever call
this directly.

**Status: verified against a real E2B account.** `npm install` (76
packages, 0 vulnerabilities), started with `node --env-file=.env
server.js`, and exercised via `RunShellCommandToolManualIT`
(`SANDBOX_SIDECAR_URL=http://localhost:8090/ mvn test -pl agent-runtime
-Dtest=RunShellCommandToolManualIT`): both tests passed against real E2B
sandboxes (confirmed via the sidecar's own logs — 3 distinct real sandbox
IDs created across the two test methods), including the ephemerality check
(a file written in one execution's sandbox is not visible in the next
execution's fresh one).

Also verified containerized: `docker build -t agent-hub-sandbox-sidecar .`
+ `docker run -p 8090:8090 --env-file .env agent-hub-sandbox-sidecar`, then
the same `RunShellCommandToolManualIT` run passed against the container --
confirmed via the container's own logs and a real E2B sandbox execution
through it.

To run it yourself, either:

**Directly with Node:**
1. `npm install`
2. Copy `.env.example` to `.env`, fill in a real `E2B_API_KEY` (never edit
   `.env.example` itself with a real key — that file is git-tracked, `.env`
   is gitignored)
3. `npm start` (or `node --env-file=.env server.js`)
4. `GET /health` should return `{"status":"UP","liveSandboxes":0}`

**Or with Docker:**
1. Same `.env` setup as above
2. `docker build -t agent-hub-sandbox-sidecar .`
3. `docker run -p 8090:8090 --env-file .env agent-hub-sandbox-sidecar`

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
