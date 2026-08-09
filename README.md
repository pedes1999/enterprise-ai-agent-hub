# Enterprise AI Agent Hub

Model-agnostic, multi-tenant platform for automating full-stack engineering
workflows via LLM-driven agents.

## Module layout

| Module | Responsibility |
|---|---|
| `common-dto` | Shared request/response contracts. No framework dependency. |
| `agent-core` | Provider-agnostic LLM abstraction (LangChain4j) + `SharedExecutionContext`. |
| `agent-runtime` | Sandboxed tool execution — filesystem, terminal, git. |
| `gateway-api` | Spring Boot app: auth, tenant/credential management, `/agents/execute` entrypoint. |

## Build

```bash
mvn clean install
```

## Run (gateway-api)

```bash
cd gateway-api
mvn spring-boot:run
```

Requires a running Postgres instance matching the `DB_URL` / `DB_USERNAME` /
`DB_PASSWORD` env vars in `application.yml` (defaults assume local Postgres
on 5432, db `agent_hub`).

## Status

Phase 1 (foundation) in progress:
- [x] Multi-module Maven skeleton
- [x] Spring Boot app boots with stateless security baseline
- [ ] Tenant / user / credential JPA entities
- [ ] JWT auth filter + platform API key issuance
- [ ] Encrypted credential storage (KMS/Vault-backed)
