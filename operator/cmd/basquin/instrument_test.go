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

package main

import (
	"bytes"
	"context"
	"strings"
	"testing"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

func TestBuildTargetMinimal(t *testing.T) {
	tg := buildTarget(instrumentOpts{name: "app", namespace: "ns", deployment: "app", threadTracker: true})
	if tg.Name != "app" || tg.Namespace != "ns" {
		t.Fatalf("name/ns = %s/%s", tg.Name, tg.Namespace)
	}
	if tg.Spec.DeploymentRef.Name != "app" {
		t.Errorf("deploymentRef = %q", tg.Spec.DeploymentRef.Name)
	}
	if tg.Spec.Agents.ThreadTracker == nil || !*tg.Spec.Agents.ThreadTracker {
		t.Error("threadTracker should be on")
	}
	if tg.Spec.Agents.Coverage.Enabled {
		t.Error("coverage must not be enabled without includes")
	}
	if tg.Spec.JVMOptsVar != "" {
		t.Errorf("jvmOptsVar should be empty (CRD default), got %q", tg.Spec.JVMOptsVar)
	}
}

func TestBuildTargetCoverageAndInvariants(t *testing.T) {
	tg := buildTarget(instrumentOpts{
		name: "app", namespace: "ns", deployment: "app", container: "web",
		jvmOptsVar: "CATALINA_OPTS", coverageIncludes: "com.x.*", coveragePort: 6300,
		coverageService: true, threadTracker: true,
		invariantMode: "soft", latencyMaxMs: 25, heapDeltaMaxKb: 256,
	})
	if tg.Spec.Container != "web" || tg.Spec.JVMOptsVar != "CATALINA_OPTS" {
		t.Errorf("container/var = %q/%q", tg.Spec.Container, tg.Spec.JVMOptsVar)
	}
	cov := tg.Spec.Agents.Coverage
	if !cov.Enabled || cov.Includes != "com.x.*" || cov.Port != 6300 {
		t.Errorf("coverage = %+v", cov)
	}
	if !tg.Spec.CoverageService {
		t.Error("coverageService should be on")
	}
	inv := tg.Spec.Invariants
	if inv.Mode != "soft" || inv.LatencyMaxMs != 25 || inv.HeapDeltaMaxKb != 256 {
		t.Errorf("invariants = %+v", inv)
	}
}

func TestBuildTargetCoveragePortDefaults(t *testing.T) {
	tg := buildTarget(instrumentOpts{deployment: "app", coverageIncludes: "com.x.*", coveragePort: 0})
	if tg.Spec.Agents.Coverage.Port != 6300 {
		t.Errorf("port should default to 6300, got %d", tg.Spec.Agents.Coverage.Port)
	}
}

func TestBuildTargetThreadTrackerFalseSurvives(t *testing.T) {
	// The *bool must carry an explicit false to the CR (a plain bool + omitempty would drop it and the
	// CRD default would re-enable it).
	tg := buildTarget(instrumentOpts{deployment: "app", threadTracker: false})
	if tg.Spec.Agents.ThreadTracker == nil {
		t.Fatal("threadTracker should be an explicit pointer, not nil")
	}
	if *tg.Spec.Agents.ThreadTracker {
		t.Error("threadTracker=false must be preserved")
	}
}

func TestValidateInstrument(t *testing.T) {
	cases := []struct {
		name string
		o    instrumentOpts
		ok   bool
	}{
		{"missing deployment", instrumentOpts{}, false},
		{"coverage-service without includes", instrumentOpts{deployment: "app", coverageService: true}, false},
		{"coverage-service with includes", instrumentOpts{deployment: "app", coverageService: true, coverageIncludes: "com.x.*"}, true},
		{"bad jvm-opts-var", instrumentOpts{deployment: "app", jvmOptsVar: "FOO"}, false},
		{"good jvm-opts-var", instrumentOpts{deployment: "app", jvmOptsVar: "CATALINA_OPTS"}, true},
		{"bad invariant-mode", instrumentOpts{deployment: "app", invariantMode: "loud"}, false},
		{"good invariant-mode", instrumentOpts{deployment: "app", invariantMode: "hard"}, true},
		{"minimal valid", instrumentOpts{deployment: "app"}, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := validateInstrument(tc.o)
			if tc.ok && err != nil {
				t.Errorf("expected ok, got %v", err)
			}
			if !tc.ok && err == nil {
				t.Errorf("expected an error, got nil")
			}
		})
	}
}

// waitTarget builds a BasquinTarget already at the given phase with ObservedGeneration caught up
// to Generation, so waitForInjected's "has the controller observed THIS spec" gate passes on the
// very first poll — the tests below don't want to depend on real polling/sleep timing.
func waitTarget(phase basquinv1alpha1.TargetPhase) *basquinv1alpha1.BasquinTarget {
	return &basquinv1alpha1.BasquinTarget{
		ObjectMeta: metav1.ObjectMeta{Name: "app", Namespace: "ns", Generation: 1},
		Status:     basquinv1alpha1.BasquinTargetStatus{Phase: phase, ObservedGeneration: 1, CoverageEndpoint: "ep:6300"},
	}
}

func TestWaitForInjectedInjectedSuccess(t *testing.T) {
	c := fake.NewClientBuilder().WithScheme(statusScheme(t)).WithObjects(waitTarget(basquinv1alpha1.PhaseInjected)).Build()
	var buf bytes.Buffer
	key := types.NamespacedName{Namespace: "ns", Name: "app"}
	if err := waitForInjected(context.Background(), c, key, time.Second, &buf); err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	out := buf.String()
	if !strings.Contains(out, "Injected ✓") {
		t.Errorf("expected an Injected success line, got:\n%s", out)
	}
	if strings.Contains(out, "Observed") {
		t.Errorf("Injected success line must not mention Observed:\n%s", out)
	}
}

// TestWaitForInjectedObservedSuccess is the DD-044 fix under test: against a pre-instrumented
// target the controller never writes Injected — it writes Observed — so waitForInjected must
// accept that phase too (§7.7), with a success line distinguishable from the Injected one and
// that does not claim the operator "instrumented" anything (it didn't; the target already was).
func TestWaitForInjectedObservedSuccess(t *testing.T) {
	c := fake.NewClientBuilder().WithScheme(statusScheme(t)).WithObjects(waitTarget(basquinv1alpha1.PhaseObserved)).Build()
	var buf bytes.Buffer
	key := types.NamespacedName{Namespace: "ns", Name: "app"}
	if err := waitForInjected(context.Background(), c, key, time.Second, &buf); err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	out := buf.String()
	if !strings.Contains(out, "Observed ✓") {
		t.Errorf("expected an Observed success line, got:\n%s", out)
	}
	if strings.Contains(out, "Injected") {
		t.Errorf("Observed success line must be distinct from the Injected one, got:\n%s", out)
	}
	if strings.Contains(out, "instrumented") {
		t.Errorf("Observed success line must not claim the operator instrumented the target, got:\n%s", out)
	}
}

func TestWaitForInjectedErrorFailure(t *testing.T) {
	c := fake.NewClientBuilder().WithScheme(statusScheme(t)).WithObjects(waitTarget(basquinv1alpha1.PhaseError)).Build()
	var buf bytes.Buffer
	key := types.NamespacedName{Namespace: "ns", Name: "app"}
	err := waitForInjected(context.Background(), c, key, time.Second, &buf)
	if err == nil {
		t.Fatal("expected an error for a target in Error phase")
	}
	if !strings.Contains(err.Error(), "Error") {
		t.Errorf("error should mention the Error phase, got %v", err)
	}
}

func TestWaitForInjectedTimeout(t *testing.T) {
	// Pending never becomes a terminal phase, so this must time out rather than hang or succeed.
	c := fake.NewClientBuilder().WithScheme(statusScheme(t)).WithObjects(waitTarget(basquinv1alpha1.PhasePending)).Build()
	var buf bytes.Buffer
	key := types.NamespacedName{Namespace: "ns", Name: "app"}
	err := waitForInjected(context.Background(), c, key, 10*time.Millisecond, &buf)
	if err == nil {
		t.Fatal("expected a timeout error")
	}
	if !strings.Contains(err.Error(), "timed out") {
		t.Errorf("expected a timeout error, got %v", err)
	}
}
