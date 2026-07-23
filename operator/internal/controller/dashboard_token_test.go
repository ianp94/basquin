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
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

func tokenTestCampaign() *basquinv1alpha1.BasquinCampaign {
	return &basquinv1alpha1.BasquinCampaign{
		ObjectMeta: metav1.ObjectMeta{Name: "camp", Namespace: "ns"},
		Spec: basquinv1alpha1.BasquinCampaignSpec{
			BaseURL:   "http://app.ns.svc.cluster.local:8080",
			TargetRef: basquinv1alpha1.TargetReference{Name: "t"},
		},
	}
}

// envByName is a small helper: env lookup plus the index, since $(VAR) expansion depends on ORDER.
func envByName(env []corev1.EnvVar, name string) (corev1.EnvVar, int, bool) {
	for i, e := range env {
		if e.Name == name {
			return e, i, true
		}
	}
	return corev1.EnvVar{}, -1, false
}

// The token must reach the JVM as a system property WITHOUT the literal value appearing in the pod
// spec — that's the whole point of the SecretKeyRef + $(VAR) indirection.
func TestDashboardDeploymentWiresTokenFromSecret(t *testing.T) {
	c := tokenTestCampaign()
	dep := buildDashboardDeployment(c, "img", dashboardTokenSecretName(c))
	env := dep.Spec.Template.Spec.Containers[0].Env

	tok, tokIdx, ok := envByName(env, dashboardTokenEnvVar)
	if !ok {
		t.Fatalf("dashboard has no %s env var", dashboardTokenEnvVar)
	}
	if tok.ValueFrom == nil || tok.ValueFrom.SecretKeyRef == nil {
		t.Fatalf("%s must come from a SecretKeyRef, got %+v", dashboardTokenEnvVar, tok)
	}
	if got, want := tok.ValueFrom.SecretKeyRef.Name, "camp-dashboard-token"; got != want {
		t.Errorf("secret name = %q, want %q", got, want)
	}
	if got, want := tok.ValueFrom.SecretKeyRef.Key, dashboardTokenKey; got != want {
		t.Errorf("secret key = %q, want %q", got, want)
	}
	if tok.Value != "" {
		t.Errorf("token env carries a literal value %q; it must only reference the Secret", tok.Value)
	}

	jto, jtoIdx, ok := envByName(env, "JAVA_TOOL_OPTIONS")
	if !ok {
		t.Fatal("dashboard has no JAVA_TOOL_OPTIONS")
	}
	if !strings.Contains(jto.Value, "-Dbasquin.dashboard.token=$("+dashboardTokenEnvVar+")") {
		t.Errorf("JAVA_TOOL_OPTIONS does not reference the token var: %q", jto.Value)
	}
	// Kubernetes only expands $(VAR) against vars declared EARLIER in the list. If this regresses the
	// dashboard silently starts with the literal string "$(BASQUIN_DASHBOARD_TOKEN)" as its token.
	if tokIdx >= jtoIdx {
		t.Errorf("%s (index %d) must precede JAVA_TOOL_OPTIONS (index %d) for $(VAR) expansion",
			dashboardTokenEnvVar, tokIdx, jtoIdx)
	}
}

func TestDriverJobTokenWiring(t *testing.T) {
	c := tokenTestCampaign()

	t.Run("operator-owned dashboard gets the token", func(t *testing.T) {
		job := buildDriverJob(c, basquinv1alpha1.InvariantsSpec{}, "app:1", "cov:6300", "runner:1", "dash:7070", dashboardTokenSecretName(c))
		env := job.Spec.Template.Spec.Containers[0].Env

		tok, tokIdx, ok := envByName(env, dashboardTokenEnvVar)
		if !ok {
			t.Fatalf("driver has no %s env var", dashboardTokenEnvVar)
		}
		if tok.ValueFrom == nil || tok.ValueFrom.SecretKeyRef == nil {
			t.Fatal("driver token must come from a SecretKeyRef")
		}
		jto, jtoIdx, _ := envByName(env, "JAVA_TOOL_OPTIONS")
		if !strings.Contains(jto.Value, "-Dbasquin.dashboard.token=$("+dashboardTokenEnvVar+")") {
			t.Errorf("driver JAVA_TOOL_OPTIONS lacks the token prop: %q", jto.Value)
		}
		if tokIdx >= jtoIdx {
			t.Errorf("token var (%d) must precede JAVA_TOOL_OPTIONS (%d)", tokIdx, jtoIdx)
		}
	})

	// externalPush points at a dashboard we don't own and have no token for. Sending one would be
	// meaningless; mounting a Secret that may not exist would wedge the pod on startup.
	t.Run("external dashboard gets no token", func(t *testing.T) {
		job := buildDriverJob(c, basquinv1alpha1.InvariantsSpec{}, "app:1", "cov:6300", "runner:1", "external:7070", "")
		env := job.Spec.Template.Spec.Containers[0].Env

		if _, _, ok := envByName(env, dashboardTokenEnvVar); ok {
			t.Error("driver must not mount a token Secret when pushing to an external dashboard")
		}
		jto, _, _ := envByName(env, "JAVA_TOOL_OPTIONS")
		if strings.Contains(jto.Value, "dashboard.token") {
			t.Errorf("driver must not set a token prop for an external dashboard: %q", jto.Value)
		}
		if !strings.Contains(jto.Value, "-Dbasquin.dashboard.push=external:7070") {
			t.Errorf("driver should still push to the external dashboard: %q", jto.Value)
		}
	})

	t.Run("no dashboard means no token and no push", func(t *testing.T) {
		job := buildDriverJob(c, basquinv1alpha1.InvariantsSpec{}, "app:1", "cov:6300", "runner:1", "", "")
		env := job.Spec.Template.Spec.Containers[0].Env

		if _, _, ok := envByName(env, dashboardTokenEnvVar); ok {
			t.Error("driver must not mount a token Secret when the dashboard is disabled")
		}
		jto, _, _ := envByName(env, "JAVA_TOOL_OPTIONS")
		if strings.Contains(jto.Value, "dashboard.") {
			t.Errorf("driver should carry no dashboard props when disabled: %q", jto.Value)
		}
	})
}

func TestDashboardTokenSecretShape(t *testing.T) {
	c := tokenTestCampaign()
	tok, err := newDashboardToken()
	if err != nil {
		t.Fatalf("newDashboardToken: %v", err)
	}
	// 32 random bytes hex-encoded.
	if len(tok) != 64 {
		t.Errorf("token length = %d, want 64 hex chars (256 bits)", len(tok))
	}
	if other, _ := newDashboardToken(); other == tok {
		t.Error("two generated tokens are identical; the source is not random")
	}

	s := buildDashboardTokenSecret(c, tok)
	if s.Name != "camp-dashboard-token" || s.Namespace != "ns" {
		t.Errorf("secret identity = %s/%s", s.Namespace, s.Name)
	}
	if s.Type != corev1.SecretTypeOpaque {
		t.Errorf("secret type = %q, want Opaque", s.Type)
	}
	if s.StringData[dashboardTokenKey] != tok {
		t.Errorf("secret does not carry the token under key %q", dashboardTokenKey)
	}
}
