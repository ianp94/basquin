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

import "testing"

func TestBuildCampaignIterations(t *testing.T) {
	cp := buildCampaign(runOpts{
		name: "c", namespace: "ns", target: "app", baseURL: "http://svc:8080",
		iterations: 200, dashboard: true, classesPath: "/x",
	}, "c-grammar", "g.grammar", "c-corpus")
	if cp.Spec.TargetRef.Name != "app" || cp.Spec.BaseURL != "http://svc:8080" {
		t.Fatalf("targetRef/baseURL = %q/%q", cp.Spec.TargetRef.Name, cp.Spec.BaseURL)
	}
	d := cp.Spec.Driver
	if d.Iterations != 200 || d.Duration != "" {
		t.Errorf("iterations/duration = %d/%q", d.Iterations, d.Duration)
	}
	if d.GrammarConfigMap != "c-grammar" || d.GrammarKey != "g.grammar" || d.CorpusConfigMap != "c-corpus" || d.ClassesPath != "/x" {
		t.Errorf("driver refs = %+v", d)
	}
	if cp.Spec.Dashboard.Enabled == nil || !*cp.Spec.Dashboard.Enabled {
		t.Error("dashboard should be enabled")
	}
}

func TestBuildCampaignDurationAndNoDashboard(t *testing.T) {
	cp := buildCampaign(runOpts{
		name: "c", namespace: "ns", target: "app", baseURL: "http://svc:8080",
		duration: "10m", dashboard: false, externalPush: "d:7070",
	}, "", "", "")
	if cp.Spec.Driver.Duration != "10m" || cp.Spec.Driver.Iterations != 0 {
		t.Errorf("duration/iterations = %q/%d", cp.Spec.Driver.Duration, cp.Spec.Driver.Iterations)
	}
	if cp.Spec.Dashboard.Enabled == nil || *cp.Spec.Dashboard.Enabled {
		t.Error("dashboard should be explicitly disabled")
	}
	if cp.Spec.Dashboard.ExternalPush != "d:7070" {
		t.Errorf("externalPush = %q", cp.Spec.Dashboard.ExternalPush)
	}
}

func TestValidateRun(t *testing.T) {
	cases := []struct {
		name string
		o    runOpts
		ok   bool
	}{
		{"missing target", runOpts{baseURL: "http://x", iterations: 1}, false},
		{"missing base-url", runOpts{target: "app", iterations: 1}, false},
		{"neither bound", runOpts{target: "app", baseURL: "http://x"}, false},
		{"both bounds", runOpts{target: "app", baseURL: "http://x", iterations: 1, duration: "10m"}, false},
		{"iterations ok", runOpts{target: "app", baseURL: "http://x", iterations: 1}, true},
		{"duration ok", runOpts{target: "app", baseURL: "http://x", duration: "10m"}, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := validateRun(tc.o)
			if tc.ok && err != nil {
				t.Errorf("expected ok, got %v", err)
			}
			if !tc.ok && err == nil {
				t.Errorf("expected an error, got nil")
			}
		})
	}
}
