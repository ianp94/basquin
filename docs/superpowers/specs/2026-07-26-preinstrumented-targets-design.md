# DD-044 — Pre-instrumented targets: driving a build-time-instrumented app through the operator

**Status:** design, not yet approved. Slots as **PR-3.5** in DD-043's ladder — after PR-3
(`basquin-maven-injector`, merged as #103 pending) and before PR-4 (coverage).

**Goal:** let the operator drive an application that is **already instrumented** — patching nothing —
so DD-043's build-time-injected Quarkus and GraalVM-native targets can be verified end-to-end in-cluster,
and so the operator's status stops claiming it instrumented an app it never touched.

---

## 1. The problem, and why the cheap version does not exist

DD-043 PR-3 ships `basquin-maven-injector`: instrumentation added at **build time**, with zero edits to
the target application's source. The resulting artifact — including a GraalVM native image — is
instrumented before the operator ever sees it. There is no agent to attach and nothing to inject; the
driver simply drives the app and reads `/__basquin/result`, the DD-040 channel PR-2's
`BasquinBoundaryFilter` serves in-process.

The obvious idea is to create a `BasquinTarget` with no agents enabled and let the operator no-op.
**That is not what happens.** `buildAgentArgs` appends `-javaagent:…basquin-agent.jar`,
`-Xbootclasspath/a:…` and `-Dbasquin.boundary=agent` **unconditionally**
(`operator/internal/controller/injection.go:108-115`); only `-agentpath` (threadTracker) and the JaCoCo
flags are gated by spec fields. So with every agent disabled the appended value is still non-empty,
`applyInjection` still adds the initContainer, volume and env var (`injection.go:228-304`), and the
target still reaches `Phase=Injected` / `Ready=True` (`basquintarget_controller.go:174-197`).

**"Inject nothing" is unrepresentable in the current API.** No spec field can express it
(`basquintarget_types.go`), and no test pins the all-agents-disabled case, so the behaviour is incidental
rather than intended.

Three consequences, in descending order of severity:

1. **`-Dbasquin.boundary=agent` is actively wrong for these targets.** The Quarkus extension *is* the
   boundary. Injecting a property that names a different boundary implementation into an app that already
   has one is a correctness hazard, not merely redundant — and on a native image the `-javaagent` beside
   it is silently ignored (**inferred**: SubstrateVM has no JVMTI agent attach; not measured here — see
   §8.1).

   **What that flag is for, and why skipping it loses nothing here.** `injection.go:111-114` records that
   `-Dbasquin.boundary=agent` is precisely how the *operator* path obtains its server-side
   heap/thread/latency oracle and its DD-029 `/__basquin` control surface — the agent's boundary is
   default-off so the bench path (valve + agent) stays single-boundary, and the operator opts in because
   it mounts no valve. A pre-instrumented Quarkus target gets both from the extension instead: PR-2's
   `BasquinBoundaryFilter` is the boundary, and `BasquinControlHandler` serves the control surface. So
   this mode does not forgo a capability — it declines a *second, conflicting* provider of one the app
   already has. That is also why "just disable the agents" is the wrong framing: the flag is not an agent,
   it is a boundary selector.
2. **The status would lie.** `Phase=Injected` with `Reason=Injected` and a message saying "all N
   replica(s) instrumented" would be reported for an application the operator did not modify. This is the
   silently-wrong-signal class DD-043 §1.1 exists to prevent, and it is surfaced where humans and CI read
   it — kubectl printer columns, the condition, `basquin status` (`cmd/basquin/status.go:86`) and
   `basquin instrument`'s wait (`instrument.go:167-196`).
3. **A verification script resting on this would encode the lie as its expected output.** That is the
   reason this spec exists rather than a script.

---

## 2. Design — one new spec field, one new phase, one relaxed gate

### 2.1 API

Add to `BasquinTargetSpec` (`operator/api/v1alpha1/basquintarget_types.go`):

```go
// PreInstrumented declares that the target's image already carries Basquin's instrumentation —
// as produced by basquin-maven-injector at build time (DD-043 §5). The operator then OBSERVES the
// Deployment and never mutates its pod template: no initContainer, no volume, no JVM-opts append.
//
// This is not "disable the agents". Disabling every agent still injects (injection.go:108-115 appends
// -javaagent, -Xbootclasspath/a and -Dbasquin.boundary=agent unconditionally), and
// -Dbasquin.boundary=agent would name a different boundary than the one the app already has.
//
// +optional
PreInstrumented bool `json:"preInstrumented,omitempty"`
```

**Why a distinct boolean rather than an `agents: none` sentinel.** The reader of a CR must be able to see
that no mutation will occur. A sentinel encodes it as the absence of something, which is exactly how the
current behaviour became incidental. A CEL rule rejects the contradiction: `preInstrumented: true`
together with any enabled agent is invalid at admission, so the conflict cannot reach the reconciler.

### 2.2 Phase and condition vocabulary

Add `PhaseObserved` alongside the existing `Pending`/`Injecting`/`Injected`/`Error`. For a
pre-instrumented target the reconciler sets:

- `Phase: Observed`
- `Ready: True`, `Reason: PreInstrumented`
- Message: `"N/N replica(s) ready; instrumentation is build-time (operator did not modify the pod template)"`

The message states what the operator did **not** do, because that is the fact a reader most needs and the
one the old vocabulary obscured.

**Readiness uses `ReadyReplicas`, not `UpdatedReplicas`.** The existing injected path flips on
`UpdatedReplicas >= desired` (`basquintarget_controller.go:180-187`), which reports success once the new
pod template has rolled out regardless of whether the pods actually came up. For an observe-only target
there is no rollout to track, so the only honest signal is readiness. (The injected path's choice is
**out of scope** here — noted in §7.1 rather than bundled.)

### 2.3 Campaign gate

`basquincampaign_controller.go:108-118` compares `Phase == PhaseInjected` exactly, so a new phase fails
it. Relax to accept `PhaseInjected` **or** `PhaseObserved`. `TargetGone` handling (dropping out of the
accepted set mid-run) must treat both the same way.

`targetAppImage` (`:421`) needs no change — a pre-instrumented target has a readable image like any other.

---

## 3. Scope boundary: load mode only

Explore mode is structurally JVM-and-JaCoCo-shaped: the campaign gates on a coverage endpoint
(`basquincampaign_controller.go:120-123`) and its resources extract and verify classes
(`campaign_resources.go:171-198`). Coverage for build-time-instrumented targets is **PR-4's** work, behind
§8.2's still-unmeasured plugin-execution gate.

**Therefore: `preInstrumented: true` accepts `mode: load` and rejects `mode: explore`**, by CEL rule, with
a message pointing at PR-4. Load mode already fits (`campaign_resources.go:130,172`).

Rejecting explicitly is the point. Allowing it would produce a campaign that starts, finds no coverage
endpoint, and fails in a way that looks like a bug in the target rather than an unimplemented combination.

---

## 4. Result-channel addressing

The driver reaches `/__basquin/result` per pod, and — a genuine surprise from the code — **the coverage
Service doubles as the driver's pod-IP source** (`PodPollTargets.java:68-70`). So it is not redundant for
a pre-instrumented target; it is how the driver enumerates pods.

- **Single replica:** the campaign's `baseURL` suffices; nothing new.
- **Multi-replica:** pod-direct polling needs `-Dbasquin.report.podHost` or a headless Service
  (`PodPollTargets.java:59-79,:114-161`).

**Decision:** PR-3.5 supports **single-replica** pre-instrumented targets, and the reconciler emits a
`Warning`-level condition if a pre-instrumented target has `replicas > 1`. Multi-replica is a follow-on.
The operator currently emits **no Kubernetes Events at all** (there is no `EventRecorder`), so this is a
condition rather than an event; adding an `EventRecorder` is out of scope.

---

## 5. The in-cluster verification script

`deploy/e2e/e2e.sh` (553 lines) is the model. Its stages that are **runtime-injection-specific** — 5, 8,
10–13 — are replaced or dropped for this path:

| Stage | Runtime path | Pre-instrumented path |
|---|---|---|
| Build images | agents, operator, runner, dashboard | **plus** the injected native app image, built via `basquin-maven-injector` |
| Deploy RAW app | app with `CATALINA_OPTS` preset, to prove append-not-replace | deploy the **already-instrumented** image |
| Apply CR | `BasquinTarget` → wait for injection to roll out | `BasquinTarget{preInstrumented: true}` → wait for `Phase=Observed` |
| **Assert instrumentation** | pod template contains `-javaagent`, initContainer, volume | **the pod template is byte-identical to what was applied** — the operator changed nothing |
| Assert the channel | agent's channel | `curl` with `X-Basquin-Req`, then poll `/__basquin/result`, expect a cost line not `miss` |

**The strongest assertion is the negative one:** capture the Deployment's pod-template hash before
applying the CR and after `Phase=Observed`, and require them equal. "Did not modify" is the claim; a hash
comparison is its evidence, and it fails loudly if a future change reintroduces the patch.

**Do not reuse e2e stage 12's route checks.** PR-2's `BasquinControlHandler` serves only
`result`, `violations` and `control/defect/*` (`BasquinControlHandler.java:151-161`) — there is no `mode`
or `drift` route, so the DD-030/DD-035 assertions would fail against a Quarkus target for reasons
unrelated to this feature. Whether the extension should grow them is a separate question (§7.2).

---

## 6. Acceptance

1. A `BasquinTarget{preInstrumented: true}` over a build-time-instrumented **native** Quarkus Deployment
   reaches `Phase=Observed` / `Ready=True`, and the Deployment's pod-template hash is **unchanged**.
2. A `BasquinCampaign{mode: load, targetRef: <that target>}` runs to completion and its findings come
   from `/__basquin/result`.
3. `preInstrumented: true` with any agent enabled is **rejected at admission**.
4. `preInstrumented: true` with `mode: explore` is **rejected**, with a message naming PR-4.
5. The existing runtime path is untouched: every current
   `basquintarget_controller_test.go` assertion still passes, including the `-Dbasquin.boundary=agent`
   contract (`:148-153`), idempotency (`:163-177`) and exact revert (`:179-206`).
6. A test pins the **all-agents-disabled, not pre-instrumented** case to today's behaviour (it still
   injects), so the distinction between the two is asserted rather than assumed.

---

## 7. Deliberately out of scope

1. **`UpdatedReplicas` vs `ReadyReplicas` on the injected path.** Reporting "instrumented" once the
   template rolled out, before pods are ready, is arguably wrong, but changing it would alter behaviour
   every existing test and the e2e depend on. Noted, not bundled.
2. **`mode`/`drift` routes in the Quarkus extension.** Their absence narrows what the control surface can
   assert versus the Tomcat valve. A real gap; not this PR's.
3. **Multi-replica pre-instrumented targets** (§4).
4. **Coverage for pre-instrumented targets** — PR-4, gated on §8.2.
5. **Dead code the investigation surfaced**: `agents.valve` is read nowhere
   (`basquintarget_types.go:68-70`) and `PhaseReverting` is an unused enum value. Removing them is
   correct but unrelated; separate cleanup.
6. **An `EventRecorder`** (§4).

---

## 8. Open questions

### 8.1 Does a native image actually ignore an injected `-javaagent`, or fail to start?

**Unverified.** §1's consequence 1 assumes SubstrateVM silently ignores `JAVA_TOOL_OPTIONS`-supplied
`-javaagent`. If it instead **fails to start**, the current operator would hard-break any native target
the moment a `BasquinTarget` was applied — which would make this spec more urgent, not less, and worth
stating in `THIRD-PARTY-APPS.md` as a hazard. Settle it with a one-off run: apply
`JAVA_TOOL_OPTIONS=-javaagent:/nonexistent.jar` to the native binary PR-3 already built
(`bench-results/dd043-pr3-native-2026-07-26/`) and record what happens. Cheap, and it decides a claim this
spec currently labels inferred.

### 8.2 Should `preInstrumented` be inferred rather than declared?

The operator could in principle detect instrumentation by probing `/__basquin/result` before deciding to
patch. Rejected for now: a probe that fails for an unrelated reason (app still starting, network policy)
would silently fall back to patching an already-instrumented app — the exact failure this spec is written
to prevent. Declaration is auditable; inference is not. Recorded because it will be proposed.
