#!/usr/bin/env python3
"""Collect a benchmark run's metrics out of the cluster into bench-results/.

The numbers on the public benchmark page have to be traceable to a real run, so this
script only ever *reads* — it snapshots what the operator and driver already produced:

  * the BasquinCampaign's full object (spec + status: coverage, findings, load stats)
  * the driver pod's terminal summary JSON (the authoritative load numbers, including
    the DD-038 redirect classification the operator's status doesn't surface)
  * the driver pod's log

Everything lands under bench-results/<app>/<campaign>/ as raw JSON, one directory per
campaign, so a later render step never has to talk to a cluster and anyone can diff a
published figure against the artifact it came from.

Usage:
  deploy/bench/collect.py <campaign> [<campaign> ...]   # by name, namespace basquin-system
  deploy/bench/collect.py --all                          # every campaign in the namespace
"""
import json
import pathlib
import re
import subprocess
import sys

NS = "basquin-system"
ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "bench-results"


def kubectl(*args: str) -> str:
    return subprocess.run(("kubectl", "-n", NS, *args), capture_output=True, text=True,
                          check=True).stdout


def campaign_names() -> list[str]:
    out = kubectl("get", "basquincampaign", "-o", "jsonpath={.items[*].metadata.name}")
    return out.split()


def driver_pod(campaign: str) -> str | None:
    """The driver Job's pod. Job name is <campaign>-driver (DD-025)."""
    out = kubectl("get", "pods", "-l", f"job-name={campaign}-driver",
                  "-o", "jsonpath={.items[*].metadata.name}").split()
    return out[0] if out else None


def terminal_summary(pod: str) -> dict | None:
    """The driver writes its end-of-run summary to /dev/termination-log; the kubelet
    surfaces it as the container's terminated message. This is the ONLY place the
    DD-038 redirects/redirectTargets counters appear — the operator's status subresource
    maps a subset."""
    raw = kubectl("get", "pod", pod, "-o",
                  "jsonpath={.status.containerStatuses[0].state.terminated.message}").strip()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        # An oversized/truncated termination log is itself worth recording rather than
        # silently dropping — the operator would drop the whole summary here too.
        return {"_unparseable": raw[:4096]}


# Redaction is deliberately NARROW and anchored to shapes we know carry a credential.
#
# A previous attempt matched any key containing "token"/"secret"/... anywhere, and it destroyed
# exactly what these artifacts exist to preserve: the corpus recipe `X-XSRF-TOKEN=${{csrf}}`
# contains "TOKEN", so it became `X-XSRF-TOKEN=<redacted>`. That recipe -- the substitution
# placeholder, never the captured value -- is the DD-036 invariant. A redactor that cannot tell a
# recipe from a secret is worse than none, because it corrupts evidence while claiming to protect it.
#
# So: match a credential-shaped KEY only in the three concrete forms we actually emit, and never
# touch a value that is a substitution placeholder.
_CRED_KEY = r"[\w.\-]*(?:token|secret|password|passwd|apikey|api[_-]?key|credential)[\w.\-]*"

# 1. JVM property:            -Dbasquin.dashboard.token=abc123
_RE_PROP = re.compile(rf"(-D{_CRED_KEY}=)(\S+)", re.IGNORECASE)
# 2. JSON field:              "token": "abc123"   /   "dashboardToken":"abc123"
_RE_JSON = re.compile(rf'("{_CRED_KEY}"\s*:\s*")([^"]*)(")', re.IGNORECASE)
# 3. Credential HTTP headers, which carry the secret in the VALUE with a fixed key.
_RE_HEADER = re.compile(r"^(\s*(?:Authorization|Proxy-Authorization|Set-Cookie|Cookie)\s*:\s*)(.+)$",
                        re.IGNORECASE | re.MULTILINE)

_PLACEHOLDER = "${{"


def _redact(text: str) -> str:
    """Strip credential VALUES from anything we persist, without touching corpus recipes.

    These artifacts are committed as benchmark evidence, so a credential in one is published. The
    driver log in particular opens with the JVM echoing every -D property it was given, which is how
    nine live dashboard tokens reached a public repository.

    A substitution placeholder (${{name}}) is a recipe, not a secret -- it is precisely what DD-036
    says must be on disk instead of the captured value -- so it is never redacted.
    """
    def sub_prop(m):
        return m.group(1) + ("<redacted>" if not m.group(2).startswith(_PLACEHOLDER) else m.group(2))

    def sub_json(m):
        return m.group(1) + ("<redacted>" if not m.group(2).startswith(_PLACEHOLDER) else m.group(2)) + m.group(3)

    def sub_header(m):
        return m.group(1) + ("<redacted>" if not m.group(2).startswith(_PLACEHOLDER) else m.group(2))

    text = _RE_PROP.sub(sub_prop, text)
    text = _RE_JSON.sub(sub_json, text)
    return _RE_HEADER.sub(sub_header, text)


def collect(campaign: str) -> dict | None:
    try:
        obj = json.loads(kubectl("get", "basquincampaign", campaign, "-o", "json"))
    except subprocess.CalledProcessError:
        print(f"  !! {campaign}: no such campaign", file=sys.stderr)
        return None

    app = obj["spec"]["targetRef"]["name"]
    # The invariant budgets live on the BasquinTarget, not the campaign, and they differ per app
    # (jspwiki 25ms/256KB, roller 250ms/512KB). The page must render the budget each run was
    # actually measured against rather than one hardcoded pair.
    try:
        target = json.loads(kubectl("get", "basquintarget", app, "-o", "json"))
        invariants = target.get("spec", {}).get("invariants")
    except subprocess.CalledProcessError:
        invariants = None
    dest = OUT / app / campaign
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "campaign.json").write_text(_redact(json.dumps(obj, indent=2)))

    summary = None
    try:
        pod = driver_pod(campaign)
    except subprocess.CalledProcessError:
        # A campaign whose driver pod is gone or unreadable degrades to "no driver data for this
        # campaign" -- it must never abort the rest of a sweep, which is what the docstring promises.
        print(f"  !! {campaign}: driver pod unreadable; recording campaign object only", file=sys.stderr)
        pod = None
    if pod:
        summary = terminal_summary(pod)
        if summary is not None:
            (dest / "summary.json").write_text(_redact(json.dumps(summary, indent=2)))
        try:
            # NEVER write a driver log verbatim: its first line is the JVM's
            # "Picked up JAVA_TOOL_OPTIONS: ..." echo, which contains the campaign's
            # dashboard auth token. Nine live tokens reached a public repo this way
            # before an approver caught it.
            (dest / "driver.log").write_text(_redact(kubectl("logs", pod)))
        except subprocess.CalledProcessError:
            pass  # log already rotated away; the summary is the load-bearing artifact

    status = obj.get("status", {})
    rec = {
        "campaign": campaign,
        "app": app,
        "mode": obj["spec"]["mode"],
        "driver": obj["spec"].get("driver", {}),
        "phase": status.get("phase"),
        "startTime": status.get("startTime"),
        "completionTime": status.get("completionTime"),
        # coveragePct is a string on the CRD (a quantity, not a float) — keep it as
        # the operator wrote it and let the render step parse.
        "coveragePct": status.get("coveragePct"),
        # CAUTION: status.findings is NOT a violation count. The operator assigns
        # Status.Findings = summary.exploration.corpus, i.e. the number of inputs the
        # explorer RETAINED as interesting. The real per-class counts only exist in the
        # driver's own summary, so carry that block through and let the render step use
        # it rather than the friendlier-sounding CRD field.
        "findingsCrdCorpusSize": status.get("findings"),
        "exploration": (summary or {}).get("exploration"),
        "iterations": (summary or {}).get("iterations"),
        "crashes": (summary or {}).get("crashes"),
        "corpusConfigMap": status.get("corpusConfigMap"),
        "message": next((c.get("message") for c in status.get("conditions", [])
                         if c.get("type") == "Ready"), None),
        "load": summary.get("load") if summary else status.get("load"),
        "invariants": invariants,
        "artifacts": str(dest.relative_to(ROOT)),
    }
    (dest / "record.json").write_text(_redact(json.dumps(rec, indent=2)))
    print(f"  ok {campaign:<28} {obj['spec']['mode']:<8} {status.get('phase')}"
          f"  -> {dest.relative_to(ROOT)}")
    return rec


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    names = campaign_names() if args == ["--all"] else args

    records = [r for r in (collect(n) for n in names) if r]
    index = OUT / "campaigns.json"
    # Merge with anything collected on an earlier invocation so a battery can be built
    # up run-by-run instead of demanding one all-or-nothing sweep.
    prior = {}
    if index.exists():
        prior = {r["campaign"]: r for r in json.loads(index.read_text())}
    prior.update({r["campaign"]: r for r in records})
    merged = sorted(prior.values(), key=lambda r: (r["app"], r["mode"], r["campaign"]))
    index.write_text(json.dumps(merged, indent=2))
    print(f"\n{len(records)} collected, {len(merged)} total -> {index.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
