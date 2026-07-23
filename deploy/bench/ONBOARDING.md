# Onboarding an app as a Basquin benchmark target

The operator's advertised flow is two steps — **instrument** a Deployment (`BasquinTarget`), then **run a campaign** (`BasquinCampaign`). Benchmarking a *new* app against other tools needs more than that: you have to get the unmodified app deployed in the cluster, give it a request grammar so exploration has a reachable surface, and stand up the load-tool baselines. This guide is the full, reproducible process, grounded in the two working targets (`jpetstore/`, `jspwiki/`).

> **Why more than "operator-initiated"?** The operator instruments an app you already run. A *benchmark* target is one you must first **package, deploy, and teach** (grammar) — plus the comparison harness (k6/Locust, the A/A′/B corpora). Steps 1–4 are the deploy; 5 is the operator's own flow; 6–9 are the benchmark battery.

---

## Prerequisites (one-time)

- **A Kubernetes cluster with the Basquin operator installed — from the published images.** Follow
  the [README quickstart](../../README.md#quick-start-kubernetes): `helm repo add basquin
  https://ianp94.github.io/basquin/charts` then `helm install basquin basquin/basquin-operator …`.
  The chart pulls the **released multi-arch `ghcr.io/ianp94/basquin-{operator,agents,runner,dashboard}`
  images** at its `appVersion` (0.3.0) — **nothing to build or repackage.** (For a throwaway local
  cluster, `kind create cluster` first.)
- **The app-under-test** is either an app you already run (the operator instruments it in place) or,
  for a *reproducible* bench target, packaged as a `raw` image in step 2 — that's *your app*, not
  Basquin.
- *(Contributors only.)* If you're iterating on Basquin's **own** source in a from-source local
  cluster (`deploy/e2e/e2e.sh`), rebuild the changed image after edits and `kind load` it, e.g.
  `deploy/runner-image/build.sh 0.3.0 basquin` — otherwise the cluster keeps running stale code (a
  stale runner crashes on newer corpus/grammar features). **Users on the published images never need
  this.**
- `KUBECONFIG=…`, `K="kubectl …"`, `NS=basquin-system`.

## 1. Prepare the app artifact

Get the unmodified WAR and, if it uses a filesystem/content store, seed it. See each app's
`setup.sh` (e.g. `jspwiki/setup.sh` explodes the WAR into `webapp/` and seeds 70 content-rich pages).
The app is deployed **unmodified** — Basquin instruments it at deploy time.

## 2. Build a `raw` image

A `raw` image is the app on Tomcat with **no agents** (the operator adds them). Mirror the
jpetstore-raw pattern (`deploy/e2e/e2e.sh:130-144`):

```dockerfile
FROM tomcat:9.0-jdk17-temurin
COPY webapp/ /usr/local/tomcat/webapps/ROOT/     # exploded, NOT a war (see below)
# ...bake any content store the app reads (jspwiki: COPY pages/ /var/jspwiki/pages/)
```

Two requirements that bite:

- **Explode the WAR into `ROOT/`, don't ship `ROOT.war`.** Tomcat serving the exploded dir is
  incidental; the real reason is the campaign's coverage initContainer copies `WEB-INF/classes`
  **out of this image** — those `.class` files must exist as files, which a war-only image doesn't
  provide until runtime.
- **If the app ships its classes in `WEB-INF/lib/*.jar` (not `WEB-INF/classes`)** — JSPWiki does,
  most Spring/library-heavy apps do — the coverage initContainer finds nothing and the campaign's
  `verify-classes` fails with *"no .class files extracted."* Extract them in the image into a
  dedicated (non-classpath) dir and point `classesPath` there:

  ```dockerfile
  RUN mkdir -p /basquin-app-classes && cd /basquin-app-classes \
   && for j in /usr/local/tomcat/webapps/ROOT/WEB-INF/lib/<app>-*.jar; do jar xf "$j" 2>/dev/null || true; done
  ```

Build and load: `docker build -t basquin/<app>-raw:0.3.0 . && kind load docker-image basquin/<app>-raw:0.3.0 --name basquin`.

## 3. Deploy the app + a Service

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: <app>, namespace: basquin-system, labels: { app: <app>-raw } }
spec:
  replicas: 1
  selector: { matchLabels: { app: <app>-raw } }
  template:
    metadata: { labels: { app: <app>-raw } }
    spec:
      containers:
        - name: <app>
          image: basquin/<app>-raw:0.3.0
          imagePullPolicy: IfNotPresent
          ports: [{ containerPort: 8080 }]
          env: [{ name: CATALINA_OPTS, value: "-Xmx2g" }]   # right-size! (see gotcha)
          readinessProbe:                                   # NO query string in path
            httpGet: { path: /Wiki.jsp, port: 8080 }
            initialDelaySeconds: 20
            failureThreshold: 40
---
apiVersion: v1
kind: Service
metadata: { name: <app>-app, namespace: basquin-system }
spec:
  selector: { app: <app>-raw }
  ports: [{ port: 8080, targetPort: 8080 }]
```

**Heap sizing is not optional.** At `-Xmx512m` an instrumented app GC-thrashes under c=50 and the
drift poll times out. Since DD-040 that surfaces as `driftUnavailable: true` with `heapDriftKb`
absent (it used to be a silent `heapDriftKb:0`), but the run has still lost the measurement. Use
`-Xmx2g`.

**Set `invariants.latencyMaxMs` on the `BasquinTarget`, or nothing checks latency.** Under load the
target's valve is in lock-free passthrough and evaluates nothing, so the *driver* is the only
evaluator — and it needs the threshold. The campaign now inherits it from the target automatically
(DD-040), but if neither the target nor `spec.driver.invariants` sets one, the summary reports
`violations.notEvaluated: [latency, heap, thread]` and the Ready condition says "not evaluated".
That is the honest answer, not a passing run: `violations.latency: 0` on an unconfigured run used to
read as a clean result at a p50 of 503 ms against an intended 250 ms budget.

## 4. Instrument with a `BasquinTarget`

```yaml
apiVersion: basquin.dev/v1alpha1
kind: BasquinTarget
metadata: { name: <app>, namespace: basquin-system }
spec:
  deploymentRef: { name: <app> }
  container: <app>
  jvmOptsVar: CATALINA_OPTS
  coverageService: true
  agents:
    threadTracker: true
    valve: true
    coverage: { enabled: true, includes: "org.<app>.*", port: 6300 }
  invariants: { mode: soft, latencyMaxMs: 25, heapDeltaMaxKb: 512 }   # tune to the app's steady-state
```

Wait for `status.phase == Injected` and the pod to re-roll. Verify the boundary took:
`kubectl logs <pod> | grep "agent boundary installed"` and
`kubectl exec <pod> -- curl -s localhost:8080/__basquin/drift` (returns `heapKb,threads,ts`).

## 5. Author a grammar + seed corpus (the real per-app cost, ~half-day)

The grammar is the reachable surface as data (DD-016/018). Model on `examples/grammar/jpetstore.grammar`:
`$name = @valuesFile | ~pattern | literal | <generator>` for value spaces, `/path?x=${name}` for
routes, `METHOD /path body` for writes, `@sequence` for multi-step transactions. **Aim the grammar
at the app's expensive/stateful paths** — for JSPWiki that's `Search.jsp` (~173ms), `Diff.jsp`
(~164ms), `Edit.jsp` render (~157ms) vs a warm page view (~9ms); probe a few routes with `curl -w
%{time_total}` first to find them. Put value files under `examples/corpus/<app>/values/`.

Create the configmaps (exactly as `e2e.sh:325-333`):

```bash
$K -n $NS create configmap <app>-grammar --from-file=<app>.grammar=examples/grammar/<app>.grammar
$K -n $NS create configmap <app>-corpus --from-file=examples/corpus/<app>/ --from-file=examples/corpus/<app>/values/
```

## 6. Explore → the fuzz corpus (arm B)

```yaml
apiVersion: basquin.dev/v1alpha1
kind: BasquinCampaign
metadata: { name: <app>-armb-explore, namespace: basquin-system }
spec:
  mode: explore
  targetRef: { name: <app> }
  baseURL: http://<app>-app.basquin-system.svc.cluster.local:8080
  driver:
    grammarConfigMap: <app>-grammar
    corpusConfigMap: <app>-corpus
    classesPath: /basquin-app-classes    # or WEB-INF/classes if the app has real ones there
    duration: 30m                        # duration, NOT iterations (durations compare; counts don't)
```

The emitted replay corpus is `status.corpusConfigMap` (`<app>-armb-explore-corpus-out`) — cost-ranked,
carrying the method/sequence-aware entries the fuzzer found expensive.

### Reconcile the reported violation count against the target's own log — every campaign

**Do this before you believe any explore result.** The target evaluates invariants and logs every one
of them; the driver reports the ones that reached it. Those two numbers should be close. When they
are not, the reporting channel is broken and the campaign's finding count is fiction — this is the
check that would have caught DD-040's header loss on day one instead of after three published
benchmark runs.

```bash
# what the target evaluated, inside the campaign's window
$K -n $NS logs <app-pod> --since-time=<campaign start> | grep -c '\[Basquin\]\[Invariant\]'

# what the driver reported
$K -n $NS get pod <driver-pod> \
  -o jsonpath='{.status.containerStatuses[0].state.terminated.message}'   # findInvariant / targetViolations
```

Read them together:

- **Roughly equal** — the channel is healthy. (Expect the pod count to be slightly *higher*: the
  kubelet readiness probe violates `heapDelta` on some apps every few seconds, ~12/min on JSPWiki
  even at idle, and probe traffic is not driver traffic.)
- **A large gap** (the pod logged thousands, the driver reported ~0) — the channel is broken. Since
  DD-040 the driver also publishes `reportMisses` and `targetViolations` in the summary and marks the
  run `findingsLowerBound: true`, so check those first; a miss majority now fails the run outright
  rather than completing "clean". `curl -s localhost:8080/__basquin/violations` in the pod gives the
  target's own cumulative total for a live cross-check.
- **The driver reported more** — you are double-counting; the header path and the result-store poll
  are alternatives, never a sum.

## 7. Corpus arms A and A′

- **A (happy path)** — the routes a k6/Gatling user would script by hand (e.g. the catalog GETs).
- **A′ (steelman)** — A plus a hand-written *valid* multi-step journey (login → … → checkout) as
  TAB-separated method-aware sequences. This is the fair comparand.

Create `<app>-corpus-a` / `<app>-corpus-aprime` configmaps (`--from-file=corpus.txt=<file>`).
**k8s names must be lowercase** — `corpus-A` is silently rejected and the run becomes a no-op.

## 8. Run the load battery (A / A′ / B)

For each arm: restart the target (fresh state), confirm it's in load mode
(`curl -X POST '.../__basquin/mode?to=load&ttlMs=900000'`), launch a load campaign
(`mode: load, corpusConfigMap: <arm>, concurrency: 50, warmup: 30s, duration: 10m`), and collect.

**Collect from the driver's termination summary, not the live dashboard** — it's authoritative and
survives teardown:

```bash
$K -n $NS get pod <driver-pod> \
  -o jsonpath='{.status.containerStatuses[0].state.terminated.message}'   # the load-block JSON
```

(The live dashboard `/api/campaign/<id>/status` also works but you must re-resolve the dashboard
pod+token each sample. The load block's `iters` is the explore counter — always 0 in load mode; the
real metrics are `throughputRps`, `latencyMs`, `serverErrors`, `heapDriftKb`, `threadDrift`.)

## 9. Load-tool baselines (the other axis)

Run k6 (`deploy/bench/k6/<app>.js`) and Locust (`deploy/bench/locust/locustfile.py`) against the same
target, same routes, same concurrency, target in load mode — so the comparison measures the load
generator, not boundary state. See [`BENCHMARKS.md`](../../docs/BENCHMARKS.md) for the method.

---

## Gotchas checklist

- [ ] *(Contributors on a from-source cluster only)* rebuild + `kind load` the changed Basquin image — users on the published `ghcr.io` images skip this.
- [ ] Classes in jars → extract into `/basquin-app-classes`, set `classesPath`.
- [ ] Target heap `-Xmx2g` — 512m GC-thrashes and starves the drift poll.
- [ ] All k8s object/configmap names **lowercase**.
- [ ] Readiness probe path has **no query string**.
- [ ] Collect from the driver **termination summary**; `iters` is not the load metric.
- [ ] After every explore campaign, reconcile the reported violation count against the target pod's `[Basquin][Invariant]` line count — a large gap means the reporting channel is broken, not that the app is clean.
- [ ] Fresh target restart between arms; reset any filesystem store the app writes.
