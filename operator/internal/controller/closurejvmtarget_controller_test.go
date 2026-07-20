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
	"context"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

// These assert the actual P1 contract (docs/OPERATOR-DESIGN.md §8): observe only, never mutate a
// workload, and reject the DD-022 coverage shape at the API server. They replace the scaffold's
// "reconcile returns no error" placeholder, which asserted none of it. Run with envtest
// (KUBEBUILDER_ASSETS); the CEL case needs the CRD's x-kubernetes-validations installed, which the
// suite does from config/crd/bases.
var _ = Describe("ClosureJVMTarget Controller (P1: observe-only)", func() {
	const (
		targetName = "test-target"
		deployName = "test-deploy"
		namespace  = "default"
	)
	ctx := context.Background()
	targetKey := types.NamespacedName{Name: targetName, Namespace: namespace}
	deployKey := types.NamespacedName{Name: deployName, Namespace: namespace}

	reconciler := func() *ClosureJVMTargetReconciler {
		return &ClosureJVMTargetReconciler{Client: k8sClient, Scheme: k8sClient.Scheme()}
	}

	newTarget := func() *closurejvmv1alpha1.ClosureJVMTarget {
		return &closurejvmv1alpha1.ClosureJVMTarget{
			ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace},
			Spec: closurejvmv1alpha1.ClosureJVMTargetSpec{
				DeploymentRef: closurejvmv1alpha1.DeploymentReference{Name: deployName},
			},
		}
	}

	AfterEach(func() {
		// Best-effort cleanup so cases don't bleed into each other.
		t := &closurejvmv1alpha1.ClosureJVMTarget{}
		if err := k8sClient.Get(ctx, targetKey, t); err == nil {
			Expect(k8sClient.Delete(ctx, t)).To(Succeed())
		}
		d := &appsv1.Deployment{}
		if err := k8sClient.Get(ctx, deployKey, d); err == nil {
			Expect(k8sClient.Delete(ctx, d)).To(Succeed())
		}
	})

	It("reports DeploymentNotFound and requeues when the referenced Deployment is absent", func() {
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())

		res, err := reconciler().Reconcile(ctx, reconcile.Request{NamespacedName: targetKey})
		Expect(err).NotTo(HaveOccurred())
		Expect(res.RequeueAfter).To(BeNumerically(">", 0),
			"a missing Deployment should be re-observed soon, not on the hours-long default resync")

		got := &closurejvmv1alpha1.ClosureJVMTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(closurejvmv1alpha1.PhasePending))
		Expect(got.Status.InstrumentedReplicas).To(Equal(int32(0)))
		Expect(got.Status.ObservedGeneration).To(Equal(got.Generation))
		cond := meta.FindStatusCondition(got.Status.Conditions, "Ready")
		Expect(cond).NotTo(BeNil())
		Expect(cond.Status).To(Equal(metav1.ConditionFalse))
		Expect(cond.Reason).To(Equal("DeploymentNotFound"))
	})

	It("reports ObserveOnly and never mutates the Deployment when it exists", func() {
		dep := &appsv1.Deployment{
			ObjectMeta: metav1.ObjectMeta{Name: deployName, Namespace: namespace},
			Spec: appsv1.DeploymentSpec{
				Replicas: int32Ptr(3),
				Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": deployName}},
				Template: corev1.PodTemplateSpec{
					ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app": deployName}},
					Spec: corev1.PodSpec{
						Containers: []corev1.Container{{Name: "app", Image: "busybox"}},
					},
				},
			},
		}
		Expect(k8sClient.Create(ctx, dep)).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())

		_, err := reconciler().Reconcile(ctx, reconcile.Request{NamespacedName: targetKey})
		Expect(err).NotTo(HaveOccurred())

		got := &closurejvmv1alpha1.ClosureJVMTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		Expect(got.Status.InstrumentedReplicas).To(Equal(int32(0)), "P1 never injects")
		cond := meta.FindStatusCondition(got.Status.Conditions, "Ready")
		Expect(cond).NotTo(BeNil())
		Expect(cond.Status).To(Equal(metav1.ConditionFalse))
		Expect(cond.Reason).To(Equal("ObserveOnly"))

		// The zero-mutation guarantee, asserted against the real object: the Deployment's pod
		// template gained no initContainer, volume, or env, and its generation did not change.
		after := &appsv1.Deployment{}
		Expect(k8sClient.Get(ctx, deployKey, after)).To(Succeed())
		Expect(after.Spec.Template.Spec.InitContainers).To(BeEmpty())
		Expect(after.Spec.Template.Spec.Volumes).To(BeEmpty())
		Expect(after.Spec.Template.Spec.Containers).To(HaveLen(1))
		Expect(after.Spec.Template.Spec.Containers[0].Env).To(BeEmpty())
		Expect(after.Generation).To(Equal(dep.Generation), "Deployment generation must be untouched")
	})

	It("rejects coverage.enabled without includes at apply time (DD-022 CEL rule)", func() {
		t := newTarget()
		t.Spec.Agents.Coverage = closurejvmv1alpha1.CoverageSpec{Enabled: true}
		Expect(k8sClient.Create(ctx, t)).NotTo(Succeed(),
			"the CRD's XValidation rule must reject coverage enabled-without-includes")
	})
})

func int32Ptr(i int32) *int32 { return &i }
