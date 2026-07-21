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

## Roller — stretch (DB-backed) — Task 0.2, pending

TBD in Phase 0. Fallback order (spec): lighter DB-backed CMS (e.g. the JSPWiki JDBC variant above) →
file-store CMS → ship 2 apps.
