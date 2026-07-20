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

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

// P5b (docs/CAMPAIGN-DESIGN.md, DD-025). When a campaign has dashboard.enabled and no externalPush,
// the operator brings up a per-campaign dashboard: the standalone read-only DashboardServer (DD-013)
// as a Deployment + ClusterIP Service. The driver Job pushes status/findings to it. Both are
// owner-referenced to the campaign, so `kubectl delete closurejvmcampaign` GCs them (the dashboard
// deliberately outlives the driver Job — results stay viewable until the campaign CR is removed).

const (
	defaultDashboardImage = "closurejvm/dashboard:latest"
	dashboardPort         = 7070
)

func dashboardName(c *closurejvmv1alpha1.ClosureJVMCampaign) string { return c.Name + "-dashboard" }

func dashboardSelector(c *closurejvmv1alpha1.ClosureJVMCampaign) map[string]string {
	return map[string]string{
		"app.kubernetes.io/managed-by": "closurejvm-operator",
		"app.kubernetes.io/component":  "dashboard",
		"closurejvm.dev/campaign":      c.Name,
	}
}

// buildDashboardDeployment builds the per-campaign dashboard Deployment (one replica of the
// DashboardServer image). Config is baked into the image entrypoint (bind 0.0.0.0, port 7070).
func buildDashboardDeployment(c *closurejvmv1alpha1.ClosureJVMCampaign, image string) *appsv1.Deployment {
	replicas := int32(1)
	labels := dashboardSelector(c)
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
						Ports:           []corev1.ContainerPort{{ContainerPort: dashboardPort, Name: "http"}},
						ReadinessProbe: &corev1.Probe{
							ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{
								Path: "/api/campaigns", Port: intstr.FromInt(dashboardPort)}},
							InitialDelaySeconds: 3, PeriodSeconds: 5,
						},
					}},
				},
			},
		},
	}
}

// buildDashboardService builds the ClusterIP Service fronting the dashboard Deployment.
func buildDashboardService(c *closurejvmv1alpha1.ClosureJVMCampaign) *corev1.Service {
	return &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{Name: dashboardName(c), Namespace: c.Namespace, Labels: dashboardSelector(c)},
		Spec: corev1.ServiceSpec{
			Selector: dashboardSelector(c),
			Ports: []corev1.ServicePort{{
				Port: dashboardPort, TargetPort: intstr.FromInt(dashboardPort), Name: "http"}},
		},
	}
}
