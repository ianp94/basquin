# Benchmark deployments (Phase 0 — build de-risk)

Reproducible setups for the benchmarking campaign
([spec](../../docs/superpowers/specs/2026-07-21-benchmarking-campaign-design.md),
[plan](../../docs/superpowers/plans/2026-07-21-benchmarking-campaign.md)). WARs are gitignored
(large, reproducible); this README pins how to fetch/build each.

## JSPWiki — CORE CMS target — de-risked ✅ (2026-07-21)

- **No build needed:** Apache ships a prebuilt WAR.
  `curl -sLo deploy/bench/jspwiki/JSPWiki.war https://archive.apache.org/dist/jspwiki/2.12.4/binaries/webapp/JSPWiki.war`
  (pinned **2.12.4**, sha to be recorded on first smoke).
- **Servlet namespace:** `javax` (web.xml root `xmlns.jcp.org/xml/ns/javaee`, Servlet 3.1; zero
  `jakarta` libs) → **Tomcat 9** with the namespace-free valve (reuses the `docker-compose.valve9.yml` path).
- **Storage:** filesystem page store (`VersioningFileProvider`) — no external DB. Needs
  `jspwiki.fileSystemPath` set + a seeded pages dir (content to render).
- **DB-backed option:** the same release ships `jspwiki-it-test-custom-jdbc-2.12.4.war` — a
  JDBC-backed JSPWiki. Candidate for the DB-driven-pathology target if Roller (Task 0.2) fights its build.
- **WAR sha256:** `3e4affef0e03ec9a…` (pin).
- **Stand up:** `bash deploy/bench/jspwiki/setup.sh` (explodes WAR → `webapp/`, seeds 70 pages →
  `pages/`), then `cd deploy/bench/jspwiki && TOMCAT_HOST_PORT=8090 docker compose up -d`.
- **Smoke result (2026-07-21) — GREEN ✅, findings flow:** agent loaded (`Basquin Agent initialized`),
  Tomcat 9 deploy clean, routes 200. Valve captured latency invariants server-side. Cold-vs-warm on
  `/Wiki.jsp?page=Main`: **8260ms cold → 847ms → ~34ms steady** (first-request-after-deploy cliff:
  JSP compile + cold-cache markup render); 10 invariant finds in the smoke. Steady state (~34ms) still
  clips a 25ms budget. **JSPWiki is IN as the core CMS target.** (Phase 2 will add a warmup phase so
  steady-state metrics aren't dominated by the one-time JSP compile — the cold cliff is captured separately.)

## Roller — stretch (DB-backed) — attempted thoroughly, OUT (2026-07-21)

Apache Roller **6.1.5** (prebuilt WAR from the binary dist; `javax`/Servlet 4.0 → Tomcat 9). Got a
long way: stood up on Tomcat 9 with the valve+agent + a **PostgreSQL 16** container, Roller's
`DatabaseProvider` connected successfully, and I loaded its bundled `dbscripts/postgresql/createdb.sql`
(33 tables). **But** the ROOT context's Spring/Struts listeners fail to start on init
(`StandardContext ... One or more listeners failed to start`) even with the schema present — a
Roller bootstrap issue in this environment (heavy Spring Security + Struts Tiles + JPA stack, ~158s
deploy). Chasing the exact listener exception is the multi-iteration rabbit hole the spec's
bounded-effort fallback exists to avoid, so per the plan: **Roller is out.**

**Decision: ship the 2-app campaign (JPetStore + JSPWiki).** It is already complete, framework-diverse,
and covers the DB dimension honestly — **JPetStore exercises a real SQL/JDBC path** (MyBatis over
HSQLDB), so "surfaces DB-driven pathologies" is demonstrated without Roller. A networked-DB CMS
(Roller, or the JSPWiki-JDBC variant against Postgres) is a **documented future extension**; the
`deploy/bench/roller/` compose is kept as a resumable starting point (KNOWN ISSUE: context-listener
init failure).
