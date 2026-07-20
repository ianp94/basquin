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

func TestBuildTargetMinimal(t *testing.T) {
	tg := buildTarget(instrumentOpts{name: "app", namespace: "ns", deployment: "app", threadTracker: true})
	if tg.Name != "app" || tg.Namespace != "ns" {
		t.Fatalf("name/ns = %s/%s", tg.Name, tg.Namespace)
	}
	if tg.Spec.DeploymentRef.Name != "app" {
		t.Errorf("deploymentRef = %q", tg.Spec.DeploymentRef.Name)
	}
	if !tg.Spec.Agents.ThreadTracker {
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

func TestValidateInstrument(t *testing.T) {
	cases := []struct {
		name string
		o    instrumentOpts
		ok   bool
	}{
		{"missing deployment", instrumentOpts{}, false},
		{"coverage-service without includes", instrumentOpts{deployment: "app", coverageService: true}, false},
		{"coverage-service with includes", instrumentOpts{deployment: "app", coverageService: true, coverageIncludes: "com.x.*"}, true},
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
