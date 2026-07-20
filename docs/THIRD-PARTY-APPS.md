# Running ClosureJVM against a third-party WAR (Tomcat valve)

Our own demo WAR bundles `IterationFilter` in its `web.xml`. Third-party apps
(JPetStore, JSPWiki, …) can't be modified, so ClosureJVM attaches iteration
boundaries with a **Tomcat valve** instead — no WAR repacking, no `web.xml` edits.
Rationale and rejected alternatives: `DESIGN-DECISIONS.md` DD-009.

> **Mutually exclusive with the in-WAR filter.** Use the *filter* for our demo WAR,
> the *valve* for third-party WARs. Running both wraps every request twice (verified:
> 6 `beginIteration` calls for 3 requests), producing nested, meaningless iteration
> boundaries. Never enable both on the same app.

## Servlet namespace → Tomcat → compose

The valve jar is **namespace-free** (DD-011): one artifact runs on both Tomcat lines.
Pick the Tomcat image to match the *app's* servlet namespace:

| App servlet namespace | Tomcat image        | Compose file                 |
|-----------------------|---------------------|------------------------------|
| `jakarta.servlet`     | `tomcat:10.1-jdk17` | `docker-compose.valve.yml`   |
| `javax.servlet`       | `tomcat:9.0-jdk17`  | `docker-compose.valve9.yml`  |

To check an app: `unzip -l app.war | grep -E 'spring-web|servlet'` and look at
`WEB-INF/web.xml`'s root namespace (`http://java.sun.com/...` and `javax.servlet.jsp.jstl`
→ javax; `jakarta.*` → jakarta).

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

## JPetStore (MyBatis) — first real target — DONE

JPetStore-6 is `javax.servlet` (bundles `spring-web-5.3.39`, old javaee `web.xml`), so it
runs on **Tomcat 9** with the (namespace-free) valve.

1. **Build the WAR.** JPetStore master targets JDK 17 but its build plugins demand newer
   tooling; build with Maven 3.9+ and the version gates skipped:
   ```
   git clone --depth 1 https://github.com/mybatis/jpetstore-6.git
   cd jpetstore-6
   mvn -DskipTests -Denforcer.skip=true -Dmaven.gitcommitid.skip=true package
   # -> target/jpetstore.war (uses an in-memory HSQLDB; no external DB needed)
   ```
2. **Deploy with the valve on Tomcat 9:**
   ```
   ./gradlew jar :tomcat-valve:jar
   WAR_PATH=$PWD/jpetstore-6/target/jpetstore.war docker compose -f docker-compose.valve9.yml up tomcat9-valve
   ```
   JPetStore has no `IterationFilter`, so the valve is the sole boundary — no double-wrap
   (iteration numbers increment 1,2,3,… one per request).
3. **Drive it** — hit real routes and read findings from the server-side agent log
   (`[ClosureJVM][Invariant] …`) and the `X-ClosureJVM-Invariant-*` response headers:
   ```
   curl -s -D - "http://localhost:8080/actions/Catalog.action?viewCategory=&categoryId=FISH" | grep X-ClosureJVM
   ```

### Verified findings (2026-07-19, soft invariants latency>5ms / heap>64KB)

Server-side, inside JPetStore's Tomcat 9 JVM via the valve:

| Route                                   | latency | heap delta | invariant |
|-----------------------------------------|--------:|-----------:|-----------|
| `/` (index)                             |  11ms   |  +2964KB   | latency, heap |
| `Catalog.action` (first, cold)          |  531ms  | +44149KB   | latency, heap |
| `Catalog.action?categoryId=FISH`        |  98ms   |            | latency |
| `Catalog.action?viewProduct=FI-SW-01`   |  81ms   | +17636KB   | latency, heap |

The cold first-catalog spike (531ms) and per-request multi-MB heap growth are exactly the
input/state-dependent availability pathologies the harness targets — surfaced in an
unmodified third-party app with no code changes.

### Status of this slice

- [x] Reusable, namespace-free valve (DD-011): one jar for Tomcat 9 and 10.
- [x] Deploy scaffolding (`docker-compose.valve.yml`, `docker-compose.valve9.yml`, context.xml).
- [x] Live run against a real JPetStore WAR on Tomcat 9 with server-side invariants captured.
- [ ] Next: an HTTP driver target + seed corpus to explore routes automatically (vs. manual curls).
