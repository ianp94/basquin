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

1. **`-Dbasquin.boundary=agent` is wrong for a JVM-mode pre-instrumented target — and MEASURED inert on
   a native one.** An earlier draft called it "actively wrong for these targets" without distinguishing the
   two, which was too strong for native and imprecise about where the hazard lives.

   **Measured** (`bench-results/dd044-native-jto-2026-07-26/`): with the exact string the operator would
   inject, the native binary starts, serves `/ok` → 200, still reports `basquin` in its banner, and logs no
   complaint. `basquin.boundary` is read only by `agent/BoundaryInstaller.java`, `agent/Agent.java` and
   `agent/TomcatBoundaryAdvice.java`; neither `basquin-quarkus` nor `basquin-core` reads it, and on a native
   image the agent that would is never loaded. So native targets are **not** broken by the current
   operator.

   **The hazard is real for a JVM-mode build-time-instrumented target**, which is the case the earlier
   wording conflated with native. There `-javaagent` does load the agent, the agent does read
   `basquin.boundary=agent`, and the request path ends up with **two** boundaries — the extension's filter
   and the agent's ByteBuddy-installed one. That is the double-instrumentation conflict this mode must
   prevent. (Reasoned from which code reads the property; not measured end-to-end — see §8.1.)

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
current behaviour became incidental.

**`preInstrumented: true` makes the `agents` block ignored, and there is deliberately NO CEL rule
rejecting the combination.** An earlier draft of this spec proposed one — "`preInstrumented: true` together
with any enabled agent is invalid at admission" — and it would have made the field **impossible to use**.
`AgentsSpec.Valve` is a plain `bool` with `+kubebuilder:default=true`
(`basquintarget_types.go:68-70`), so it is always true after admission and `valve: false` marshals as unset
and gets re-defaulted — precisely the trap `ThreadTracker`'s own comment documents and avoids by being a
`*bool` (`:61-66`). `ThreadTracker` likewise defaults true. So "any enabled agent" is *always* satisfied,
and the rule would reject every possible spelling of a pre-instrumented target.

Rather than work around that with a `*bool` migration of `Valve` — a change to the runtime path's API,
which this PR should not touch — `preInstrumented: true` simply means the reconciler does not read
`agents` at all. The field comment must say so, because an operator setting both will otherwise expect the
agents to matter. (`agents.valve` is in any case read nowhere in the shipped reconciler — see §7.5.)

### 2.1a The phase enum marker — a step whose omission breaks everything silently

`TargetPhase` carries `+kubebuilder:validation:Enum=Pending;Injecting;Injected;Reverting;Error`
(`basquintarget_types.go:130`). Adding Go constants **alone is not enough**: the API server would reject
every status write carrying a new phase, so the target would never leave its previous phase and the failure
would surface as a stuck reconcile rather than as a validation error in the obvious place.

The marker must gain both new values and the CRD manifests must be regenerated (`make manifests`), with
the regenerated `operator/config/crd/bases/basquin.dev_basquintargets.yaml` committed.

### 2.2 Phase and condition vocabulary — and the two states that are NOT the happy path

Add **two** phases, `Observing` and `Observed`. One is not enough, and an earlier draft of this spec
specified only the terminal state — which left two windows in which the operator would emit exactly the
dishonest signal this whole design exists to prevent.

**Terminal state — `Observed`:**
- `Phase: Observed`
- `Ready: True`, `Reason: PreInstrumented`
- Message: `"N/N replica(s) ready; instrumentation is build-time (operator did not modify the pod template)"`

The message states what the operator did **not** do, because that is the fact a reader most needs and the
one the old vocabulary obscured.

**Readiness uses `ReadyReplicas`, not `UpdatedReplicas`.** The injected path flips on
`UpdatedReplicas >= desired` (`basquintarget_controller.go:180-187`), which reports success once the pod
template has rolled out regardless of whether pods came up. For an observe-only target there is no rollout
to track, so readiness is the only honest signal. (The injected path's choice is **out of scope** — §7.1.)

#### 2.2a The pre-ready window

Until `ReadyReplicas >= desired` the target is `Observing`, with `Ready: False`,
`Reason: WaitingForReplicas`. **`Observed` must never be minted on first sight of the Deployment.**

This matters because the campaign gate keys on phase alone: a target that flipped straight to `Observed`
would satisfy it while zero pods were serving, and the driver Job would launch against nothing. The
existing 5-second requeue (`basquintarget_controller.go:200-202`) covers the injected path's rollout wait
and must be extended to cover the observe wait, or `Observing` never advances without an unrelated event.

**The campaign gate accepts `Observed` only — never `Observing`** (§2.3).

#### 2.2b The `false → true` transition on an already-injected target

If `preInstrumented` is flipped to `true` on a target the operator has **already injected**, the pod
template still carries the initContainer, volume and JVM-opts append. Simply switching to `Observed` would
report *"operator did not modify the pod template"* over a template the operator demonstrably did modify —
the precise lie this spec exists to eliminate, arriving through the spec's own new code path.

**The reconciler must revert before observing.** `revertInjection` (`injection.go:309`) already exists and
its exactness is pinned by test (`basquintarget_controller_test.go:179-206`), so this reuses tested code
rather than adding a second removal path. Sequence:

1. Detect our injection is present on a `preInstrumented: true` target.
2. `Phase: Reverting` — the enum value exists today and is currently dead (§7.5), so this gives it a real
   use rather than adding a third new phase.
3. Once the template is clean, proceed to `Observing`, then `Observed` on readiness.

Only after the revert has landed may the `Observed` message's claim be made, because only then is it true.

**Alternative considered and rejected:** a CEL rule making `preInstrumented` immutable after creation.
That prevents the lie but forces a delete-and-recreate to adopt an existing target, and — worse — it would
leave a stale injection on a Deployment the user believes they have detached. Reverting is strictly more
honest.

### 2.3 Campaign gate

`basquincampaign_controller.go:108-118` compares `Phase == PhaseInjected` exactly, so a new phase fails
it. Relax to accept `PhaseInjected` **or** `PhaseObserved` — and **not** `Observing` or `Reverting`, which
are the in-flight states of §2.2a and §2.2b and must keep the campaign `Pending`.

`TargetGone` handling (dropping out of the accepted set mid-run) must treat the accepted set as a set: a
target leaving `{Injected, Observed}` fails a Running campaign, exactly as leaving `Injected` does today.
Note this changes the message text `"dropped out of Injected (now %q)"` (`:114`), which
`basquincampaign_controller_test.go` asserts — see §6.5.

`targetAppImage` (`:421`) needs no change — a pre-instrumented target has a readable image like any other.

---

## 3. Scope boundary: load mode only

Explore mode is structurally JVM-and-JaCoCo-shaped: the campaign gates on a coverage endpoint
(`basquincampaign_controller.go:120-123`) and its resources extract and verify classes
(`campaign_resources.go:171-198`). Coverage for build-time-instrumented targets is **PR-4's** work, behind
§8.2's still-unmeasured plugin-execution gate.

**Therefore: a campaign over a pre-instrumented target accepts `mode: load` and rejects `mode: explore`,
with a message pointing at PR-4.** Load mode already fits (`campaign_resources.go:130,172`).

**This cannot be a CEL rule, and an earlier draft of this spec wrongly said it could.** CEL validation is
scoped to a single resource, and the two fields live on *different* ones: `mode` on `BasquinCampaign`,
`preInstrumented` on `BasquinTarget`. No admission-time rule can see both.

It must therefore be a **campaign-controller gate**: after the controller has fetched the target (it
already does, `:94-104`), reject `Spec.Mode == "explore"` when `target.Spec.PreInstrumented` is true, by
failing the campaign terminally with a named reason (`ExploreUnsupportedForPreInstrumented`) rather than
leaving it Pending forever. A Pending campaign with no explanation is the failure shape this rejection
exists to avoid.

Rejecting explicitly is the point. Allowing it would produce a campaign that starts, finds no coverage
endpoint, and fails in a way that looks like a bug in the target rather than an unimplemented combination.

---

## 4. Result-channel addressing

The coverage Service *can* act as the driver's pod-IP source for result polls
(`PodPollTargets.java:68-70`) — but **not in this feature's configuration**, and an earlier draft of this
spec wrongly presented that as load-bearing here. A pre-instrumented target in **load** mode creates no
coverage Service and passes no JaCoCo flag, so the campaign's **`baseURL` is the source**. The Service's
dual role matters for the explore path, which §3 defers to PR-4.

- **Single replica:** the campaign's `baseURL` suffices; nothing new is required.
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

**The strongest assertion is the negative one:** capture the Deployment before applying the CR and after
`Phase=Observed`, and require it unchanged. "Did not modify" is the claim, and an equality check is its
evidence — it fails loudly if a future change reintroduces the patch.

**A pod-template hash alone is not sufficient**, because the operator can annotate the Deployment's own
metadata without touching the pod template. Assert all three:

1. the pod template is unchanged (spec-hash equality),
2. `metadata.generation` is unchanged — any write to the Deployment spec bumps it, so this catches
   mutations a template hash would miss,
3. no `basquin.dev/*` annotation or label has appeared on the Deployment metadata.

**Do not reuse the `/__basquin/drift` and `/__basquin/mode` route checks** (`deploy/e2e/e2e.sh:~397-408`,
in the later assert stages — *not* stage 12, as an earlier draft of this spec said). PR-2's
`BasquinControlHandler` serves only `result`, `violations` and `control/defect/*`
(`BasquinControlHandler.java:151-161`), so those DD-030/DD-035 assertions would fail against a Quarkus
target for reasons unrelated to this feature. Whether the extension should grow them is separate (§7.2).

---

## 6. Acceptance

1. A `BasquinTarget{preInstrumented: true}` over a build-time-instrumented **native** Quarkus Deployment
   reaches `Phase=Observed` / `Ready=True`, and the Deployment is **unmodified** by the checks in §5.
2. A `BasquinCampaign{mode: load, targetRef: <that target>}` runs to completion and its findings come
   from `/__basquin/result`.
3. **The pre-ready window (§2.2a):** with `ReadyReplicas < desired`, the target sits in `Observing` with
   `Ready: False`, and a campaign referencing it stays **Pending** — it does not launch a driver. Assert
   the campaign's phase, not merely the target's, because launching against zero pods is the failure this
   guards.
4. **The `false → true` transition (§2.2b):** flipping `preInstrumented` to `true` on an
   **already-injected** target reverts the injection first — the pod template returns to its
   pre-injection state — and only then reaches `Observed`. Assert that no state exists in which the
   `Observed` message's "did not modify the pod template" claim coexists with a template still carrying
   the initContainer, volume or JVM-opts append. **This is the acceptance item that pins the spec's own
   honesty claim against its own new code path.**
5. `mode: explore` against a pre-instrumented target is **rejected terminally** by the campaign
   controller (not left Pending), with a reason naming PR-4.
6. The existing runtime path is untouched: every current `basquintarget_controller_test.go` assertion
   still passes, including the `-Dbasquin.boundary=agent` contract (`:148-153`), idempotency
   (`:163-177`) and exact revert (`:179-206`). Where `basquincampaign_controller_test.go` asserts the
   `"dropped out of Injected"` message text (§2.3), that expectation is updated — a deliberate,
   reviewed change, not an incidental one.
7. A test pins the **all-agents-disabled, not pre-instrumented** case to today's behaviour (it still
   injects), so the distinction between the two is asserted rather than assumed. Today nothing pins it.
8. The regenerated CRD manifest is committed and its `phase` enum contains the new values (§2.1a) —
   without this every status write is rejected by the API server.

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
7. **The CLI's `--wait` semantics.** `basquin instrument --wait` blocks until `Injected`
   (`cmd/basquin/instrument.go:167-196`) and `basquin status` prints the raw phase (`status.go:86`).
   Against a pre-instrumented target the wait would **hang forever**, since that target never
   reaches `Injected`. This is NOT out of scope in the sense the others are — it is a real break
   this feature introduces, and it must be handled: the wait accepts the `{Injected, Observed}` set
   for symmetry with the campaign gate (§2.3). Listed here because an earlier draft of this spec
   dropped the CLI touch point entirely, which would have shipped a hang.

---

## 8. Open questions

### 8.1 Does a native image actually ignore an injected `-javaagent`, or fail to start? — **RESOLVED: it ignores them**

**Resolved 2026-07-26 — measured, evidence `bench-results/dd044-native-jto-2026-07-26/` (see that
directory's `rerun.md`, not the first attempt: PR #103's approver correctly found the original artifacts
could not support the claim — no proof the env var applied, and `-agentpath` untested despite
`threadTracker` defaulting to true).** The native
binary starts, serves, and stays instrumented; the flags are inert on SubstrateVM, so the current operator
does **not** hard-break native targets. §1's consequence 1 has been narrowed accordingly, and the primary
motivation for this feature is now (a) double instrumentation on **JVM-mode** pre-instrumented targets and
(b) status dishonesty — neither affected by this result.

**Still open, narrower:** the JVM-mode double-boundary conflict is reasoned from which code reads
`basquin.boundary`, not measured end-to-end; and the test used an agent jar path that does not exist, so
"SubstrateVM ignores a *present* agent jar" remains inference (it has no JVMTI attach, so the outcome
should not differ).

The original text is kept below because it is why the experiment was run.

~~**Unverified.** §1's consequence 1 assumes SubstrateVM silently ignores `JAVA_TOOL_OPTIONS`-supplied
`-javaagent`. If it instead **fails to start**, the current operator would hard-break any native target the
moment a `BasquinTarget` was applied — which would make this spec more urgent, not less, and belongs in
`THIRD-PARTY-APPS.md` as a hazard.

Settle it with a one-off run against the native binary PR-3 already built:
`bench-results/dd043-spikes-2026-07-24/fixture/target/fixture-1.0.0-SNAPSHOT-runner`. (Not the
`bench-results/dd043-pr3-native-2026-07-26/` evidence directory, as an earlier draft said — that holds logs
only, since `bench-results/` tracks no binaries anywhere.)

**Use the full string the operator would actually inject**, not a placeholder: `buildAgentArgs`
(`injection.go:108-115`) emits `-javaagent:`, `-Xbootclasspath/a:` **and** `-Dbasquin.boundary=agent`
together. Testing with `-javaagent:/nonexistent.jar` alone would answer a different question — a missing
file may fail differently from a present-but-unusable agent, and `-Xbootclasspath/a` and the boundary
selector each have their own failure mode on SubstrateVM. Record the outcome per flag.

### 8.2 Should `preInstrumented` be inferred rather than declared?

The operator could in principle detect instrumentation by probing `/__basquin/result` before deciding to
patch. Rejected for now: a probe that fails for an unrelated reason (app still starting, network policy)
would silently fall back to patching an already-instrumented app — the exact failure this spec is written
to prevent. Declaration is auditable; inference is not. Recorded because it will be proposed.
