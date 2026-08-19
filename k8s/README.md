# Kubernetes manifests

A proof of concept, and proven rather than asserted: everything here was
applied to a real cluster (kind v0.30, Kubernetes v1.34), verified end to end,
and then torn down. The findings section below is what running it caught —
each item was a manifest that looked correct and wasn't.

## What it deploys

| Object | Notes |
|---|---|
| `postgres` | StatefulSet, 1 replica, PVC. pgvector image; `init.sh` mounted from a ConfigMap creates the non-superuser app role, so RLS is actually enforced. |
| `gateway` | Deployment, **2 replicas**. Startup/readiness/liveness probes, graceful shutdown, `preStop` drain. |
| `sidecar` | Deployment, 1 replica. Needs a real `E2B_API_KEY` to do anything. |
| `frontend` | Deployment, 2 replicas, nginx. |

## Running it

```bash
kind create cluster --name agent-hub-poc

docker build -t agent-hub/gateway-api:poc -f gateway-api/Dockerfile .
docker build -t agent-hub/frontend:poc --build-arg API_BASE_URL=http://localhost:18080 frontend
docker build -t agent-hub/sidecar:poc agent-runtime/sidecar
for i in gateway-api frontend sidecar; do kind load docker-image agent-hub/$i:poc --name agent-hub-poc; done

kubectl apply -f k8s/00-namespace.yaml -f k8s/10-config.yaml

# Secrets are generated, never committed. 20-secret.example.yaml is the
# documented shape; this is the version that never touches disk.
kubectl create secret generic gateway-secrets -n agent-hub \
  --from-literal=DB_USERNAME=hub_user \
  --from-literal=DB_PASSWORD="$(python -c 'import secrets;print(secrets.token_urlsafe(24))')" \
  --from-literal=JWT_SECRET="$(python -c 'import secrets;print(secrets.token_urlsafe(48))')" \
  --from-literal=CREDENTIAL_LOCAL_KEY="$(python -c 'import os,base64;print(base64.b64encode(os.urandom(32)).decode())')" \
  --from-literal=E2B_API_KEY=your-e2b-key

kubectl apply -f k8s/30-postgres.yaml -f k8s/40-gateway.yaml -f k8s/50-sidecar.yaml -f k8s/60-frontend.yaml
kubectl wait --for=condition=ready pod --all -n agent-hub --timeout=300s

kubectl port-forward -n agent-hub svc/gateway 18080:8080 &
kubectl port-forward -n agent-hub svc/frontend 18081:80 &
```

`API_BASE_URL` is baked into the frontend bundle at build time (Angular has no
runtime config), so it must match wherever the **browser** will reach the
gateway — an env var on the Deployment would do nothing.

Teardown is the namespace plus the cluster:

```bash
kubectl delete namespace agent-hub
kind delete cluster --name agent-hub-poc
```

## What running it actually proved

- **35 migrations applied, schema at v35, 11 `model_pricing` rows seeded.**
  Both replicas run Flyway at boot and race for it; that is safe because
  Flyway takes a Postgres advisory lock, so one migrates and the other waits.
- **Both replicas serve traffic**, confirmed from inside the cluster.
- **Rolling restart is zero-downtime** — 441 consecutive health probes, 0
  failures, measured across a `kubectl rollout restart`.
- Registering a tenant, reading `/agents/executions/spend`, and setting a
  monthly budget all work against the in-cluster database.

## Findings — things that only showed up by running it

**`runAsNonRoot` is not enough on its own.** The image declares `USER app`, a
name. The kubelet cannot resolve a name to a uid without running the image, so
it refused the pod with `CreateContainerConfigError: image has non-numeric
user`. Fixed by stating `runAsUser: 100` / `runAsGroup: 101` explicitly.

**Rolling restarts were dropping requests** — 4 of 150 probes failed. Two
causes, both needed fixing: Spring Boot defaults to immediate shutdown (in-flight
requests are dropped), and endpoint removal is eventually consistent, so a
terminating pod still receives new connections for a moment. `server.shutdown:
graceful` plus a 5s `preStop` sleep took it to 0 of 441.

**A Service load-balances per TCP connection, not per request.** An early test
using `fetch` (keep-alive) sent all 140 requests down one connection and hit a
single pod, which looked like broken load balancing. With `agent: false` the
traffic split across both pods. Worth knowing before concluding anything from a
load test — and worth knowing for the rate limiter, whose counters are
per-instance, so a keep-alive client is limited by one pod while a
connection-per-request client gets roughly N× the ceiling.

## Not included, deliberately

No Ingress, TLS, HPA, NetworkPolicy, or PodDisruptionBudget. Those are
environment-specific (which ingress controller, which cert issuer, which
metrics stack) and would be untested guesses here rather than the working
baseline this is. `port-forward` is the access path precisely because it needs
no cluster add-ons.
