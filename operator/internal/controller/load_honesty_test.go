/*
Copyright 2026.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package controller

import (
	"encoding/json"
	"strings"
	"testing"

	batchv1 "k8s.io/api/batch/v1"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

// DD-040. Omitting a field at the DRIVER is theater unless the consumers change too: with
// non-pointer int32 fields an omitted `violations.latency` unmarshals straight back to 0, so the
// operator re-fabricates the very zero the driver refused to print, one layer up. These tests pin
// the round trip end to end — driver JSON in, Ready-condition message out.

func loadStatusFromSummary(t *testing.T, summary string) *basquinv1alpha1.LoadStatus {
	t.Helper()
	var s driverSummary
	if err := json.Unmarshal([]byte(summary), &s); err != nil {
		t.Fatalf("driver summary must parse: %v", err)
	}
	if s.Load == nil {
		t.Fatal("summary has no load block")
	}
	return s.Load
}

func TestOmittedViolationCountDoesNotBecomeZero(t *testing.T) {
	// What a load driver with no configured latency threshold now emits.
	ld := loadStatusFromSummary(t, `{"load":{"requests":1000,"throughputRps":"250.5",`+
		`"latencyMs":{"p50":503,"p90":700,"p99":900,"max":1200},"heapDriftKb":512,"threadDrift":1,`+
		`"violations":{"notEvaluated":["latency","heap","thread"]}}}`)

	if ld.Violations.Latency != nil {
		t.Errorf("an omitted latency count must stay absent, got %d", *ld.Violations.Latency)
	}
	if ld.Violations.Heap != nil || ld.Violations.Thread != nil {
		t.Error("load mode never evaluates heap/thread; those must not materialize as 0")
	}
	if len(ld.Violations.NotEvaluated) != 3 {
		t.Errorf("the explicit marker must survive the round trip, got %v", ld.Violations.NotEvaluated)
	}
}

func TestMeasuredZeroStaysDistinguishableFromAbsent(t *testing.T) {
	// The other half of the contract: a real, checked-and-clean 0 must still be a 0.
	ld := loadStatusFromSummary(t, `{"load":{"requests":10,"throughputRps":"1.0",`+
		`"latencyMs":{"p99":9},"violations":{"latency":0,"notEvaluated":["heap","thread"]}}}`)

	if ld.Violations.Latency == nil {
		t.Fatal("a measured 0 must round trip as a present 0, not as absent")
	}
	if *ld.Violations.Latency != 0 {
		t.Errorf("latency = %d, want 0", *ld.Violations.Latency)
	}
}

// DD-035's driftUnavailable was emitted by the driver and parsed NOWHERE, so a drift the target
// never confirmed still reached the dashboard as `0 KiB`. Pointers plus a parsed flag close it.
func TestDriftUnavailableIsActuallyParsed(t *testing.T) {
	ld := loadStatusFromSummary(t, `{"load":{"requests":10,"throughputRps":"1.0",`+
		`"latencyMs":{"p99":9},"driftUnavailable":true,"violations":{"latency":2}}}`)

	if !ld.DriftUnavailable {
		t.Error("driftUnavailable must be parsed into status, not silently dropped")
	}
	if ld.HeapDriftKb != nil || ld.ThreadDrift != nil {
		t.Error("an unavailable drift must not materialize as 0")
	}
}

func TestLoadReadyMessageNeverPrintsACountItDoesNotHave(t *testing.T) {
	zero := int32(0)
	three := int32(3)

	cases := []struct {
		name        string
		ld          *basquinv1alpha1.LoadStatus
		wantContain string
		wantAbsent  string
	}{
		{
			name:        "unevaluated",
			ld:          &basquinv1alpha1.LoadStatus{Requests: 1000, ThroughputRps: "250.5"},
			wantContain: "latency violations not evaluated",
			wantAbsent:  "0 latency violations",
		},
		{
			name: "measured zero",
			ld: &basquinv1alpha1.LoadStatus{Requests: 1000, ThroughputRps: "250.5",
				Violations: basquinv1alpha1.LoadViolations{Latency: &zero}},
			wantContain: "0 latency violations",
		},
		{
			name: "measured nonzero",
			ld: &basquinv1alpha1.LoadStatus{Requests: 1000, ThroughputRps: "250.5",
				Violations: basquinv1alpha1.LoadViolations{Latency: &three}},
			wantContain: "3 latency violations",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			msg := loadReadyMessage(tc.ld)
			if !strings.Contains(msg, tc.wantContain) {
				t.Errorf("message %q does not contain %q", msg, tc.wantContain)
			}
			if tc.wantAbsent != "" && strings.Contains(msg, tc.wantAbsent) {
				t.Errorf("message %q must not claim %q", msg, tc.wantAbsent)
			}
		})
	}
}

// The trap: buildDriverJob already propagated -Dbasquin.invariant.latency.maxMs from
// campaign.spec.driver.invariants, so "propagate latencyMaxMs" looks done. But operators set the
// threshold on the BasquinTarget, which only ever reached the TARGET jvm — and in load mode the
// target's valve is in lock-free passthrough and evaluates nothing, so the number was checked by
// nobody. The load driver must inherit the target's threshold.
func TestLoadDriverInheritsTargetLatencyThreshold(t *testing.T) {
	targetInv := basquinv1alpha1.InvariantsSpec{Mode: "soft", LatencyMaxMs: 250}

	t.Run("load inherits the target's threshold", func(t *testing.T) {
		c := tokenTestCampaign()
		c.Spec.Mode = "load"
		jto := driverJavaToolOptions(t, buildDriverJob(c, targetInv, "app:1", "cov:6300", "runner:1", "", ""))
		if !strings.Contains(jto, "-Dbasquin.invariant.latency.maxMs=250") {
			t.Errorf("load driver did not inherit the target's latency budget: %q", jto)
		}
	})

	t.Run("the campaign's own invariants still win", func(t *testing.T) {
		c := tokenTestCampaign()
		c.Spec.Mode = "load"
		c.Spec.Driver.Invariants.LatencyMaxMs = 800
		jto := driverJavaToolOptions(t, buildDriverJob(c, targetInv, "app:1", "cov:6300", "runner:1", "", ""))
		if !strings.Contains(jto, "-Dbasquin.invariant.latency.maxMs=800") {
			t.Errorf("campaign override must win over the target's threshold: %q", jto)
		}
		if strings.Contains(jto, "maxMs=250") {
			t.Errorf("target threshold must not also be emitted: %q", jto)
		}
	})

	// Explore must NOT inherit: there the TARGET's own agent evaluates the invariant and the DD-040
	// result channel reports it back as a finding. A driver-side copy of the same budget would count
	// a second, differently-scoped (client round-trip) violation for the same request.
	t.Run("explore does not inherit", func(t *testing.T) {
		c := tokenTestCampaign()
		jto := driverJavaToolOptions(t, buildDriverJob(c, targetInv, "app:1", "cov:6300", "runner:1", "", ""))
		if strings.Contains(jto, "-Dbasquin.invariant.latency.maxMs") {
			t.Errorf("explore driver must not double-count the target's latency invariant: %q", jto)
		}
	})

	t.Run("no threshold anywhere means no flag", func(t *testing.T) {
		c := tokenTestCampaign()
		c.Spec.Mode = "load"
		jto := driverJavaToolOptions(t, buildDriverJob(c, basquinv1alpha1.InvariantsSpec{}, "app:1", "cov:6300", "runner:1", "", ""))
		if strings.Contains(jto, "-Dbasquin.invariant.latency.maxMs") {
			t.Errorf("nothing configured must stay nothing configured: %q", jto)
		}
	})
}

func driverJavaToolOptions(t *testing.T, job *batchv1.Job) string {
	t.Helper()
	jto, _, ok := envByName(job.Spec.Template.Spec.Containers[0].Env, "JAVA_TOOL_OPTIONS")
	if !ok {
		t.Fatal("driver container has no JAVA_TOOL_OPTIONS")
	}
	return jto.Value
}
