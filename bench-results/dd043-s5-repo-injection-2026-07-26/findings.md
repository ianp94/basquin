# S5 findings — injected `<repository>` vs Quarkus bootstrap resolution

## Verdict: CONFIRMED

With the local repository purged of `com.basquin` (`purge-proof.txt`) and Central not carrying the
group (`central-absence.txt`), a Maven core extension injecting a repository at `afterProjectsRead`
was the sole source for the full closure, over HTTP, with zero edits to any application-tree file:

| Artifact | Resolution | Evidence |
|---|---|---|
| `com.basquin:basquin-quarkus:0.3.0` | RESOLVED from `basquin-injected`, session dependency resolution | `build-s5-injected.log:8-17`, `http-access.log`, `local-repo-provenance.txt` |
| `com.basquin:basquin-core:0.3.0` | RESOLVED from `basquin-injected`, session dependency resolution (transitive of runtime) | `build-s5-injected.log:11-19`, same two |
| `com.basquin:basquin-quarkus-deployment:0.3.0` | RESOLVED from `basquin-injected` **by the Quarkus bootstrap resolver** (during `quarkus:generate-code`; augmentation then consumed it — banner lists `basquin`, and that feature is emitted by the deployment module's build step) | `build-s5-injected.log:27-33,52-53`, `banner-s5.txt` |

The crux sub-question — whether Quarkus's bootstrap builds its remote-repository list from the
session/settings rather than the mutated project — did **not** reproduce §1.1's failure mode: the
bootstrap consumed `${project.remoteProjectRepositories}`, which the participant refreshed. §5.1's
S4 result extends to the repository half.

**The load-bearing implementation detail** (this is the part that fails silently if omitted):
injecting the `<repository>` into the `Model` alone is a no-op at `afterProjectsRead` — the
effective repository lists were computed before any participant ran. The participant must *also*
append to `p.getRemoteArtifactRepositories()` and call `p.setRemoteArtifactRepositories(...)`, whose
3.9.16 implementation (verified in bytecode) refreshes the Aether `remoteProjectRepositories` list
that both Maven resolution and the quarkus-maven-plugin consume. **Round 5 (2026-07-29): the
model-only half of this claim is now measured, not just read from bytecode.** A control probe
(`ModelOnlyRepoInjectProbe.java`) applies the model mutation alone and omits
`setRemoteArtifactRepositories(...)`; the resulting build fails at dependency resolution
(`Could not find artifact com.basquin:basquin-quarkus:jar:0.3.0 in central`) because the resolver
never attempts `basquin-injected` at all — see README "The control cell (round 5, 2026-07-29)",
`build-s5-control.log`.

## Scope limits (claims are exactly this wide)

- JVM packaging only; single-module fixture; plain HTTP on localhost (TLS untested; environments
  with custom `mirrorOf="*"` mirrors unmeasured — see README's transport note).
- The injected-**dependency** half stays S4's claim: the fixture pom still declares
  `basquin-quarkus` (PR-2 leftover, unmodifiable under the zero-edit rule), so S5 cannot attribute
  the dependency's model presence to injection. The **deployment** artifact carries no such
  confound — no pom anywhere declares it; only the runtime jar's `quarkus-extension.properties`
  names it, and the bootstrap fetched it from the injected repo on its own.

## Spec amendment forced (docs/superpowers/specs/2026-07-24-native-reactive-targets-design.md)

The brief was right that either outcome forces one. The CONFIRMED outcome forces:

1. **§5 (Injection without source modification):** the injector's `afterProjectsRead` injects the
   `basquin-quarkus` dependency **and the Pages `<repository>`** — and the spec must state the
   two-level mutation (model `Repository` + `setRemoteArtifactRepositories(...)` to refresh
   `remoteProjectRepositories`), because the model-only version **fails to resolve, measured**
   (round-5 control cell, `build-s5-control.log`: `BUILD FAILURE`,
   `Could not resolve dependencies ... com.basquin:basquin-quarkus:jar:0.3.0`) — not merely argued
   from maven-core's bytecode. Fresh-instance-per-project discipline (already specified for
   `Dependency`) applies to the `Repository`/`ArtifactRepository` objects too.
2. **§5, operator contract:** no operator pre-populate step is required; S4's local-repo path
   (`publishToMavenLocal` / `install:install-file`) demotes to a documented **offline fallback**,
   not a requirement. `THIRD-PARTY-APPS.md` therefore needs no per-app install step.
3. **§3 (spec:339) "Consumers add that one `<repository>`":** add the cross-reference that for
   PR-3 harness targets the injector adds it — the ordinary-consumer sentence stays true but must
   not read as PR-3's mechanism (it is incompatible with the zero-pom-edit rule).
4. **§3 publishing gap, surfaced by this spike:** only `basquin-core` publishes to the Pages repo
   today; `basquin-quarkus` and `basquin-quarkus-deployment` publish only to `dd043Spike`. PR-3's
   self-contained story needs all **three** artifacts in the Pages repo (this spike stood that in
   via `s5-publish-init.gradle`). That is a `basquin-quarkus/*/build.gradle` + release-workflow
   change PR-3 must make; per the spike constraints it was not made here.

No `basquin-quarkus`/`basquin-core` production-code change is needed.
