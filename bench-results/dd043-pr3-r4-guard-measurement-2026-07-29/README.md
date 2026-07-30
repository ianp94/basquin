# DD-043 PR-3 — round-4 guard measurement: what dependencyManagement actually does to an injected dependency

This directory exists to settle, by measurement, the Maven-internals question PR #103's round-4 approver
raised as S1 — and, while the harness was standing, to settle the shape of the S2 guard the same way
instead of arguing it.

**Every figure below is a `file:line` into this directory.** `citations.txt` is the machine-generated
index of every line cited here; `cell-results.txt` is the machine-generated summary table. Both are
regenerated from `logs/`, not typed.

---

## The question

`BasquinInjector` adds its `<dependency>` at `afterProjectsRead` — *after* model building, therefore
after Maven's model-level dependencyManagement injection has already run over the model. The
managed-exclusions guard added in `a61a90c` asserted as fact that

> Managed exclusions apply to the dependency this injector adds, so they can strip basquin-core

and nothing measured it. The approver's S1 argued the guard is wrong in either direction that claim
resolves: if managed attributes *do* reach a post-model-build dependency then `scope`/`type`/`classifier`
are unguarded on the managed path too; if they *do not* then the guard is a false positive that
hard-fails a legitimate build with an untrue message.

Neither horn was measured. This is that measurement.

## Method

A minimal jar project (`cells/*.pom.xml`, 5 lines of coordinates plus one deliberate deviation each) is
built with the injector on `-Dmaven.ext.class.path`, and the resolved dependency set is read out with
`maven-dependency-plugin:3.6.1:list`. `list` is `@requiresDependencyResolution(TEST)`, so Maven's own
`LifecycleDependencyResolver` → `DefaultProjectDependenciesResolver` performs the resolution and the
mojo prints what Maven resolved — not what the plugin re-derived.

Two injector jars, differing only by `injector-noguard.diff` (a one-line early `return` in
`failOnConflictingManagedVersion`):

- **`stock`** — the branch source, unmodified. Used for every cell about what the *shipped* injector does.
- **`noguard`** — the guard neutered, so Maven's real behaviour under a managed `com.basquin` entry can
  be observed instead of being intercepted by the guard under test. This is the only way to measure a
  hazard a guard already refuses to let happen.

Both jars were compiled in a scratchpad from `BasquinInjector.java` at the HEAD commit recorded in
`provenance.txt`; the repository's own source was never modified for this run. `provenance.txt` records
the Maven version, both jar md5s, the md5 of every artifact served, and the tree-dirty disclosure.

Resolution used an **isolated, initially empty local repository**, `com/basquin/*` from a `file://`
repository the injector was pointed at, and Maven Central for the rest. The user's `~/.m2/repository`
was not used as the local repo.

`com.basquin:basquin-core:0.0.1-conflicting` is **synthetic** — the 0.3.0 jar under a rewritten version
(`provenance.txt`). That is deliberate: if the conflicting version could not resolve, the build would
fail loudly, and a loud failure is not the hazard under test. The hazard is a build that **succeeds**.

## Cell 0 — control

`cells/ctl.pom.xml`, no `dependencyManagement`, no declared basquin. Both jars agree:

| | `basquin-quarkus` | `basquin-core` | resolved |
|---|---|---|---|
| `logs/ctl-noguard-list.log:12-13` | `jar:0.3.0:compile` | `jar:0.3.0:runtime` | 95 |
| `logs/ctl-stock-list.log:12-13` | `jar:0.3.0:compile` | `jar:0.3.0:runtime` | 95 |

`BUILD SUCCESS` at `logs/ctl-noguard-list.log:109` and `logs/ctl-stock-list.log:109`. The injector's own
accept line is at `:2` of each. This is the baseline every other cell is read against.

---

## Result 1 — managed `<exclusions>` DO apply. The guard's claim is true; S1's second horn is refuted.

`cells/mx.pom.xml` — `dependencyManagement` for `com.basquin:basquin-quarkus:0.3.0` (the *agreeing*
version, so only the exclusions branch is in play) carrying `<exclusion>com.basquin:basquin-core`.

Run with `noguard`, so the build is allowed to proceed:

- `logs/mx-noguard-list.log:12` — `com.basquin:basquin-quarkus:jar:0.3.0:compile` resolved.
- **`basquin-core` does not appear anywhere in the resolved set** — the only `com.basquin` line in the
  block is `:12`, against the control's two at `ctl-noguard-list.log:12-13`.
- Resolved count **94** (`cell-results.txt`) against the control's **95**: exactly one artifact removed.
- `logs/mx-noguard-list.log:108` — `BUILD SUCCESS`.

A managed `<exclusions>` entry therefore strips `basquin-core` from the dependency the injector adds at
`afterProjectsRead`, and the build **succeeds** while doing it. The guard is not a false positive, its
message is not untrue, and the hazard it names is real and reproducible.

*(Mechanism, now that the behaviour is measured rather than assumed: `DefaultProjectDependenciesResolver`
passes `project.getDependencyManagement()` into the Aether `CollectRequest` as managed dependencies at
resolution time — long after model building — and `ClassicDependencyManager` applies managed **exclusions**
at every depth, unlike version/scope/optional. The measurement is the evidence; this paragraph is only
the explanation of it.)*

## Result 2 — managed `scope`, `type`, `classifier` and `optional` do NOT apply. S1's first horn is refuted.

Same shape, one managed attribute varied at a time, all with `noguard` so nothing intercepts them. All
four are **identical to the control** — 95 resolved, `basquin-quarkus` still at `:compile`, `basquin-core`
still present at `:runtime`:

| cell | managed attribute | `basquin-quarkus` | `basquin-core` | citation |
|---|---|---|---|---|
| `msc` | `<scope>provided</scope>` | `jar:0.3.0:compile` | `jar:0.3.0:runtime` | `logs/msc-noguard-list.log:12-13` |
| `mty` | `<type>pom</type>` | `jar:0.3.0:compile` | `jar:0.3.0:runtime` | `logs/mty-noguard-list.log:12-13` |
| `mcl` | `<classifier>tests</classifier>` | `jar:0.3.0:compile` | `jar:0.3.0:runtime` | `logs/mcl-noguard-list.log:12-13` |
| `mopt` | `<optional>true</optional>` | `jar:0.3.0:compile` | `jar:0.3.0:runtime` | `logs/mopt-noguard-list.log:12-13` |

The `msc` row is the direct refutation of the approver's reproduction: a managed `<scope>provided</scope>`
for `com.basquin:basquin-quarkus` at the agreeing version does not change the injected dependency's scope,
so the fact that `failOnConflictingManagedVersion` throws nothing for it is **correct**, not a gap. There
is nothing there to guard. Adding a managed-scope guard would have hard-failed builds that demonstrably
work — the same mistake as guarding `optional=true` would have been.

## Result 3 — managed `<version>` DOES apply transitively. The existing version branch guards a real hazard.

`cells/mcv.pom.xml` — `dependencyManagement` pinning `com.basquin:basquin-core:0.0.1-conflicting`.

- With `noguard`: `logs/mcv-noguard-list.log:13` — `com.basquin:basquin-core:jar:0.0.1-conflicting:runtime`
  resolved, alongside `basquin-quarkus:jar:0.3.0:compile` at `:12`, and `BUILD SUCCESS` at `:109`. This is
  verbatim "a build instrumented with a different core than the extension was compiled against", succeeding.
- With `stock`: `logs/mcv-stock-list.log:2` — the guard fires, Maven aborts in `Scanning for projects`, and
  the log contains no resolved set at all.

So the version branch and the exclusions branch each guard a hazard measured to be real, and the two
mechanisms are genuinely different (version at depth ≥ 2 only; exclusions at every depth) — which is why
the version branch, not the exclusions branch, is the one that catches this cell.

---

## S2 — a declared `com.basquin:basquin-core` at a conflicting version, and what else on that path matters

All S2 cells are run with the **`stock`** jar: the question is what the *shipped* injector does. "Shipped"
here means shipped **at capture time**, commit `bffcbba` (`provenance.txt`) — the commit these very
findings motivated `failOnUnusableSiblingDeclaration` against, which landed *next*, in `8cadf8a`. Two of
the three hazard rows below (`dcv`, `dsc`, `dprov`) are exactly the shapes that guard now rejects; see
"Reproducibility under the shipped guard" below for what that means for reproducing this directory today.

**The bypass is real.** `cells/dcv.pom.xml` declares `com.basquin:basquin-core:0.0.1-conflicting` directly:

- `logs/dcv-stock-list.log:2` — the injector logs `instrumented probe-dcv`; no guard fires.
- `logs/dcv-stock-list.log:111-112` — the resolved set contains
  `com.basquin:basquin-core:jar:0.0.1-conflicting:compile` **and** `com.basquin:basquin-quarkus:jar:0.3.0:compile`.
- `logs/dcv-stock-list.log:208` — `BUILD SUCCESS`.

That is `failOnConflictingManagedVersion`'s own stated failure mode — a build succeeding with a core that
does not match the extension — reached through the declared path, which nothing looked at, because
`declaredDependency` matches on `artifactId == basquin-quarkus`. The managed equivalent (`mcv`, above)
hard-fails. The asymmetry is real.

**Which other declared-sibling shapes are hazards, measured rather than assumed.** The class javadoc's own
rule is that enumerating bad values ships the next variant, so every field was tried, and the guard covers
exactly what measured harmful:

| cell | declared `com.basquin:basquin-core` shape | `basquin-core` in the resolved set | verdict |
|---|---|---|---|
| `dag` | `0.3.0`, plain | `jar:0.3.0:compile` (`logs/dag-stock-list.log:12`) | harmless — **accept** |
| `dcv` | `0.0.1-conflicting` | `jar:0.0.1-conflicting:compile` (`logs/dcv-stock-list.log:111`) | **hazard — guard** |
| `dsc` | `0.3.0`, `<scope>test</scope>` | `jar:0.3.0:**test**` (`logs/dsc-stock-list.log:12`) | **hazard — guard** |
| `dprov` | `0.3.0`, `<scope>provided</scope>` | `jar:0.3.0:**provided**` (`logs/dprov-stock-list.log:12`) | **hazard — guard** |
| `dty` | `0.3.0`, `<type>pom</type>` | `pom:0.3.0:compile` **plus** `jar:0.3.0:runtime` (`logs/dty-stock-list.log:13,15`) | harmless — **accept** |
| `dcl` | `0.3.0`, `<classifier>tests</classifier>` | `jar:tests:0.3.0:compile` **plus** `jar:0.3.0:runtime` (`logs/dcl-stock-list.log:56,58`) | harmless — **accept** |
| `dex` | `0.3.0`, `<exclusions>*:*` | `jar:0.3.0:compile` (`logs/dex-stock-list.log:12`) | harmless — **accept** |

Reading the table:

- **`dsc` / `dprov` are the second hazard.** A direct declaration wins the scope for that node, so
  `basquin-core` lands at `test` / `provided` and is absent from the runtime classpath while
  `basquin-quarkus` is still at `compile` — the extension ships without the core it needs, and the build
  succeeds (`logs/dsc-stock-list.log:109`, `logs/dprov-stock-list.log:109`).
- **`dty` / `dcl` are not hazards, and this is why the guard does not copy `failOnUnusableDeclaration`
  wholesale.** `type` and `classifier` are part of the resolution key, so a `pom`-typed or classified
  declaration is a *different node*: the resolved set holds **96** artifacts, one more than the control,
  and the plain transitive `basquin-core:jar:0.3.0:runtime` still arrives. Guarding these would reject
  builds that demonstrably work.
- **`dex` is not a hazard** here either: an exclusion on a declared `basquin-core` strips *that node's*
  transitives, and `basquin-core-0.3.0.pom` declares none. (Contrast `mx`, where the exclusion is on the
  managed entry for `basquin-quarkus` and therefore strips `basquin-quarkus`'s own child.)

`system` and `import` are not measured here. They are rejected by the same compile/runtime whitelist as
`test`/`provided` on the declared-`basquin-quarkus` path, where `system` additionally requires a
`systemPath` and `import` is only meaningful inside `dependencyManagement`.

---

## The guard this directory licenses, and the proof its tests bind

`mutation-proof.txt` is the transcript; `mutation-proof.py` regenerates it and is what to re-run after
touching any guard. Each row neuters one guard branch, **deletes `build/test-results` first**, runs the
module suite, and reads the failing set out of *fresh* JUnit XML — a row showing `xml 0` would mean no
verdict exists rather than a pass (round 3's B1, which is the trap this branch has now hit three times).

Ten rows, all `binds=YES`, baseline and restored suite both `gradle_rc=0 tests=28 failing=0`
(`mutation-proof.txt`). Five are the guards `scripts/verify-dd043-pr3.sh` already mutation-checks,
re-run here because S2's guard edits the same file they anchor in. Five are new:

- `sibling-scope` and `sibling-version` neuter one branch each and fail exactly that branch's test.
  **These two are the entries to add to `run_guards`** — one per branch, for the reason the
  `managed-exclusions` entry records: a single whole-method mutation leaves whichever branch it does not
  name unbound.
- `sibling-whole` neuters the method and fails both.
- `sibling-overstrict` and `sibling-widened` push the guard the *other* way — rejecting an agreeing
  version, and widening to `failOnUnusableDeclaration`'s full whitelist — and fail the accept-side tests.
  Without those two rows the accept decisions in the table above would be unpinned, and a future widening
  would silently reject the three shapes measured harmless.

## What this does not establish

- Every cell is a **plain `jar` project**, not a Quarkus application. It measures what Maven resolves,
  which is the mechanism S1 and S2 both turn on. It does not measure what Quarkus augmentation then does
  with a `basquin-core` that is missing or at the wrong version — that could plausibly fail loudly at
  augmentation time rather than silently, and it was not run.
- Single-module reactor only. Managed entries inherited from a parent pom reach the effective model the
  same way, but that was not exercised.
- `com.basquin:basquin-core:0.0.1-conflicting` is a copy of the 0.3.0 jar, so "the build succeeds with a
  mismatched core" is measured at the *resolution* level. Nothing was executed from it.
- Maven **3.9.15** only, and that version is attested by exactly one file: `provenance.txt:7`
  (`Apache Maven 3.9.15 (98b2cdbfdb5f1ac8781f537ea9acccaed7922349)`), captured once for the run. It is
  **not** in the per-cell evidence — the 17 `logs/*.log` files were captured without `-V`, they open at
  `[INFO] Scanning for projects...`, and `grep 'Apache Maven' logs/*.log` matches nothing. So the
  version is a run-level record, not something any individual cell's log proves.
  It is the version `BasquinInjector`'s javadoc pins to.
  Maven 4's resolver replaces `ClassicDependencyManager`'s depth rules; nothing here transfers to it.

## Reproducibility under the shipped guard (round-5 approver S6)

This directory was captured at `bffcbba` (`provenance.txt`), the commit immediately **before**
`failOnUnusableSiblingDeclaration` landed in `8cadf8a`. Three of its cells — `dcv`, `dsc`, `dprov` — are
precisely the shapes that guard was written to reject. Naively rebuilding `rerun.sh`'s "stock" jar from
whatever is checked out today (post-`8cadf8a`) would make those three cells abort at `Scanning for
projects` instead of reproducing the committed `BUILD SUCCESS`, with no way for a reader to tell a real
regression from the guard doing its job. `rerun.sh` now avoids that by building **three** jars instead of
two:

- `stock` — today's checkout (HEAD), used for every cell the guard does not affect.
- `noguard` — `stock` with `failOnConflictingManagedVersion` neutered, for the S1 cells.
- `stock-preguard` — `BasquinInjector.java`/`InjectorVersion.java` **as they read at `bffcbba`**, read
  via `git show bffcbba:<path>` (read-only; no checkout, no working-tree change) and compiled fresh. Used
  only for `dcv`, `dsc`, `dprov`, so those three reproduce the committed `BUILD SUCCESS` verbatim.

`rerun.sh` then runs `dcv`/`dsc`/`dprov` a **second** time each, against today's `stock` jar, into three
new logs (`dcv-guard-verify-list.log`, `dsc-guard-verify-list.log`, `dprov-guard-verify-list.log`) that
are not part of the original 17 and are not compared against them. These are expected to abort at
`Scanning for projects` with `failOnUnusableSiblingDeclaration`'s message — confirmed by an actual run
(below) — which is the guard working, not a failure of this script.

| cell(s) | reproduces verbatim today? | why |
|---|---|---|
| `ctl`, `mx`, `msc`, `mty`, `mcl`, `mopt`, `mcv` (`noguard`) | yes | `noguard` bypasses every guard, including the new one |
| `mcv` (`stock`) | yes | trips `failOnConflictingManagedVersion`, unchanged since before `bffcbba` |
| `ctl`, `dag`, `dty`, `dcl`, `dex` (`stock`) | yes | none declare a sibling shape `failOnUnusableSiblingDeclaration` checks (it looks only at `scope` and `version`) |
| **`dcv`, `dsc`, `dprov`** | **yes, via the pinned `stock-preguard` jar** — **no**, if built from today's checkout instead | these are exactly the shapes the new guard rejects |

**Verified by an actual run**, `MVN`/`BASQUIN_SRC_REPO` pointed at this machine's Maven 3.9.15 install and
`~/.m2/repository` (which already held `com/basquin/*:0.3.0`), network reachable to Maven Central:

```
$ WORK=<scratch> bash rerun.sh
...
dcv (stock-preguard)     -> dcv-stock-list.log
dsc (stock-preguard)     -> dsc-stock-list.log
dprov (stock-preguard)   -> dprov-stock-list.log
...
dcv (stock)              -> dcv-guard-verify-list.log
dsc (stock)              -> dsc-guard-verify-list.log
dprov (stock)            -> dprov-guard-verify-list.log
```

- `dcv-stock-list.log` (`stock-preguard`): `[INFO] BUILD SUCCESS`, resolved set holds
  `com.basquin:basquin-core:jar:0.0.1-conflicting:compile` — matches `logs/dcv-stock-list.log` as
  committed. Same for `dsc`/`dprov` (`:test`/`:provided`, `BUILD SUCCESS`).
- `dcv-guard-verify-list.log` (today's `stock`): `[ERROR] basquin-injector: probe-dcv declares
  com.basquin:basquin-core at version 0.0.1-conflicting, but this injector supplies
  basquin-quarkus:0.3.0. …` — `failOnUnusableSiblingDeclaration`'s own message, build aborted at
  `Scanning for projects`. `dsc`/`dprov` abort the same way with the scope-branch message
  (`… declares com.basquin:basquin-core at scope 'test'/'provided'. …`).
- The unaffected cells (`ctl-stock`, `dag-stock`, `dty-stock`, `dcl-stock`, `dex-stock`, `mcv-stock`, all
  `-noguard` cells) reproduced with the same resolved counts as `cell-results.txt` (95/95/96/96/95 and
  `mcv-stock`'s pre-existing `failOnConflictingManagedVersion` abort).

This confirms the table above by measurement, not assertion: the finding this directory reports is
unaffected by the guard that was added afterward, and the reproduction script now says so instead of
failing confusingly.

## Reproduce

`rerun.sh` regenerates 16 of the 17 original logs in `logs/` from `cells/*.pom.xml`, plus 3 new
guard-verification logs (see above). The seventeenth original log, `ctl-noguard-coldcache-list.log`, is
the same control cell on a cold local repository — kept only because it is the one log that records
where every artifact came from — and a rerun's local repository will be warm by the time it reaches the
control, so that file is not reproducible verbatim. It needs network (Maven Central), a Maven 3.9.15
distribution, `git` (to read `BasquinInjector.java`/`InjectorVersion.java` at `bffcbba` — read-only, no
checkout), and `com/basquin/*:0.3.0` available somewhere it can be pointed at; it builds its own local
repository and its own three injector jars from the module source, and writes nothing into the repository
tree or into `~/.m2/repository`.
