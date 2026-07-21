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
	"fmt"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

// P3 (docs/OPERATOR-DESIGN.md §4, DD-023): when spec.coverageService is set, the operator creates a
// HEADLESS Service (clusterIP: None) selecting the target's pods on the coverage port. Its DNS name
// resolves to every pod IP, which is exactly what the DD-023 driver flag
// (-Dbasquin.coverage.jacoco=<host>:<port>) + InetAddress.getAllByName consume to union-merge
// coverage across replicas. status.coverageEndpoint publishes the host:port to point the flag at.

const coverageServiceSuffix = "-basquin-jacoco"

func coverageServiceName(target *basquinv1alpha1.BasquinTarget) string {
	return target.Spec.DeploymentRef.Name + coverageServiceSuffix
}

func coveragePort(spec *basquinv1alpha1.BasquinTargetSpec) int32 {
	if spec.Agents.Coverage.Port != 0 {
		return spec.Agents.Coverage.Port
	}
	return 6300
}

// coverageEndpoint is the host:port a driver points -Dbasquin.coverage.jacoco at. The headless
// Service's cluster DNS resolves to all backing pod IPs, so getAllByName reaches every replica.
func coverageEndpoint(target *basquinv1alpha1.BasquinTarget) string {
	return fmt.Sprintf("%s.%s.svc.cluster.local:%d",
		coverageServiceName(target), target.Namespace, coveragePort(&target.Spec))
}

// desiredCoverageService builds the headless Service for a target, selecting the same pods as the
// referenced Deployment. It is owner-referenced to the target by the caller so it is garbage-collected
// when the target is deleted.
func desiredCoverageService(target *basquinv1alpha1.BasquinTarget, deploy *appsv1.Deployment) *corev1.Service {
	port := coveragePort(&target.Spec)
	var selector map[string]string
	if deploy.Spec.Selector != nil {
		selector = deploy.Spec.Selector.MatchLabels
	}
	return &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      coverageServiceName(target),
			Namespace: target.Namespace,
			Labels:    map[string]string{"app.kubernetes.io/managed-by": "basquin-operator"},
		},
		Spec: corev1.ServiceSpec{
			ClusterIP: corev1.ClusterIPNone, // headless: DNS returns all pod IPs (DD-023)
			Selector:  selector,
			Ports: []corev1.ServicePort{{
				Name:       coveragePortName,
				Port:       port,
				TargetPort: intstr.FromInt32(port),
			}},
		},
	}
}
