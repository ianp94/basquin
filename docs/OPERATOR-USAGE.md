# Basquin operator — usage guide

How to actually drive the operator: instrument a running app, run a bounded coverage-guided test
against it, and read the result. For *why* the operator is shaped this way (explicit-patch vs.
admission webhook, the two-CRD split, the trust boundary), see [OPERATOR-DESIGN.md](OPERATOR-DESIGN.md)
and [CAMPAIGN-DESIGN.md](CAMPAIGN-DESIGN.md); this doc is the task-oriented companion to them.

The canonical, always-working reference is [`deploy/e2e/e2e.sh`](../deploy/e2e/e2e.sh) — it builds
every image, deploys the operator, instruments a raw JPetStore, runs a campaign, and asserts the
result in a kind cluster. Every YAML block below is adapted from it.

## 1. Overview

The operator is two custom resources in group `basquin.dev/v1alpha1`:

- **`BasquinTarget`** — *instrument a Deployment*. Long-lived: it patches an unmodified app
  Deployment to load the Basquin agents (thread tracker, JaCoCo coverage) and, optionally, stands
  up a headless coverage Service. Reversible — deleting it restores the app exactly. "This app
  carries the agents."
- **`BasquinCampaign`** — *run a bounded test*. Ephemeral: it references an already-instrumented
  target, launches the coverage-guided driver as a `Job`, and (by default) a per-campaign dashboard,
  then aggregates the result into status. One target can be driven by many campaigns over time.

You instrument once (target), then run tests against it (campaigns).

> **Namespaced by design.** The operator watches and mutates only its own namespace — `WATCH_NAMESPACE`
> is set from the pod's namespace via the downward API, and the operator *refuses to start* if it's
> empty rather than defaulting to cluster-wide. The standing privilege is a `Role`/`RoleBinding` in
> one namespace, never a `ClusterRole`. To instrument another namespace, install another instance.
> See [OPERATOR-DESIGN.md §6](OPERATOR-DESIGN.md#6-rbac--the-trust-boundary-concretely).

## 2. Prerequisites & install

The operator install is three things: the **two CRDs**, the **namespaced RBAC** (ServiceAccount +
Role + RoleBinding), and the **controller Deployment**. It also needs three images it launches or
injects — you supply each via a flag:

| Image | Flag | Used for |
|-------|------|----------|
| `basquin/agents` | `--agents-image` | the initContainer the target injection copies agents from |
| `basquin/runner` | `--runner-image` | the campaign driver `Job` (coverage-guided runner) |
| `basquin/dashboard` | `--dashboard-image` | the per-campaign dashboard Deployment |

Each flag empty falls back to a built-in default; in practice you pin them to a fixed tag, and the
Helm chart wires all three onto the controller for you.

### Install from the published repo (no clone, no build)

The chart is published to a GitHub Pages Helm repo and its default images to GitHub Container Registry
(`ghcr.io/ianp94/basquin-*`), so this pulls real images with nothing to build:

```bash
helm repo add basquin https://ianp94.github.io/basquin/charts
helm repo update
helm install basquin basquin/basquin-operator \
  --namespace basquin-system --create-namespace \
  --set fullnameOverride=basquin
```

Then jump to §3. The rest of this section covers building the images yourself (for a local kind
cluster or a private registry).

> **Architectures (DD-027).** Published images are multi-arch manifest lists — `linux/amd64` and
> `linux/arm64` — so an arm64 cluster (Graviton, Apple-Silicon `kind`) pulls the right variant
> automatically. arm64 is **functionally validated in CI on real arm64 hardware**
> (`arm64-smoke.yml`: native `.so` build, `-agentpath` load in a real arm64 JVM, JVMTI hooks
> asserted active, full leak-oracle iteration loop) on every native/agent change. amd64 is
> exercised end-to-end by the in-cluster e2e on every change. One historical note: the v0.2.0
> images predate that check (their arm64 `.so` was QEMU-cross-compiled from the same source);
> releases after 2026-07-21 ship images backed by it.

**Build the three images** (each `build.sh` takes `[TAG] [KIND_CLUSTER]`; with a cluster name it also
`kind load`s the image):

```bash
deploy/agents-image/build.sh    0.2.0 <kind-cluster>    # => basquin/agents:0.2.0
deploy/runner-image/build.sh    0.2.0 <kind-cluster>    # => basquin/runner:0.2.0
deploy/dashboard-image/build.sh 0.2.0 <kind-cluster>    # => basquin/dashboard:0.2.0

# operator image (a fixed tag => IfNotPresent, so kind uses the loaded image, not controller:latest)
docker build -t basquin/operator:0.2.0 operator/
kind load docker-image basquin/operator:0.2.0 --name <kind-cluster>
```

### Build + install from a checkout (local / kind)

For a local kind cluster or a private registry, build the images (above) and install the chart from
the checkout, `--set`ting the locally-built images (chart docs:
[the chart README](../deploy/helm/basquin-operator/README.md)):

```bash
helm install basquin ./deploy/helm/basquin-operator \
  --namespace basquin-system --create-namespace \
  --set fullnameOverride=basquin \
  --set imageTag=0.2.0 \
  --set image.repository=basquin/operator \
  --set images.agents=basquin/agents \
  --set images.runner=basquin/runner \
  --set images.dashboard=basquin/dashboard
```

(One `imageTag` sets all four image tags — for a published release it defaults to the chart's
appVersion, so a plain `helm install` from the repo needs no version flags.)

`--set fullnameOverride=basquin` makes the resources read as `basquin-controller-manager`, … to
match this guide; omit it for the default `<release>-<chart>` prefix. RBAC is namespaced `Role`s (the
operator needs no cluster-scoped grants). Note: Helm does **not** upgrade or delete CRDs — re-apply
changed CRDs by hand on `helm upgrade`, and they (plus any remaining CRs) are left in place on
`helm uninstall`.

### Or install with kustomize

```bash
kubectl apply -f operator/config/crd/bases/basquin.dev_basquintargets.yaml
kubectl apply -f operator/config/crd/bases/basquin.dev_basquincampaigns.yaml
kubectl create namespace basquin-system

kubectl kustomize operator/config/default \
  | sed 's#image: controller:latest#image: basquin/operator:0.2.0#' \
  | kubectl apply -f -                      # controller Deployment + ServiceAccount/Role/RoleBinding

# then wire the three image flags onto the controller (Helm does this for you):
for arg in \
  --agents-image=basquin/agents:0.2.0 \
  --runner-image=basquin/runner:0.2.0 \
  --dashboard-image=basquin/dashboard:0.2.0 ; do
  kubectl -n basquin-system patch deploy basquin-controller-manager --type=json \
    -p="[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"$arg\"}]"
done
kubectl -n basquin-system rollout status deploy/basquin-controller-manager --timeout=120s
```

The `kubectl patch` loop appends unconditionally — re-running it by hand duplicates the args. Guard
each with a `grep -q -- '--agents-image'`-style check as `deploy/e2e/e2e.sh` does, or just use Helm,
which wires the flags declaratively. (The in-cluster e2e installs either way — `INSTALL=helm
deploy/e2e/e2e.sh` exercises the chart end to end; the default uses kustomize.)

## 3. Instrument an app (`BasquinTarget`)

Point a `BasquinTarget` at a Deployment in the same namespace. The operator patches that
Deployment's pod template — an initContainer copies the agents into a shared `emptyDir`, the agent
flags are **appended** to the container's JVM opts env var (never replacing your heap/GC flags), the
coverage port is exposed — then rolls it out.

```yaml
apiVersion: basquin.dev/v1alpha1
kind: BasquinTarget
metadata:
  name: jpetstore
  namespace: basquin-system
spec:
  # WHAT to instrument — a Deployment in this namespace.
  deploymentRef:
    name: jpetstore
  # WHICH container in the pod template. Optional when the pod has one container; REQUIRED otherwise.
  container: jpetstore

  # HOW the JVM picks up the agents. The operator APPENDS its flags to this env var's existing value.
  #   CATALINA_OPTS       — Tomcat images
  #   JAVA_TOOL_OPTIONS   — everything else (the default)
  jvmOptsVar: CATALINA_OPTS

  # WHICH agents to inject. Each toggles independently.
  agents:
    threadTracker: true          # native JVMTI agent (-agentpath) — the leak/thread oracle (default true)
    coverage:
      enabled: true              # JaCoCo tcpserver for coverage-guided-over-HTTP
      port: 6300                 # coverage port inside the pod (default 6300)
      includes: "org.mybatis.jpetstore.*"   # REQUIRED when enabled — no wildcard default (DD-022)

  # Invariant thresholds, passed through as -Dbasquin.invariant.* flags.
  invariants:
    mode: soft                   # soft (record + continue) | hard (fail the iteration). default soft
    latencyMaxMs: 25
    heapDeltaMaxKb: 256

  # Create a headless Service selecting this target's pods on the coverage port, so one driver can
  # reach every replica by DNS for DD-023 union coverage. Opt-in.
  coverageService: true
```

Field notes:

- **`agents.coverage.includes` is mandatory when coverage is enabled** and has no default. A `"*"`
  filter silently instruments Tomcat/MyBatis, inflating the coverage denominator and faking latency
  violations (DD-022); the CRD's CEL rule rejects an empty/omitted `includes` at apply time.
- **`agents.valve`** (Tomcat server-side invariant valve, default true in the schema) exists but valve
  *mounting* is deferred — it needs a `context.xml` entry, not a JVM flag — so leave it as is for now.
- **`jvmOptsVar`** is an enum: only `CATALINA_OPTS` or `JAVA_TOOL_OPTIONS`. The original value is
  stashed so revert is exact.
- **`dashboardPush`** (`host:port`) — optional; push server-side status/findings to a standalone
  dashboard. Leave empty unless you already run one.

**Check it landed.** Wait for `status.phase` to reach `Injected` and grab the coverage endpoint the
campaign will consume:

```bash
kubectl -n basquin-system get basquintargets
# NAME        DEPLOYMENT   PHASE      INSTRUMENTED   AGE
# jpetstore   jpetstore    Injected   1              30s

kubectl -n basquin-system get basquintarget jpetstore -o jsonpath='{.status.coverageEndpoint}'
# jpetstore-basquin-jacoco.basquin-system.svc.cluster.local:6300
```

Phases: `Pending → Injecting → Injected` (or `Reverting`/`Error`). With `coverageService: true` the
operator creates a headless Service named after the **Deployment it targets** —
`<deploymentRef.name>-basquin-jacoco` (not the `BasquinTarget` CR's own name; they match in this
example) — and writes its DNS name to `status.coverageEndpoint`.

**Or with the CLI.** The `basquin` CLI (download a binary for your platform from the
[GitHub Releases](https://github.com/ianp94/basquin/releases), or build it with
`make -C operator cli` → `operator/bin/basquin`) applies
the same typed `BasquinTarget` from flags, so you don't hand-write the YAML above — and `--wait`
blocks until `Injected`:

```bash
basquin instrument -n basquin-system --deployment jpetstore \
  --jvm-opts-var CATALINA_OPTS \
  --coverage-includes 'org.mybatis.jpetstore.*' --coverage-service \
  --invariant-mode soft --latency-max-ms 25 --heap-delta-max-kb 256 --wait
# Applied BasquinTarget basquin-system/jpetstore (deployment "jpetstore")
#   phase: Injected
# Injected ✓  coverageEndpoint=jpetstore-basquin-jacoco.basquin-system.svc.cluster.local:6300
```

`basquin instrument -h` lists every flag. Running a campaign / reading status via the CLI is
planned; for now the campaign is applied as YAML (next section).

### Server-side oracle

Instrumented targets now capture **server-side** heap/thread/latency findings (the availability
oracle) — these come from the Tomcat app's own JVM, not the driver. This is enabled by
`-Dbasquin.boundary=agent`, which the operator sets automatically. The server-side measurements
include:

- **Latency:** measured inside `StandardHostValve.invoke`, before and after the request.
- **Heap delta:** captured per-request; configured with `invariants.heapDeltaMaxKb` on the target.
- **Thread delta:** live non-daemon thread count at request start and end; configured with
  `invariants.threadDelta` on the target.

Violations are reported in response headers (`X-Basquin-Invariant-*`), which the driver harvests and
records.

> **In-cluster load control (`/__basquin`):** The instrumented app exposes an in-cluster-only control
> surface on its own port. The load-mode driver toggles the valve into lock-free mode via
> `/__basquin/mode` and reads the app's heap/thread drift via `/__basquin/drift`. These endpoints are
> unauthenticated (in-cluster trust model, same as the JaCoCo coverage port; see DD-022); do not
> expose the app's port outside the cluster. Hardening is tracked as a follow-up.

## 4. Run a test (`BasquinCampaign`)

Once the target is `Injected`, a `BasquinCampaign` drives it. The operator gates on the target
being `Injected`, reads its `status.coverageEndpoint` for coverage, launches the driver `Job`, and
aggregates status.

```yaml
apiVersion: basquin.dev/v1alpha1
kind: BasquinCampaign
metadata:
  name: jpetstore-campaign
  namespace: basquin-system
spec:
  # WHAT to drive — an existing BasquinTarget in this namespace (must be Injected before the run).
  targetRef:
    name: jpetstore

  # REQUIRED — the app's HTTP entrypoint. The operator does NOT front the app with a Service of its
  # own, so name an in-cluster Service URL here (you create the app Service yourself).
  baseURL: http://jpetstore-app.basquin-system.svc.cluster.local:8080

  driver:
    # Bound the run — set EXACTLY ONE of iterations / duration (CEL-enforced).
    iterations: 200
    # duration: "10m"            # ...or a Go-style duration; the runner exits cleanly at the deadline

    # Grammar + corpus come from ConfigMaps (see §5).
    grammarConfigMap: jpetstore-grammar
    corpusConfigMap: jpetstore-corpus
    # grammarKey: jpetstore.grammar   # optional; defaults to the ConfigMap's sole key

    # Where the app's .class files live INSIDE the target's container image. An initContainer copies
    # them out for JaCoCo covered/total analysis. Default is the Tomcat WAR layout below.
    classesPath: /usr/local/tomcat/webapps/ROOT/WEB-INF/classes

    # Optional — invariants for the driver JVM (same shape as the target's).
    # invariants: { mode: soft, latencyMaxMs: 25, heapDeltaMaxKb: 256 }

  # dashboard: {}                # per-campaign dashboard on by default — see §6
```

Field notes:

- **`baseURL` is required** — there's no default. It's the URL the driver hits, typically a ClusterIP
  Service you put in front of the app pods.
- **`driver.iterations` XOR `driver.duration`** — set exactly one. `iterations` uses the runner's
  count cap; `duration` (e.g. `"10m"`, `"30s"`) makes the runner stop and exit cleanly at the deadline
  so its summary still gets written (a hard kill would skip it — DD-025 §7.2).
- **`driver.classesPath`** must point at real `.class` *files in the target image*. A war-only image
  (no exploded `WEB-INF/classes`) has nothing to copy and coverage reports zero — see Troubleshooting.

**Or with the CLI.** `basquin run` creates the grammar/corpus ConfigMaps from local files (§5), applies
the campaign, and — with `--watch` — tails it to completion, printing coverage/findings/dashboard. The
ConfigMaps are owner-referenced to the campaign, so `kubectl delete basquincampaign` GCs them:

```bash
basquin run -n basquin-system --target jpetstore \
  --base-url http://jpetstore-app.basquin-system.svc.cluster.local:8080 \
  --iterations 200 \
  --grammar examples/grammar/jpetstore.grammar \
  --corpus  examples/corpus/jpetstore \
  --watch
# Created BasquinCampaign basquin-system/jpetstore-campaign (target "jpetstore")
#   grammar ConfigMap jpetstore-campaign-grammar (key jpetstore.grammar)
#   corpus ConfigMap jpetstore-campaign-corpus (27 file(s))
#   phase: Running
#   phase: Completed
# Completed ✓  coverage=22.8%  findings=44  dashboard=http://jpetstore-campaign-dashboard...:7070
```

`--corpus` reads the dir's top-level files **and** its `values/` subdir (the corpus layout), keyed by
basename — the same flat convention the `kubectl create configmap --from-file` commands below produce.
`--duration 10m` bounds by time instead of `--iterations`; `--no-dashboard` / `--external-push host:port`
control the dashboard. `basquin run -h` lists every flag.

### Load / soak mode — hammer the interesting states (`spec.mode: load`)

A campaign runs in one of two modes (`spec.mode`, default `explore`):

- **`explore`** (default) — the coverage-guided fuzz above. On completion it **emits its interesting
  "replay corpus"** (the inputs that reached new coverage) as a campaign-owned ConfigMap named
  `<campaign>-corpus-out`, recorded in `status.corpusConfigMap`.
- **`load`** — replays a saved corpus at a fixed **concurrency** for a **duration**, no mutation and no
  coverage sampling, watching the same invariant oracles under sustained traffic.
  *Fuzz to discover the interesting states, then hammer those states under load.*

  > **Lock-free load (DD-029).** Explore mode serializes requests through the valve so per-request
  > heap/thread deltas are attributable — which would cap a load run at concurrency 1. In load mode the
  > driver toggles the target's valve **lock-free** for the run (over a `/__basquin/mode` control request
  > on the app's own port; auto-reverts on a TTL if the driver dies), so the app is driven concurrently.
  > It then reports end-to-end **latency percentiles + 5xx** (client-side) and the app's **heap/thread
  > drift** (polled from `/__basquin/drift`, an absolute in-JVM reading). Per-request heap *attribution*
  > is given up in load mode — that's explore's job. **Security:** the `/__basquin/*` control surface is
  > unauthenticated on the app port (in-cluster trust, same posture as the JaCoCo coverage port, DD-022);
  > don't expose the target's port outside the cluster. Hardening is a tracked follow-up.

So the workflow is: run an `explore` campaign, then point a `load` campaign at the corpus it emitted:

```yaml
apiVersion: basquin.dev/v1alpha1
kind: BasquinCampaign
metadata: { name: jpetstore-load, namespace: basquin-system }
spec:
  mode: load
  targetRef: { name: jpetstore }
  baseURL: http://jpetstore-app.basquin-system.svc.cluster.local:8080
  driver:
    duration: 30m                            # load is time-bounded (CEL requires duration, not iterations)
    concurrency: 50                          # parallel in-flight requests
    corpusConfigMap: jpetstore-campaign-corpus-out   # the corpus the explore run emitted
    # warmup: 30s                            # optional: excluded from the reported latency percentiles
```

Notes:
- **`load` requires `driver.corpusConfigMap`** and forbids a `grammarConfigMap` (CEL-enforced) — it
  replays fixed requests, it doesn't explore. It ignores `driver.iterations`; use `driver.duration`.
- The corpus can be any corpus ConfigMap — the one an explore run emitted (`status.corpusConfigMap`),
  or one you build yourself (route strings, one per line).
- A load run's driver Job is **coverage-free** (no JaCoCo, no class extraction).

Read the results from `status.load`:

```bash
kubectl -n basquin-system get basquincampaign jpetstore-load -o jsonpath='{.status.load}'
# {"requests":1284003,"throughputRps":"713.4","latencyMs":{"p50":8,"p90":22,"p99":61,"max":240},
#  "heapDriftKb":1840,"threadDrift":0,"violations":{"latency":12,"heap":0,"thread":0}}
```

First cut: only `violations.latency` is threshold-gated (against `invariants.latencyMaxMs`); heap/thread
are reported as end-to-end **drift** (`heapDriftKb`/`threadDrift`), measured on the driver.

**Or with the CLI:**

```bash
basquin run -n basquin-system --name jpetstore-load --mode load --target jpetstore \
  --base-url http://jpetstore-app...:8080 \
  --duration 30m --concurrency 50 --corpus ./saved-corpus --watch
# Completed ✓  ... load: 1284003 requests, 713.4 rps, p99 61ms, 12 latency violations
```

## 5. Grammar & corpus ConfigMaps

The driver's exploration surface is a **grammar** (structure) and a **corpus** (values), each delivered
as a ConfigMap. The split — corpus supplies real values, grammar supplies how to invent new ones that
look real — is DD-018; for writing a grammar see [USAGE.md → "Writing a request grammar"](USAGE.md#writing-a-request-grammar).

**Grammar ConfigMap** — one key, the grammar file. `grammarKey` defaults to the sole key, so you can
omit it if the ConfigMap has exactly one entry:

```bash
kubectl -n basquin-system create configmap jpetstore-grammar \
  --from-file=jpetstore.grammar=examples/grammar/jpetstore.grammar \
  --dry-run=client -o yaml | kubectl apply -f -
```

**Corpus ConfigMap** — a *flat* map of files keyed by basename. The example corpus has two levels —
route-seed files at the top (`cart_add.txt`, `catalog_item.txt`, …) and value files under `values/`
(`itemId.txt`, `categoryId.txt`, …). `kubectl create configmap --from-file` on a directory is
**non-recursive**, so pass *both* levels; each file lands as a key named by its basename:

```bash
kubectl -n basquin-system create configmap jpetstore-corpus \
  --from-file=examples/corpus/jpetstore/ \
  --from-file=examples/corpus/jpetstore/values/ \
  --dry-run=client -o yaml | kubectl apply -f -
```

How this resolves at run time: the driver mounts the ConfigMap and runs with
`-Dbasquin.corpusDir=<mount>`. The route-seed files are walked for `/`-prefixed routes, and the
grammar's `@`-value-file references — e.g. `@../corpus/jpetstore/values/itemId.txt` — fall back to
`<corpusDir>/<basename>` (i.e. the key `itemId.txt`). That's why the flat basename convention matters:
`@../a/b/c/itemId.txt` and a ConfigMap key `itemId.txt` must line up.

## 6. Dashboard

> **Security model — read this before a shared cluster.** The operator mints a random 256-bit token
> per campaign into a `<campaign>-dashboard-token` Secret, mounts it into both the dashboard and its
> driver, and the driver authenticates every push with it. So **writes are authenticated**: another
> pod cannot POST spoofed status or findings into your campaign.
>
> **Reads are authenticated too** (DD-028): with a token configured (always, for operator-managed
> dashboards), `/api/…` and the HTML UI answer 401 without it. Scripts send `X-Basquin-Token`;
> browsers authenticate once through the tokenized URL that `basquin dashboard` prints — the server
> swaps `?token=…` for an `HttpOnly` session cookie and redirects so the token leaves the address
> bar. The one unauthenticated endpoint is `/healthz` (the readiness probe; no campaign data).
> Two residuals to know about: the token appears once in the URL (shell/browser history), and
> browser cookies ignore ports — the session cookie rides to anything else the browser talks to on
> `localhost`. Both are acceptable for a per-campaign throwaway token that dies with the campaign.
> A `NetworkPolicy` remains sound defense-in-depth — but some CNIs (including `kind`'s default)
> silently do not enforce NetworkPolicy, so verify yours does before relying on it.
> This model assumes the dashboard stays on its default loopback/ClusterIP reach via `kubectl
> port-forward` (an authenticated, encrypted tunnel); binding it wider without TLS in front sends
> the token and cookie in plaintext.


By default every campaign gets its **own** dashboard — the operator creates a Deployment + Service
(`<campaign>-dashboard`, ClusterIP on port **7070**), owner-referenced to the campaign so it's
garbage-collected when the campaign is deleted. The URL is published to `status.dashboardURL`. Reach
it with a port-forward:

```bash
kubectl -n basquin-system get basquincampaign jpetstore-campaign -o jsonpath='{.status.dashboardURL}'
# http://jpetstore-campaign-dashboard.basquin-system.svc.cluster.local:7070

kubectl -n basquin-system port-forward svc/jpetstore-campaign-dashboard 7070:7070
# then open http://localhost:7070

# ...or let the CLI find the dashboard pod and forward it:
basquin dashboard -n basquin-system --campaign jpetstore-campaign
# Dashboard for "jpetstore-campaign" at http://localhost:7070  (Ctrl-C to stop)
```

The dashboard **outlives the run** — it's GC'd with the campaign, not with the driver Job, so results
stay viewable after the driver completes.

Two alternatives on `spec.dashboard`:

```yaml
dashboard:
  enabled: false                 # don't create a dashboard at all
```
```yaml
dashboard:
  externalPush: "basquin-dashboard.basquin-system.svc:7070"   # push to a shared, long-lived one
```

`externalPush` fans many campaigns into one dashboard for cross-campaign comparison. `enabled` is a
`*bool` deliberately, so `enabled: false` actually sticks (a plain bool would re-default to true).

> The no-auth ClusterIP posture is fine for single-tenant use — nothing is exposed outside the
> cluster without a port-forward or Ingress you add. See [CAMPAIGN-DESIGN.md §7 decision 3](CAMPAIGN-DESIGN.md#7-decisions).

## 7. Reading results

The driver writes a machine-readable summary the operator surfaces in campaign status — no dashboard
scraping needed:

```bash
kubectl -n basquin-system get basquincampaign
# NAME                 TARGET      PHASE       COVERAGE   FINDINGS   AGE
# jpetstore-campaign   jpetstore   Completed   23.1       19         5m

kubectl -n basquin-system get basquincampaign jpetstore-campaign -o yaml | less
# status.phase / coveragePct / findings / dashboardURL / driverJob / startTime / completionTime

# ...or the CLI, which renders targets + campaigns together (add --watch to follow):
basquin status -n basquin-system
# TARGET      DEPLOYMENT   PHASE      COVERAGE-ENDPOINT
# jpetstore   jpetstore    Injected   jpetstore-basquin-jacoco...:6300
#
# CAMPAIGN             TARGET      PHASE       COVERAGE   FINDINGS   DASHBOARD
# jpetstore-campaign   jpetstore   Completed   22.5       72         http://jpetstore-campaign-dashboard...:7070
```

Phase machine: **`Pending`** (target not yet `Injected`) → **`Provisioning`** (creating dashboard /
driver Job) → **`Running`** (driver Job active) → **`Completed`** (Job succeeded, summary read) or
**`Failed`** (driver failed, or the target went away mid-run). `status.driverJob` names the `Job` —
tail its logs to triage a run:

```bash
kubectl -n basquin-system logs job/$(kubectl -n basquin-system get basquincampaign \
  jpetstore-campaign -o jsonpath='{.status.driverJob}')
```

## 8. Editing a running campaign

The campaign spec is hashed onto the driver `Job`. Jobs are immutable, so **editing the spec triggers
a fresh run** — the operator deletes and recreates the driver Job with the new config. That's the
intended semantics for "I changed the test": change `iterations`, the grammar ConfigMap ref, or an
invariant, and re-apply, and a new run starts. A steady, unchanged campaign is a no-op reconcile.

## 9. Cleanup

Delete the CRs; owner references and finalizers do the rest.

```bash
# Delete the campaign FIRST — owner refs GC its driver Job + dashboard.
kubectl -n basquin-system delete basquincampaign jpetstore-campaign

# Then the target — a finalizer reverts the Deployment to its exact pre-injection state
# (initContainer/volume/env/port removed, jvmOptsVar restored) and GCs the coverage Service.
kubectl -n basquin-system delete basquintarget jpetstore
```

Delete the campaign before the target: the campaign's driver Job depends on the target's coverage
Service, so let it GC first. Deleting a campaign never un-instruments the app — targets are shared and
outlive campaigns.

## 10. Troubleshooting

- **Target stuck `Pending`, never `Injected`.** The operator observed the CR but hasn't injected.
  Check `--agents-image` is wired onto the controller and pullable; check the operator logs for RBAC
  `forbidden` errors (`kubectl -n <ns> logs deploy/basquin-controller-manager`); confirm
  `deploymentRef.name` matches a real Deployment in the *same* namespace, and that `container` names a
  real container when the pod has more than one.
- **No `status.coverageEndpoint`.** You didn't set `coverageService: true`, or coverage isn't enabled.
  The campaign needs this endpoint — with it absent, there's nothing to read coverage from.
- **Campaign stuck `Pending`.** The target isn't `Injected` yet (the campaign gates on it and requeues).
  Fix the target first; the campaign starts as soon as injection completes.
- **Campaign `Failed` with `InitContainerFailed` — "no .class files extracted".** The `verify-classes`
  initContainer found nothing at `driver.classesPath`, so the run fails loudly instead of reporting a
  misleading 0% coverage. The usual cause is a **war-only image**: it has no exploded `WEB-INF/classes`
  to copy (Tomcat only unpacks the WAR at runtime, so the files aren't in the image layers). The image
  must ship the classes as files — the e2e explodes `ROOT.war` into `webapps/ROOT/` for exactly this
  reason. Point `classesPath` at the real in-image location, or bake an exploded webapp. The reason and
  message are in `status.conditions` (`kubectl get basquincampaign -o yaml`), not only in pod logs.
- **Campaign `Failed` for other reasons.** Check `status.conditions[].reason`/`message`, then tail
  `job/<status.driverJob>` logs. Common causes: `baseURL` unreachable from the driver pod (wrong
  Service name/namespace/port → `DriverFailed`), or the target reverted/was deleted mid-run
  (`TargetGone`).
- **Grammar/corpus mismatch — value files reported missing.** The grammar's `@`-value-file basenames
  must exist as ConfigMap keys. Remember `--from-file` on a directory is non-recursive: include the
  `values/` subdir explicitly (§5), or the value keys won't be present in the mount.
- **RBAC `forbidden` in operator logs.** The install's Role is missing a grant, or the operator is
  running against the wrong namespace. The operator is namespaced-by-design — confirm `WATCH_NAMESPACE`
  is set (it refuses to start otherwise) and that the RoleBinding is in the namespace you're applying
  CRs into.
```
