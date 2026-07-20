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
	"fmt"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"
	appsv1 "k8s.io/api/apps/v1"
	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

// P5a (DD-025): the campaign reconciler gates on an Injected target, launches the driver Job wired to
// the target's coverage endpoint + app image, and aggregates the run's outcome from the driver pod's
// termination message. Run with envtest.
var _ = Describe("ClosureJVMCampaign Controller (P5a)", func() {
	const (
		appImage    = "myco/app:1.2.3"
		runnerImg   = "test/runner:v1"
		namespace   = "default"
		covEndpoint = "camp-cjvm-jacoco.default.svc.cluster.local:6300"
	)
	ctx := context.Background()
	// Unique names per spec — envtest has no GC controller, so a leftover Job would otherwise bleed
	// into the next spec's first reconcile.
	runID := 0
	var campaignName, targetName, deployName string
	var campaignKey, jobKey types.NamespacedName
	BeforeEach(func() {
		runID++
		campaignName = fmt.Sprintf("camp-%d", runID)
		targetName = fmt.Sprintf("camptgt-%d", runID)
		deployName = fmt.Sprintf("campdep-%d", runID)
		campaignKey = types.NamespacedName{Name: campaignName, Namespace: namespace}
		jobKey = types.NamespacedName{Name: campaignName + "-driver", Namespace: namespace}
	})

	rec := func() *ClosureJVMCampaignReconciler {
		return &ClosureJVMCampaignReconciler{Client: k8sClient, Scheme: k8sClient.Scheme(), RunnerImage: runnerImg}
	}
	reconcileOnce := func() (reconcile.Result, error) {
		return rec().Reconcile(ctx, reconcile.Request{NamespacedName: campaignKey})
	}

	// A target Deployment whose app container carries the image the classes come from.
	newTargetDeploy := func() *appsv1.Deployment {
		return &appsv1.Deployment{
			ObjectMeta: metav1.ObjectMeta{Name: deployName, Namespace: namespace},
			Spec: appsv1.DeploymentSpec{
				Replicas: int32Ptr(1),
				Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": deployName}},
				Template: corev1.PodTemplateSpec{
					ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app": deployName}},
					Spec:       corev1.PodSpec{Containers: []corev1.Container{{Name: "app", Image: appImage}}},
				},
			},
		}
	}
	// An Injected target with a published coverage endpoint.
	makeInjectedTarget := func() {
		t := &closurejvmv1alpha1.ClosureJVMTarget{
			ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace},
			Spec: closurejvmv1alpha1.ClosureJVMTargetSpec{
				DeploymentRef: closurejvmv1alpha1.DeploymentReference{Name: deployName},
				Container:     "app",
				Agents:        closurejvmv1alpha1.AgentsSpec{Coverage: closurejvmv1alpha1.CoverageSpec{Enabled: true, Includes: "com.x.*"}},
			},
		}
		Expect(k8sClient.Create(ctx, t)).To(Succeed())
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: targetName, Namespace: namespace}, t)).To(Succeed())
		t.Status.Phase = closurejvmv1alpha1.PhaseInjected
		t.Status.CoverageEndpoint = covEndpoint
		Expect(k8sClient.Status().Update(ctx, t)).To(Succeed())
	}
	newCampaign := func() *closurejvmv1alpha1.ClosureJVMCampaign {
		return &closurejvmv1alpha1.ClosureJVMCampaign{
			ObjectMeta: metav1.ObjectMeta{Name: campaignName, Namespace: namespace},
			Spec: closurejvmv1alpha1.ClosureJVMCampaignSpec{
				TargetRef: closurejvmv1alpha1.TargetReference{Name: targetName},
				BaseURL:   "http://camp-deploy.default.svc:8080",
				Driver:    closurejvmv1alpha1.CampaignDriverSpec{Iterations: 500},
			},
		}
	}

	AfterEach(func() {
		for _, o := range []client.Object{
			&closurejvmv1alpha1.ClosureJVMCampaign{ObjectMeta: metav1.ObjectMeta{Name: campaignName, Namespace: namespace}},
			&batchv1.Job{ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-driver", Namespace: namespace}},
			&closurejvmv1alpha1.ClosureJVMTarget{ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace}},
			&appsv1.Deployment{ObjectMeta: metav1.ObjectMeta{Name: deployName, Namespace: namespace}},
		} {
			_ = k8sClient.Delete(ctx, o)
		}
		// The simulated driver pod (from the completion test) isn't owned by anything we delete above.
		_ = k8sClient.DeleteAllOf(ctx, &corev1.Pod{}, client.InNamespace(namespace),
			client.MatchingLabels{"closurejvm.dev/campaign": campaignName})
	})

	It("stays Pending while the target is not Injected", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		// target exists but never marked Injected
		t := &closurejvmv1alpha1.ClosureJVMTarget{ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace},
			Spec: closurejvmv1alpha1.ClosureJVMTargetSpec{DeploymentRef: closurejvmv1alpha1.DeploymentReference{Name: deployName}}}
		Expect(k8sClient.Create(ctx, t)).To(Succeed())
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		got := &closurejvmv1alpha1.ClosureJVMCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(closurejvmv1alpha1.CampaignPending))
		Expect(k8sClient.Get(ctx, jobKey, &batchv1.Job{})).NotTo(Succeed()) // no Job yet
	})

	It("launches the driver Job wired to the target and goes Running", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		job := &batchv1.Job{}
		Expect(k8sClient.Get(ctx, jobKey, job)).To(Succeed())
		tmpl := job.Spec.Template.Spec
		// initContainer extracts classes from the TARGET's app image.
		Expect(tmpl.InitContainers).To(HaveLen(1))
		Expect(tmpl.InitContainers[0].Image).To(Equal(appImage))
		// driver runs the runner image, wired via JAVA_TOOL_OPTIONS.
		Expect(tmpl.Containers[0].Image).To(Equal(runnerImg))
		jto := envValue(tmpl.Containers[0].Env, "JAVA_TOOL_OPTIONS")
		Expect(jto).To(ContainSubstring("-Dexamples.http.baseUrl=http://camp-deploy.default.svc:8080"))
		Expect(jto).To(ContainSubstring("-Dclosurejvm.coverage.jacoco=" + covEndpoint))
		Expect(jto).To(ContainSubstring("-Dclosurejvm.coverage.classes=" + campaignClassesDir))
		Expect(jto).To(ContainSubstring("-Dclosurejvm.summary.out=/dev/termination-log"))
		Expect(tmpl.Containers[0].Args).To(Equal([]string{"500"}))
		Expect(job.OwnerReferences).To(ContainElement(HaveField("Name", campaignName)))

		got := &closurejvmv1alpha1.ClosureJVMCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(closurejvmv1alpha1.CampaignRunning))
		Expect(got.Status.DriverJob).To(Equal(campaignName + "-driver"))
	})

	It("completes and reads coverage/findings from the driver pod's termination message", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce() // creates the Job, goes Running
		Expect(err).NotTo(HaveOccurred())

		// Simulate the Job succeeding and the driver pod terminating with a summary.
		job := &batchv1.Job{}
		Expect(k8sClient.Get(ctx, jobKey, job)).To(Succeed())
		job.Status.Succeeded = 1
		Expect(k8sClient.Status().Update(ctx, job)).To(Succeed())
		pod := &corev1.Pod{
			ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-driver-abc", Namespace: namespace,
				Labels: map[string]string{"closurejvm.dev/campaign": campaignName}},
			Spec: corev1.PodSpec{Containers: []corev1.Container{{Name: "driver", Image: runnerImg}}},
		}
		Expect(k8sClient.Create(ctx, pod)).To(Succeed())
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: pod.Name, Namespace: namespace}, pod)).To(Succeed())
		pod.Status.ContainerStatuses = []corev1.ContainerStatus{{
			Name: "driver",
			State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{
				Message: `{"exploration":{"corpus":19,"coverage":{"pct":23.1}}}`}},
		}}
		Expect(k8sClient.Status().Update(ctx, pod)).To(Succeed())

		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		got := &closurejvmv1alpha1.ClosureJVMCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(closurejvmv1alpha1.CampaignCompleted))
		Expect(got.Status.CoveragePct).To(Equal("23.1"))
		Expect(got.Status.Findings).To(Equal(int32(19)))
		Expect(got.Status.CompletionTime).NotTo(BeNil())
	})

	It("fails with TargetGone if the target is deleted mid-run", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce() // Running
		Expect(err).NotTo(HaveOccurred())

		Expect(k8sClient.Delete(ctx, &closurejvmv1alpha1.ClosureJVMTarget{
			ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace}})).To(Succeed())
		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		got := &closurejvmv1alpha1.ClosureJVMCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(closurejvmv1alpha1.CampaignFailed))
		Expect(meta.FindStatusCondition(got.Status.Conditions, "Ready").Reason).To(Equal("TargetGone"))
	})

	It("rejects duration AND iterations set together (CEL)", func() {
		c := newCampaign()
		c.Spec.Driver.Duration = "10m" // both duration and iterations=500
		Expect(k8sClient.Create(ctx, c)).NotTo(Succeed())
	})
})
