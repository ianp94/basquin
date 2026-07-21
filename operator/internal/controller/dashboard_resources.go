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
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

// P5b (docs/CAMPAIGN-DESIGN.md, DD-025). When a campaign has dashboard.enabled and no externalPush,
// the operator brings up a per-campaign dashboard: the standalone read-only DashboardServer (DD-013)
// as a Deployment + ClusterIP Service. The driver Job pushes status/findings to it. Both are
// owner-referenced to the campaign, so `kubectl delete basquincampaign` GCs them (the dashboard
// deliberately outlives the driver Job — results stay viewable until the campaign CR is removed).

const (
	defaultDashboardImage = "basquin/dashboard:latest"
	dashboardPort         = 7070
	dashboardTokenKey     = "token"
)

func dashboardName(c *basquinv1alpha1.BasquinCampaign) string { return c.Name + "-dashboard" }

// dashboardTokenSecretName is the per-campaign Secret holding the dashboard's shared token.
func dashboardTokenSecretName(c *basquinv1alpha1.BasquinCampaign) string {
	return c.Name + "-dashboard-token"
}

// dashboardTokenEnvVar is injected into BOTH the dashboard and the driver, and referenced from
// JAVA_TOOL_OPTIONS via Kubernetes' $(VAR) expansion — so the token value never appears literally in
// a Deployment/Job spec, only the SecretKeyRef does.
const dashboardTokenEnvVar = "BASQUIN_DASHBOARD_TOKEN"

// dashboardTokenEnv sources the token from the per-campaign Secret. Optional=false: if the Secret is
// missing the pod fails to start rather than silently coming up unauthenticated.
func dashboardTokenEnv(secretName string) corev1.EnvVar {
	return corev1.EnvVar{
		Name: dashboardTokenEnvVar,
		ValueFrom: &corev1.EnvVarSource{
			SecretKeyRef: &corev1.SecretKeySelector{
				LocalObjectReference: corev1.LocalObjectReference{Name: secretName},
				Key:                  dashboardTokenKey,
			},
		},
	}
}

// buildDashboardTokenSecret builds the per-campaign token Secret. Created once and never rotated
// mid-campaign: the dashboard reads it at JVM start, so rotating it would leave a running dashboard
// and a running driver disagreeing.
func buildDashboardTokenSecret(c *basquinv1alpha1.BasquinCampaign, token string) *corev1.Secret {
	return &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Name:      dashboardTokenSecretName(c),
			Namespace: c.Namespace,
			Labels:    dashboardSelector(c),
		},
		Type:       corev1.SecretTypeOpaque,
		StringData: map[string]string{dashboardTokenKey: token},
	}
}

func dashboardSelector(c *basquinv1alpha1.BasquinCampaign) map[string]string {
	return map[string]string{
		"app.kubernetes.io/managed-by": "basquin-operator",
		"app.kubernetes.io/component":  "dashboard",
		"basquin.dev/campaign":         c.Name,
	}
}

// buildDashboardDeployment builds the per-campaign dashboard Deployment (one replica of the
// DashboardServer image). Config is baked into the image entrypoint (bind 0.0.0.0, port 7070).
func buildDashboardDeployment(c *basquinv1alpha1.BasquinCampaign, image string, tokenSecret string) *appsv1.Deployment {
	replicas := int32(1)
	labels := dashboardSelector(c)
	// Order matters: the SecretKeyRef var must precede JAVA_TOOL_OPTIONS for $(VAR) to expand.
	env := []corev1.EnvVar{
		dashboardTokenEnv(tokenSecret),
		{Name: "JAVA_TOOL_OPTIONS", Value: "-Dbasquin.dashboard.token=$(" + dashboardTokenEnvVar + ")"},
	}
	return &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{Name: dashboardName(c), Namespace: c.Namespace, Labels: labels},
		Spec: appsv1.DeploymentSpec{
			Replicas: &replicas,
			Selector: &metav1.LabelSelector{MatchLabels: labels},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels},
				Spec: corev1.PodSpec{
					Containers: []corev1.Container{{
						Name:            "dashboard",
						Image:           image,
						ImagePullPolicy: corev1.PullIfNotPresent,
						Env:             env,
						Ports:           []corev1.ContainerPort{{ContainerPort: dashboardPort, Name: "http"}},
						// /healthz is the one deliberately unauthenticated endpoint (no campaign data):
						// probes can't send the token, and reads are token-gated since DD-028.
						ReadinessProbe: &corev1.Probe{
							ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{
								Path: "/healthz", Port: intstr.FromInt(dashboardPort)}},
							InitialDelaySeconds: 3, PeriodSeconds: 5,
						},
					}},
				},
			},
		},
	}
}

// buildDashboardService builds the ClusterIP Service fronting the dashboard Deployment.
func buildDashboardService(c *basquinv1alpha1.BasquinCampaign) *corev1.Service {
	return &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{Name: dashboardName(c), Namespace: c.Namespace, Labels: dashboardSelector(c)},
		Spec: corev1.ServiceSpec{
			Selector: dashboardSelector(c),
			Ports: []corev1.ServicePort{{
				Port: dashboardPort, TargetPort: intstr.FromInt(dashboardPort), Name: "http"}},
		},
	}
}
