# DD-028 — Dashboard read-path authentication (design note)

Status: **proposed** (2026-07-21). Implements the open half of the #21/#43 review finding.

## Context

Since #43, dashboard **writes** are authenticated: the operator mints a per-campaign 256-bit token
into a `<campaign>-dashboard-token` Secret, and `DashboardClient` sends it as `X-Basquin-Token` on
every push. **Reads are deliberately open** — `/api/campaign/…` and the HTML UI — because the
browser behind a `kubectl port-forward` cannot attach a custom header. Consequence: any pod with
network reach to the ClusterIP can read a campaign's findings (routes, inputs, stack traces —
reconnaissance-grade data about the target app). Acceptable single-tenant; not multi-tenant.

## Options considered

**A. Token-to-cookie handoff (recommended).** The dashboard accepts the existing per-campaign token
once as a query parameter (`/?token=…`); on a constant-time match it sets an `HttpOnly` /
`SameSite=Lax` session cookie and immediately redirects to the bare URL (so the token doesn't sit
in the address bar or get bookmarked). Guarded reads then accept either the `X-Basquin-Token`
header (drivers, curl, scripts) or the cookie (browsers). When no token is configured — the
standalone laptop case — reads stay open, exactly as today.

- The CLI already does the port-forward (`basquin dashboard`); it additionally reads the campaign's
  Secret (RBAC it already has) and prints the tokenized URL, so the UX stays one command.
- No new secret material, no new CRD surface, no external dependency; reuses the mint/mount
  machinery from #43.
- Residual: the token appears once in the URL (shell history / browser history until the
  redirect). Threat-model-appropriate for a test-tooling dashboard; the token dies with the
  campaign anyway (owner-referenced Secret).

**B. NetworkPolicy only.** Zero code: ship a policy restricting ingress on the dashboard Service to
the driver pods + document it. Rejected as the *primary* control: policy enforcement depends on the
cluster's CNI, and kind's default CNI silently does not enforce NetworkPolicy — we will not ship
security that our own e2e cannot exercise. Retained as **defense-in-depth documentation** (an
example manifest in `deploy/`, marked "verify your CNI enforces this").

**C. Auth proxy sidecar (oauth2-proxy / ingress auth).** Real SSO, real users — and a hard external
dependency plus per-cluster IdP configuration, for a per-campaign throwaway dashboard. Rejected at
this stage; nothing in option A precludes it later.

## Decision (proposed)

Option **A**, plus B-as-documentation. In the same change, replace `guarded()`'s `String.equals`
token comparison with `MessageDigest.isEqual` (the #43 review's constant-time nit) — reads and
writes then share one hardened comparison path.

## Implementation sketch

1. `DashboardServer`: extract token comparison into one constant-time helper; add cookie
   issue/verify (`basquin_dash` cookie, value = the token itself — no server-side session state,
   the server stays stateless and restart-safe); guard all read handlers when a token is
   configured; `?token=` handoff endpoint with 302 redirect.
2. `basquin dashboard` CLI: fetch the token Secret, print the tokenized URL (and a bare URL +
   token separately, for header-based tooling).
3. e2e: after the campaign completes, assert an unauthenticated read gets 401/403 and a
   tokenized/cookied read gets 200 (both through the port-forward path the CLI uses).
4. Docs: OPERATOR-USAGE §6 security model updated — reads authenticated when operator-managed;
   NetworkPolicy example added with the CNI caveat.

## Non-goals

- Multi-user auth, roles, or audit — one shared token per campaign is the model, matching writes.
- Protecting the standalone (non-operator) dashboard by default — no token configured, no gate,
  unchanged local workflow.
