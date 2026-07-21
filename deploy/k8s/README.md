# Basquin on Kubernetes (kind demo)

> **Prefer the operator for real use.** This page bakes the agents into a **custom image**, which
> means a bespoke rebuild per app. The [operator](../../docs/USAGE.md#kubernetes-instrument-any-app-with-the-operator)
> instruments an *unmodified* Deployment at deploy time (apply a `BasquinTarget`, revert by
> deleting it) — see [`deploy/e2e/e2e.sh`](../e2e/) for the full in-cluster flow. Keep using this
> baked path if you want a self-contained one-command JPetStore demo with no operator install.

A one-command local Kubernetes demo: JPetStore runs as a **pod** with the full Basquin
stack baked in — the valve (iteration boundaries + invariants), the Basquin agent, and the
JaCoCo coverage agent — inside a [kind](https://kind.sigs.k8s.io/) (Kubernetes-in-Docker) cluster.

## Prereqs

- `docker`, `kind`, `kubectl`
- A built JPetStore WAR (`javax.servlet`; see [../../docs/THIRD-PARTY-APPS.md](../../docs/THIRD-PARTY-APPS.md))

## Bring it up

```bash
JPETSTORE_WAR=/abs/jpetstore.war deploy/k8s/up.sh
```

This builds the agents, bakes a self-contained image (`deploy/k8s/Dockerfile.jpetstore`) tagged
uniquely per build, creates the `basquin` kind cluster, loads the image, and applies
`deploy/k8s/jpetstore.yaml` (Deployment + Service). Every `kubectl` call is pinned to
`--context kind-basquin`, and the deploy does an explicit `set image` — so a re-run can neither
touch whatever context happens to be current nor silently keep the previously loaded image.

When it finishes, the JPetStore pod is `Running` with:

- **8080** — the app, every request wrapped by the Basquin valve (server-side invariants)
- **6300** — the JaCoCo coverage tcpserver (scoped to `org.mybatis.jpetstore.*`)

## Drive it (all features, in-cluster)

The Service is deliberately **ClusterIP**: JaCoCo's remote-control protocol is unauthenticated and
lets any client dump *and reset* execution data, so port 6300 is never published outside the
cluster (DD-022). Reach both ports through a port-forward.

```bash
kubectl --context kind-basquin port-forward svc/jpetstore 8080:8080 6300:6300 &

# extract the app's classes for the coverage analyzer
D=$(mktemp -d); (cd "$D" && unzip -q /abs/jpetstore.war 'WEB-INF/classes/*')

./gradlew runHttpDriveCoverage \
  -Dexamples.http.baseUrl=http://localhost:8080 \
  -Dbasquin.coverage.jacoco=localhost:6300 \
  -Dbasquin.coverage.classes="$D/WEB-INF/classes" \
  -Dbasquin.invariant.latency.maxMs=25 -Dbasquin.invariant.mode=soft
```

The live panel shows execs, latency, **invariant finds** (harvested server-side from the pod via
the valve headers), and the **coverage %** of JPetStore's code (pulled from the pod's JaCoCo
agent). Every feature — valve, agent invariants, coverage over HTTP, exploration UI — runs against
the app in the cluster.

Verify pieces directly:

```bash
kubectl --context kind-basquin get pods -l app=jpetstore
curl -D - -o /dev/null "http://localhost:8080/actions/Catalog.action"   # X-Basquin-Invariant-* headers
```

## Tear down

```bash
kubectl --context kind-basquin delete -f deploy/k8s/jpetstore.yaml
kind delete cluster --name basquin
```
