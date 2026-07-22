# Onboarding an app as a Basquin benchmark target

The operator's advertised flow is two steps — **instrument** a Deployment (`BasquinTarget`), then **run a campaign** (`BasquinCampaign`). Benchmarking a *new* app against other tools needs more than that: you have to get the unmodified app deployed in the cluster, give it a request grammar so exploration has a reachable surface, and stand up the load-tool baselines. This guide is the full, reproducible process, grounded in the two working targets (`jpetstore/`, `jspwiki/`).

> **Why more than "operator-initiated"?** The operator instruments an app you already run. A *benchmark* target is one you must first **package, deploy, and teach** (grammar) — plus the comparison harness (k6/Locust, the A/A′/B corpora). Steps 1–4 are the deploy; 5 is the operator's own flow; 6–9 are the benchmark battery.

---

## Prerequisites (one-time)

- A kind cluster with the operator installed and the four images loaded. The canonical setup is
  [`deploy/e2e/e2e.sh`](../e2e/e2e.sh) (run it once, or `--no-teardown` to leave the cluster up).
- **Keep the cluster images current.** They are tagged `0.3.0` but built from source; if the cluster
  has been up across code changes, rebuild the driver so it runs current code:
  `deploy/runner-image/build.sh 0.3.0 basquin`. A stale runner will crash on new corpus/grammar
  features (e.g. DD-035 method/sequence entries) — the symptom is a driver `IndexOutOfBounds` at
  startup.
- `KUBECONFIG=/tmp/kc-basquin`, `K="kubectl --context kind-basquin"`, `NS=basquin-system`.

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
drift poll times out — the run reports a silent `heapDriftKb:0`. Use `-Xmx2g`.

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

- [ ] Runner image current (rebuild if the cluster is stale) — else driver crashes on new features.
- [ ] Classes in jars → extract into `/basquin-app-classes`, set `classesPath`.
- [ ] Target heap `-Xmx2g` — 512m GC-thrashes and starves the drift poll.
- [ ] All k8s object/configmap names **lowercase**.
- [ ] Readiness probe path has **no query string**.
- [ ] Collect from the driver **termination summary**; `iters` is not the load metric.
- [ ] Fresh target restart between arms; reset any filesystem store the app writes.
