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

## Roller — stretch (DB-backed) — Task 0.2, pending

TBD in Phase 0. Fallback order (spec): lighter DB-backed CMS (e.g. the JSPWiki JDBC variant above) →
file-store CMS → ship 2 apps.
