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
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
)

func TestReadCorpusDir(t *testing.T) {
	dir := t.TempDir()
	// top-level route-seed files + a values/ subdir + an unrelated nested dir (must be skipped).
	mustWrite(t, filepath.Join(dir, "catalog_item.txt"), "/actions/Catalog.action\n")
	mustWrite(t, filepath.Join(dir, "cart_add.txt"), "/actions/Cart.action\n")
	if err := os.Mkdir(filepath.Join(dir, "values"), 0o755); err != nil {
		t.Fatal(err)
	}
	mustWrite(t, filepath.Join(dir, "values", "categoryId.txt"), "FISH\nDOGS\n")
	if err := os.Mkdir(filepath.Join(dir, "nested"), 0o755); err != nil {
		t.Fatal(err)
	}
	mustWrite(t, filepath.Join(dir, "nested", "ignored.txt"), "x\n")

	data, err := readCorpusDir(dir)
	if err != nil {
		t.Fatalf("readCorpusDir: %v", err)
	}
	for _, k := range []string{"catalog_item.txt", "cart_add.txt", "categoryId.txt"} {
		if _, ok := data[k]; !ok {
			t.Errorf("missing key %q", k)
		}
	}
	if _, ok := data["ignored.txt"]; ok {
		t.Error("nested subdir files must not be included (non-recursive)")
	}
	if data["categoryId.txt"] != "FISH\nDOGS\n" {
		t.Errorf("values file content = %q", data["categoryId.txt"])
	}
}

func TestReadCorpusDirBasenameCollision(t *testing.T) {
	dir := t.TempDir()
	mustWrite(t, filepath.Join(dir, "dup.txt"), "top\n")
	if err := os.Mkdir(filepath.Join(dir, "values"), 0o755); err != nil {
		t.Fatal(err)
	}
	mustWrite(t, filepath.Join(dir, "values", "dup.txt"), "nested\n")
	if _, err := readCorpusDir(dir); err == nil {
		t.Error("expected a basename-collision error")
	}
}

func TestReadCorpusDirMissing(t *testing.T) {
	if _, err := readCorpusDir(filepath.Join(t.TempDir(), "nope")); err == nil {
		t.Error("expected an error for a missing --corpus directory")
	}
}

func mustWrite(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestReadEmittedCorpus(t *testing.T) {
	scheme := runtime.NewScheme()
	if err := corev1.AddToScheme(scheme); err != nil {
		t.Fatal(err)
	}
	c := fake.NewClientBuilder().WithScheme(scheme).WithObjects(
		&corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{Name: "exp-corpus-out", Namespace: "ns"},
			Data:       map[string]string{"corpus.txt": "/a\n/b\n"},
		},
		&corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{Name: "empty-corpus-out", Namespace: "ns"},
		},
	).Build()
	ctx := context.Background()

	data, err := readEmittedCorpus(ctx, c, "exp", "ns")
	if err != nil {
		t.Fatalf("readEmittedCorpus: %v", err)
	}
	if data["corpus.txt"] != "/a\n/b\n" {
		t.Errorf("corpus.txt = %q", data["corpus.txt"])
	}

	if _, err := readEmittedCorpus(ctx, c, "nope", "ns"); err == nil {
		t.Error("expected an error for a missing -corpus-out ConfigMap")
	} else if !strings.Contains(err.Error(), "nope-corpus-out") {
		t.Errorf("missing-CM error should name the ConfigMap looked for, got %v", err)
	}

	if _, err := readEmittedCorpus(ctx, c, "empty", "ns"); err == nil {
		t.Error("expected an error for an empty -corpus-out ConfigMap")
	}
}

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
		{"load with corpus dir ok", runOpts{target: "app", baseURL: "http://x", duration: "10m", mode: "load", corpusDir: "./c"}, true},
		{"load with corpus-from ok", runOpts{target: "app", baseURL: "http://x", duration: "10m", mode: "load", corpusFrom: "exp"}, true},
		{"load with both corpus sources", runOpts{target: "app", baseURL: "http://x", duration: "10m", mode: "load", corpusDir: "./c", corpusFrom: "exp"}, false},
		{"load with neither corpus source", runOpts{target: "app", baseURL: "http://x", duration: "10m", mode: "load"}, false},
		{"explore with corpus-from", runOpts{target: "app", baseURL: "http://x", iterations: 1, mode: "explore", corpusFrom: "exp"}, false},
		{"default mode with corpus-from", runOpts{target: "app", baseURL: "http://x", iterations: 1, corpusFrom: "exp"}, false},
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
