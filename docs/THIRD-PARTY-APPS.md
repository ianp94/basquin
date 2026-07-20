# Running ClosureJVM against a third-party WAR (Tomcat valve)

Our own demo WAR bundles `IterationFilter` in its `web.xml`. Third-party apps
(JPetStore, JSPWiki, …) can't be modified, so ClosureJVM attaches iteration
boundaries with a **Tomcat valve** instead — no WAR repacking, no `web.xml` edits.
Rationale and rejected alternatives: `DESIGN-DECISIONS.md` DD-009.

> **Mutually exclusive with the in-WAR filter.** Use the *filter* for our demo WAR,
> the *valve* for third-party WARs. Running both wraps every request twice (verified:
> 6 `beginIteration` calls for 3 requests), producing nested, meaningless iteration
> boundaries. Never enable both on the same app.

## Pieces

- `tomcat-valve/` — `com.closurejvm.valve.ClosureJVMValve` (extends `ValveBase`), built
  to `closurejvm-valve-<v>.jar`. Goes in Tomcat's `lib/`.
- `deploy/valve/context.xml` — global context that registers the valve for every
  deployed app. Mounted over `conf/context.xml`.
- `build/libs/closurejvm-<v>.jar` — the agent, injected via `CATALINA_OPTS`
  (`-javaagent` + bootclasspath) so invariants are captured inside the server JVM.
- `docker-compose.valve.yml` — wires all of the above around a WAR under test.

## Quick run (against the demo WAR, to exercise the valve path)

```
./gradlew jar :tomcat-war:build :tomcat-valve:jar
# demo WAR already has the filter, so for a pure valve test point WAR_PATH at a
# filterless WAR (see JPetStore below). To just confirm the stack deploys:
docker compose -f docker-compose.valve.yml up tomcat-valve
```

Verified deploy signals: `ClosureJVM Agent initialized` in the log (premain ran),
WAR deploys with no `SEVERE`, routes serve, and `GET /closurejvm/status` shows
server-side `requests`/`crashes`/`invariants` counters advancing.

## JPetStore (MyBatis) — first real target

JPetStore is a small, readable e-commerce WAR — findings are easy to hand-verify.

1. **Get a WAR.** Build MyBatis JPetStore-6 (`github.com/mybatis/jpetstore-6`) or use a
   build you trust. Note the servlet namespace:
   - Recent JPetStore (Jakarta EE, `jakarta.servlet`) → Tomcat 10.1 (matches this setup).
   - Older JPetStore (`javax.servlet`) → needs Tomcat 9 **and** a `javax`-compiled valve
     variant. Don't mix a `jakarta` valve into a `javax` Tomcat; they won't link.
2. **Point the compose at it:**
   ```
   WAR_PATH=/abs/path/jpetstore.war docker compose -f docker-compose.valve.yml up tomcat-valve
   ```
   JPetStore has no `IterationFilter`, so the valve is the sole boundary — no double-wrap.
3. **Database.** JPetStore ships with an in-memory HSQLDB by default (no external DB
   needed for a first run). If you use the MySQL profile, reuse the `mysql` service from
   the root `docker-compose.yml`.
4. **Drive it.** Point the HTTP driver target at `http://localhost:8080/...` JPetStore
   routes (catalog search, cart, order) with a small seed corpus, soft invariants on, and
   watch `GET /closurejvm/status` plus the `X-ClosureJVM-Invariant-*` response headers.

### Status of this slice

- [x] Reusable valve built and confirmed loading/active in real Tomcat 10.1.
- [x] Deploy scaffolding (compose + global context.xml) and this recipe.
- [ ] Live run against an actual JPetStore WAR (needs a jakarta-compatible build) — the
      next verification step; wire the HTTP driver + seed corpus for its routes.
