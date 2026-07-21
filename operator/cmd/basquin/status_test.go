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

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

func statusScheme(t *testing.T) *runtime.Scheme {
	t.Helper()
	s := runtime.NewScheme()
	if err := basquinv1alpha1.AddToScheme(s); err != nil {
		t.Fatal(err)
	}
	return s
}

func TestPrintStatusEmpty(t *testing.T) {
	c := fake.NewClientBuilder().WithScheme(statusScheme(t)).Build()
	var buf bytes.Buffer
	if err := printStatus(context.Background(), c, "ns", &buf); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if !strings.Contains(out, "TARGET") || !strings.Contains(out, "CAMPAIGN") {
		t.Errorf("both table headers should render:\n%s", out)
	}
	if strings.Count(out, "(none)") != 2 {
		t.Errorf("both tables should show (none):\n%s", out)
	}
}

func TestPrintStatusWithItems(t *testing.T) {
	tg := &basquinv1alpha1.BasquinTarget{
		ObjectMeta: metav1.ObjectMeta{Name: "jpetstore", Namespace: "ns"},
		Spec:       basquinv1alpha1.BasquinTargetSpec{DeploymentRef: basquinv1alpha1.DeploymentReference{Name: "jpetstore"}},
		Status:     basquinv1alpha1.BasquinTargetStatus{Phase: basquinv1alpha1.PhaseInjected, CoverageEndpoint: "ep:6300"},
	}
	cp := &basquinv1alpha1.BasquinCampaign{
		ObjectMeta: metav1.ObjectMeta{Name: "jpetstore-campaign", Namespace: "ns"},
		Spec:       basquinv1alpha1.BasquinCampaignSpec{TargetRef: basquinv1alpha1.TargetReference{Name: "jpetstore"}},
		Status:     basquinv1alpha1.BasquinCampaignStatus{Phase: basquinv1alpha1.CampaignCompleted, CoveragePct: "22.5", Findings: 72, DashboardURL: "http://d:7070"},
	}
	c := fake.NewClientBuilder().WithScheme(statusScheme(t)).WithObjects(tg, cp).Build()
	var buf bytes.Buffer
	if err := printStatus(context.Background(), c, "ns", &buf); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	for _, want := range []string{"jpetstore", "Injected", "ep:6300", "jpetstore-campaign", "Completed", "22.5", "72", "http://d:7070"} {
		if !strings.Contains(out, want) {
			t.Errorf("status output missing %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "(none)") {
		t.Errorf("should not show (none) when items exist:\n%s", out)
	}
}

func TestCampaignRowExplore(t *testing.T) {
	cp := &basquinv1alpha1.BasquinCampaign{
		ObjectMeta: metav1.ObjectMeta{Name: "jpetstore-campaign"},
		Spec: basquinv1alpha1.BasquinCampaignSpec{
			TargetRef: basquinv1alpha1.TargetReference{Name: "jpetstore"},
			// Mode left empty: defaults to explore.
		},
		Status: basquinv1alpha1.BasquinCampaignStatus{
			Phase:       basquinv1alpha1.CampaignCompleted,
			CoveragePct: "22.5",
			Findings:    72,
		},
	}
	row := campaignRow(cp)
	if !strings.Contains(row, "explore") {
		t.Errorf("expected row to show mode=explore:\n%s", row)
	}
	if !strings.Contains(row, "22.5") || !strings.Contains(row, "72 finds") {
		t.Errorf("expected row to show coverage/findings metrics:\n%s", row)
	}
	if strings.Contains(row, "rps") {
		t.Errorf("explore row should not show load metrics:\n%s", row)
	}
}

func TestCampaignRowLoad(t *testing.T) {
	cp := &basquinv1alpha1.BasquinCampaign{
		ObjectMeta: metav1.ObjectMeta{Name: "checkout-load"},
		Spec: basquinv1alpha1.BasquinCampaignSpec{
			TargetRef: basquinv1alpha1.TargetReference{Name: "jpetstore"},
			Mode:      "load",
		},
		Status: basquinv1alpha1.BasquinCampaignStatus{
			Phase: basquinv1alpha1.CampaignCompleted,
			Load: &basquinv1alpha1.LoadStatus{
				ThroughputRps: "713.4",
				LatencyMs:     basquinv1alpha1.LoadLatency{P50: 12, P90: 40, P99: 88, Max: 210},
			},
		},
	}
	row := campaignRow(cp)
	if !strings.Contains(row, "load") {
		t.Errorf("expected row to show mode=load:\n%s", row)
	}
	if !strings.Contains(row, "713.4") || !strings.Contains(row, "88") {
		t.Errorf("expected row to show rps/p99 load metrics:\n%s", row)
	}
	if strings.Contains(row, "finds") {
		t.Errorf("load row should not show explore metrics:\n%s", row)
	}
}

// TestCampaignRowLoadPending covers a load campaign still Running with no Status.Load published
// yet — the mode=="load" + nil-Load path the other two tests don't exercise. A regression that
// flips the nil-guard ordering (e.g. dereferencing Load before checking it, or falling through to
// the explore branch) would pass both other tests but should fail this one.
func TestCampaignRowLoadPending(t *testing.T) {
	cp := &basquinv1alpha1.BasquinCampaign{
		ObjectMeta: metav1.ObjectMeta{Name: "checkout-load"},
		Spec: basquinv1alpha1.BasquinCampaignSpec{
			TargetRef: basquinv1alpha1.TargetReference{Name: "jpetstore"},
			Mode:      "load",
		},
		Status: basquinv1alpha1.BasquinCampaignStatus{
			Phase: basquinv1alpha1.CampaignRunning,
			Load:  nil,
		},
	}
	row := campaignRow(cp) // must not panic on a nil Load
	if !strings.Contains(row, "load") {
		t.Errorf("expected row to show mode=load:\n%s", row)
	}
	if !strings.Contains(row, "pending") {
		t.Errorf("expected row to show a load-appropriate pending placeholder:\n%s", row)
	}
	if strings.Contains(row, "finds") {
		t.Errorf("pending load row should not show explore metrics:\n%s", row)
	}
}
