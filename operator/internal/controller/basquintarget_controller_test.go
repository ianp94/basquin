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
	"strings"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

// P2 (docs/OPERATOR-DESIGN.md §4, DD-024): these assert the actual injection contract — the
// Deployment is patched (initContainer + volume + appended jvmOptsVar + coverage port), the patch is
// idempotent, and deleting the target reverts the Deployment EXACTLY. Run with envtest
// (KUBEBUILDER_ASSETS). envtest has no controllers, so Deployment.Status is authored by hand where a
// test needs a "rollout complete" state.
var _ = Describe("BasquinTarget Controller (P2: injection)", func() {
	const (
		targetName = "test-target"
		deployName = "test-deploy"
		container  = "app"
		namespace  = "default"
		agentsImg  = "test/agents:v1"
		origOpts   = "-Xmx512m"
	)
	ctx := context.Background()
	targetKey := types.NamespacedName{Name: targetName, Namespace: namespace}
	deployKey := types.NamespacedName{Name: deployName, Namespace: namespace}

	reconciler := func() *BasquinTargetReconciler {
		return &BasquinTargetReconciler{Client: k8sClient, Scheme: k8sClient.Scheme(), AgentsImage: agentsImg}
	}
	// Injection happens on the 2nd pass (the 1st adds the finalizer and requeues); reconcile a few
	// times to reach steady state.
	reconcileN := func(n int) {
		for i := 0; i < n; i++ {
			_, err := reconciler().Reconcile(ctx, reconcile.Request{NamespacedName: targetKey})
			Expect(err).NotTo(HaveOccurred())
		}
	}
	getDeploy := func() *appsv1.Deployment {
		d := &appsv1.Deployment{}
		Expect(k8sClient.Get(ctx, deployKey, d)).To(Succeed())
		return d
	}
	appContainer := func(d *appsv1.Deployment) corev1.Container {
		for _, c := range d.Spec.Template.Spec.Containers {
			if c.Name == container {
				return c
			}
		}
		Fail("app container not found")
		return corev1.Container{}
	}

	newDeploy := func(containers ...corev1.Container) *appsv1.Deployment {
		return &appsv1.Deployment{
			ObjectMeta: metav1.ObjectMeta{Name: deployName, Namespace: namespace},
			Spec: appsv1.DeploymentSpec{
				Replicas: int32Ptr(2),
				Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": deployName}},
				Template: corev1.PodTemplateSpec{
					ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app": deployName}},
					Spec:       corev1.PodSpec{Containers: containers},
				},
			},
		}
	}
	newTarget := func() *basquinv1alpha1.BasquinTarget {
		return &basquinv1alpha1.BasquinTarget{
			ObjectMeta: metav1.ObjectMeta{Name: targetName, Namespace: namespace},
			Spec: basquinv1alpha1.BasquinTargetSpec{
				DeploymentRef: basquinv1alpha1.DeploymentReference{Name: deployName},
				Container:     container,
				JVMOptsVar:    "CATALINA_OPTS",
				Agents: basquinv1alpha1.AgentsSpec{
					ThreadTracker: boolPtr(true),
					Coverage:      basquinv1alpha1.CoverageSpec{Enabled: true, Port: 6300, Includes: "com.example.*"},
				},
				Invariants: basquinv1alpha1.InvariantsSpec{Mode: "soft", LatencyMaxMs: 25},
			},
		}
	}

	AfterEach(func() {
		// Force-clear finalizers so nothing wedges deletion, then remove both objects.
		t := &basquinv1alpha1.BasquinTarget{}
		if err := k8sClient.Get(ctx, targetKey, t); err == nil {
			t.Finalizers = nil
			_ = k8sClient.Update(ctx, t)
			_ = k8sClient.Delete(ctx, t)
		}
		d := &appsv1.Deployment{}
		if err := k8sClient.Get(ctx, deployKey, d); err == nil {
			_ = k8sClient.Delete(ctx, d)
		}
		// envtest runs no GC controller, so the owner-referenced coverage Service won't be collected.
		_ = k8sClient.Delete(ctx, &corev1.Service{ObjectMeta: metav1.ObjectMeta{
			Name: deployName + coverageServiceSuffix, Namespace: namespace}})
	})

	It("injects the agents and APPENDS to (never replaces) jvmOptsVar", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{
			Name: container, Image: "busybox",
			Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", Value: origOpts}},
		}))).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())
		reconcileN(2)

		d := getDeploy()
		// initContainer copies from the configured agents image.
		Expect(d.Spec.Template.Spec.InitContainers).To(HaveLen(1))
		Expect(d.Spec.Template.Spec.InitContainers[0].Name).To(Equal(agentsInitName))
		Expect(d.Spec.Template.Spec.InitContainers[0].Image).To(Equal(agentsImg))
		// IfNotPresent so a :latest agents image doesn't ImagePullBackOff on a kind/air-gapped node.
		Expect(d.Spec.Template.Spec.InitContainers[0].ImagePullPolicy).To(Equal(corev1.PullIfNotPresent))
		// shared volume + mount.
		Expect(d.Spec.Template.Spec.Volumes).To(HaveLen(1))
		Expect(d.Spec.Template.Spec.Volumes[0].Name).To(Equal(agentsVolumeName))
		c := appContainer(d)
		Expect(c.VolumeMounts).To(ContainElement(HaveField("MountPath", agentsMountPath)))
		// jvmOptsVar appended, original preserved at the front.
		opts := envValue(c.Env, "CATALINA_OPTS")
		Expect(opts).To(HavePrefix(origOpts + " "))
		Expect(opts).To(ContainSubstring("-agentpath:" + agentsMountPath + "/libbasquinjvmti.so"))
		Expect(opts).To(ContainSubstring("-javaagent:" + agentsMountPath + "/basquin-agent.jar"))
		Expect(opts).To(ContainSubstring("jacocoagent.jar=output=tcpserver"))
		Expect(opts).To(ContainSubstring("includes=com.example.*"))
		Expect(opts).To(ContainSubstring("-Dbasquin.invariant.mode=soft"))
		// coverage port exposed.
		Expect(c.Ports).To(ContainElement(And(
			HaveField("Name", coveragePortName), HaveField("ContainerPort", int32(6300)))))
		// annotations record the hash + original for exact revert.
		Expect(d.Annotations).To(HaveKey(annInjectedHash))
		Expect(d.Annotations[annOriginalOpts]).To(Equal(origOpts))
		Expect(d.Annotations[annOptsVar]).To(Equal("CATALINA_OPTS"))
	})

	It("is idempotent — repeated reconciles never double-append or duplicate the initContainer", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{
			Name: container, Image: "busybox",
			Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", Value: origOpts}},
		}))).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())
		reconcileN(2)
		optsAfterFirst := envValue(appContainer(getDeploy()).Env, "CATALINA_OPTS")

		reconcileN(4) // more passes must change nothing
		d := getDeploy()
		Expect(envValue(appContainer(d).Env, "CATALINA_OPTS")).To(Equal(optsAfterFirst))
		Expect(d.Spec.Template.Spec.InitContainers).To(HaveLen(1))
		Expect(d.Spec.Template.Spec.Volumes).To(HaveLen(1))
	})

	It("reverts the Deployment EXACTLY when the target is deleted", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{
			Name: container, Image: "busybox",
			Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", Value: origOpts}},
		}))).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())
		reconcileN(2)
		Expect(getDeploy().Annotations).To(HaveKey(annInjectedHash)) // injected

		// Delete → the finalizer keeps the object until we reconcile the revert.
		t := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, t)).To(Succeed())
		Expect(k8sClient.Delete(ctx, t)).To(Succeed())
		reconcileN(1)

		// Target is gone (finalizer released)...
		Expect(k8sClient.Get(ctx, targetKey, &basquinv1alpha1.BasquinTarget{})).NotTo(Succeed())
		// ...and the Deployment is byte-for-byte back to its original shape.
		d := getDeploy()
		Expect(d.Spec.Template.Spec.InitContainers).To(BeEmpty())
		Expect(d.Spec.Template.Spec.Volumes).To(BeEmpty())
		c := appContainer(d)
		Expect(c.VolumeMounts).To(BeEmpty())
		Expect(c.Ports).To(BeEmpty())
		Expect(envValue(c.Env, "CATALINA_OPTS")).To(Equal(origOpts))
		Expect(d.Annotations).NotTo(HaveKey(annInjectedHash))
		Expect(d.Annotations).NotTo(HaveKey(annOriginalOpts))
	})

	It("reports Injected once all replicas are on the instrumented template", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{Name: container, Image: "busybox"}))).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())
		reconcileN(2)

		// envtest has no Deployment controller, so simulate the rollout completing. (status.replicas
		// must be set first — updated/ready can't exceed it.)
		d := getDeploy()
		d.Status.Replicas = 2
		d.Status.UpdatedReplicas = 2
		d.Status.ReadyReplicas = 2
		Expect(k8sClient.Status().Update(ctx, d)).To(Succeed())
		reconcileN(1)

		got := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.PhaseInjected))
		Expect(got.Status.InstrumentedReplicas).To(Equal(int32(2)))
		cond := meta.FindStatusCondition(got.Status.Conditions, "Ready")
		Expect(cond).NotTo(BeNil())
		Expect(cond.Status).To(Equal(metav1.ConditionTrue))
		Expect(cond.Reason).To(Equal("Injected"))
	})

	It("surfaces an error (and injects nothing) when the container is ambiguous", func() {
		Expect(k8sClient.Create(ctx, newDeploy(
			corev1.Container{Name: "a", Image: "busybox"},
			corev1.Container{Name: "b", Image: "busybox"},
		))).To(Succeed())
		t := newTarget()
		t.Spec.Container = "" // ambiguous: two containers, none named
		Expect(k8sClient.Create(ctx, t)).To(Succeed())
		rvBefore := getDeploy().ResourceVersion
		reconcileN(2)

		got := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.PhaseError))
		cond := meta.FindStatusCondition(got.Status.Conditions, "Ready")
		Expect(cond).NotTo(BeNil())
		Expect(cond.Reason).To(Equal("InjectionRejected"))
		Expect(cond.Message).To(ContainSubstring("container"))
		// nothing was injected — and, since no prior injection existed, the Deployment must not be
		// written at all (a no-op write would emit a MODIFIED event that self-re-enqueues → storm).
		d := getDeploy()
		Expect(d.Spec.Template.Spec.InitContainers).To(BeEmpty())
		Expect(d.ResourceVersion).To(Equal(rvBefore), "error path must not write the Deployment when nothing was reverted")
	})

	It("restores the injected container's jvmOptsVar without touching a sidecar that sets the same var", func() {
		// A sidecar independently sets CATALINA_OPTS; revert must not clobber it (review HIGH #1).
		Expect(k8sClient.Create(ctx, newDeploy(
			corev1.Container{Name: container, Image: "busybox",
				Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", Value: origOpts}}},
			corev1.Container{Name: "sidecar", Image: "busybox",
				Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", Value: "-Dsidecar=1"}}},
		))).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())
		reconcileN(2)

		// Only the app container was instrumented; the sidecar's var is untouched.
		var sidecarOpts string
		for _, c := range getDeploy().Spec.Template.Spec.Containers {
			if c.Name == "sidecar" {
				sidecarOpts = envValue(c.Env, "CATALINA_OPTS")
			}
		}
		Expect(sidecarOpts).To(Equal("-Dsidecar=1"))

		// Revert: the sidecar's var must survive, the app's must return to exactly origOpts.
		t := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, t)).To(Succeed())
		Expect(k8sClient.Delete(ctx, t)).To(Succeed())
		reconcileN(1)
		d := getDeploy()
		for _, c := range d.Spec.Template.Spec.Containers {
			switch c.Name {
			case "sidecar":
				Expect(envValue(c.Env, "CATALINA_OPTS")).To(Equal("-Dsidecar=1"), "sidecar var clobbered by revert")
			case container:
				Expect(envValue(c.Env, "CATALINA_OPTS")).To(Equal(origOpts))
			}
		}
	})

	It("refuses to instrument a valueFrom-sourced jvmOptsVar rather than silently losing it", func() {
		// review HIGH #2: appending would replace the reference with a literal, and revert would then
		// delete it — permanent loss of a Secret/ConfigMap-sourced var. Fail loud instead.
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{
			Name: container, Image: "busybox",
			Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", ValueFrom: &corev1.EnvVarSource{
				ConfigMapKeyRef: &corev1.ConfigMapKeySelector{
					LocalObjectReference: corev1.LocalObjectReference{Name: "cfg"}, Key: "opts"}}}},
		}))).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())
		reconcileN(2)

		got := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		Expect(got.Status.Phase).To(Equal(basquinv1alpha1.PhaseError))
		cond := meta.FindStatusCondition(got.Status.Conditions, "Ready")
		Expect(cond.Reason).To(Equal("InjectionRejected"))
		Expect(cond.Message).To(ContainSubstring("valueFrom"))
		// nothing injected, and the original valueFrom reference is intact.
		d := getDeploy()
		Expect(d.Spec.Template.Spec.InitContainers).To(BeEmpty())
		ev := findEnv(appContainer(d).Env, "CATALINA_OPTS")
		Expect(ev).NotTo(BeNil())
		Expect(ev.ValueFrom).NotTo(BeNil(), "valueFrom reference must be untouched")
	})

	It("re-heals out-of-band content drift (injected fields stripped, annotation left)", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{
			Name: container, Image: "busybox",
			Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", Value: origOpts}},
		}))).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())
		reconcileN(2)
		Expect(getDeploy().Spec.Template.Spec.InitContainers).To(HaveLen(1))

		// Simulate a `kubectl edit` that strips the initContainer but leaves the annotation.
		d := getDeploy()
		d.Spec.Template.Spec.InitContainers = nil
		Expect(k8sClient.Update(ctx, d)).To(Succeed())

		reconcileN(1) // must notice the content drift and re-inject
		Expect(getDeploy().Spec.Template.Spec.InitContainers).To(HaveLen(1))
	})

	It("re-heals when the agent flags are stripped from the env but the initContainer remains", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{
			Name: container, Image: "busybox",
			Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", Value: origOpts}},
		}))).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())
		reconcileN(2)
		injected := getDeploy().Spec.Template.Spec.Containers[0]
		Expect(envValue(injected.Env, "CATALINA_OPTS")).To(ContainSubstring("basquin-agent.jar"))

		// The worst drift: someone resets the env var to its original value (agents silently stop
		// loading) while the annotation AND initContainer remain — previously read as steady Injected.
		d := getDeploy()
		setEnv(&d.Spec.Template.Spec.Containers[0], "CATALINA_OPTS", origOpts)
		Expect(k8sClient.Update(ctx, d)).To(Succeed())

		reconcileN(1) // must notice the env drift and re-append the flags
		healed := getDeploy().Spec.Template.Spec.Containers[0]
		Expect(envValue(healed.Env, "CATALINA_OPTS")).To(ContainSubstring("basquin-agent.jar"))
		Expect(envValue(healed.Env, "CATALINA_OPTS")).To(HavePrefix(origOpts + " "))
	})

	It("keeps a sole-container injection intact when an unrelated sidecar is added (review #26)", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{
			Name: container, Image: "busybox",
			Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", Value: origOpts}},
		}))).To(Succeed())
		t := newTarget()
		t.Spec.Container = "" // rely on the sole-container default
		Expect(k8sClient.Create(ctx, t)).To(Succeed())
		reconcileN(2)
		Expect(envValue(getDeploy().Spec.Template.Spec.Containers[0].Env, "CATALINA_OPTS")).To(ContainSubstring("basquin-agent.jar"))

		// A sidecar injected out-of-band makes the pod multi-container. resolveContainer's sole-container
		// default would now be ambiguous — but resolving the instrumented container by its stashed name
		// must keep the healthy injection intact (not tear it down into PhaseError).
		d := getDeploy()
		d.Spec.Template.Spec.Containers = append(d.Spec.Template.Spec.Containers,
			corev1.Container{Name: "istio-proxy", Image: "proxy"})
		Expect(k8sClient.Update(ctx, d)).To(Succeed())

		reconcileN(1)
		// The injection must NOT be torn down: a broken check would revert (strip the flags), then
		// fail to re-apply on the now-ambiguous sole-container default, ending in PhaseError with the
		// flags gone. (Phase itself reads Injecting here only because the manual sidecar edit bumps the
		// Deployment generation and envtest has no rollout controller to complete it — an artifact.)
		got := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		Expect(got.Status.Phase).NotTo(Equal(basquinv1alpha1.PhaseError))
		Expect(envValue(getDeploy().Spec.Template.Spec.Containers[0].Env, "CATALINA_OPTS")).To(ContainSubstring("basquin-agent.jar"))
	})

	It("re-heals when the coverage containerPort is stripped", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{
			Name: container, Image: "busybox",
			Env: []corev1.EnvVar{{Name: "CATALINA_OPTS", Value: origOpts}},
		}))).To(Succeed())
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed()) // coverage enabled
		reconcileN(2)
		Expect(getDeploy().Spec.Template.Spec.Containers[0].Ports).To(ContainElement(HaveField("Name", coveragePortName)))

		// Strip only the coverage port, leaving everything else injected.
		d := getDeploy()
		var kept []corev1.ContainerPort
		for _, p := range d.Spec.Template.Spec.Containers[0].Ports {
			if p.Name != coveragePortName {
				kept = append(kept, p)
			}
		}
		d.Spec.Template.Spec.Containers[0].Ports = kept
		Expect(k8sClient.Update(ctx, d)).To(Succeed())

		reconcileN(1)
		Expect(getDeploy().Spec.Template.Spec.Containers[0].Ports).To(ContainElement(HaveField("Name", coveragePortName)))
	})

	It("reports DeploymentNotFound and requeues when the Deployment is absent", func() {
		Expect(k8sClient.Create(ctx, newTarget())).To(Succeed())
		reconcileN(2)
		got := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		cond := meta.FindStatusCondition(got.Status.Conditions, "Ready")
		Expect(cond).NotTo(BeNil())
		Expect(cond.Reason).To(Equal("DeploymentNotFound"))
	})

	It("rejects coverage.enabled without includes at apply time (DD-022 CEL rule)", func() {
		t := newTarget()
		t.Spec.Agents.Coverage = basquinv1alpha1.CoverageSpec{Enabled: true}
		err := k8sClient.Create(ctx, t)
		Expect(err).To(HaveOccurred())
		Expect(strings.ToLower(err.Error())).To(ContainSubstring("includes"))
	})

	// --- P3: coverage Service ---------------------------------------------------------------------

	It("creates a headless coverage Service and publishes status.coverageEndpoint when enabled", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{Name: container, Image: "busybox"}))).To(Succeed())
		t := newTarget()
		t.Spec.CoverageService = true
		Expect(k8sClient.Create(ctx, t)).To(Succeed())
		reconcileN(2)

		svc := &corev1.Service{}
		svcKey := types.NamespacedName{Name: deployName + coverageServiceSuffix, Namespace: namespace}
		Expect(k8sClient.Get(ctx, svcKey, svc)).To(Succeed())
		Expect(svc.Spec.ClusterIP).To(Equal(corev1.ClusterIPNone), "must be headless so DNS returns all pod IPs (DD-023)")
		Expect(svc.Spec.Selector).To(Equal(map[string]string{"app": deployName}), "selects the target's pods")
		Expect(svc.Spec.Ports).To(HaveLen(1))
		Expect(svc.Spec.Ports[0].Port).To(Equal(int32(6300)))
		// owner-referenced to the target so it's GC'd when the target is deleted.
		Expect(svc.OwnerReferences).To(ContainElement(And(
			HaveField("Kind", "BasquinTarget"), HaveField("Name", targetName))))

		got := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		Expect(got.Status.CoverageEndpoint).To(Equal(
			deployName + coverageServiceSuffix + "." + namespace + ".svc.cluster.local:6300"))
	})

	It("removes the coverage Service and clears the endpoint when coverageService is toggled off", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{Name: container, Image: "busybox"}))).To(Succeed())
		t := newTarget()
		t.Spec.CoverageService = true
		Expect(k8sClient.Create(ctx, t)).To(Succeed())
		reconcileN(2)
		svcKey := types.NamespacedName{Name: deployName + coverageServiceSuffix, Namespace: namespace}
		Expect(k8sClient.Get(ctx, svcKey, &corev1.Service{})).To(Succeed()) // created

		Expect(k8sClient.Get(ctx, targetKey, t)).To(Succeed())
		t.Spec.CoverageService = false
		Expect(k8sClient.Update(ctx, t)).To(Succeed())
		reconcileN(1)

		Expect(k8sClient.Get(ctx, svcKey, &corev1.Service{})).NotTo(Succeed()) // deleted
		got := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		Expect(got.Status.CoverageEndpoint).To(BeEmpty())
	})

	It("tears down the coverage Service + clears the endpoint when the Deployment disappears", func() {
		Expect(k8sClient.Create(ctx, newDeploy(corev1.Container{Name: container, Image: "busybox"}))).To(Succeed())
		t := newTarget()
		t.Spec.CoverageService = true
		Expect(k8sClient.Create(ctx, t)).To(Succeed())
		reconcileN(2)
		svcKey := types.NamespacedName{Name: deployName + coverageServiceSuffix, Namespace: namespace}
		Expect(k8sClient.Get(ctx, svcKey, &corev1.Service{})).To(Succeed()) // created

		// The referenced Deployment vanishes after the Service was provisioned.
		Expect(k8sClient.Delete(ctx, &appsv1.Deployment{
			ObjectMeta: metav1.ObjectMeta{Name: deployName, Namespace: namespace}})).To(Succeed())
		reconcileN(1)

		// Service torn down and endpoint cleared — not left publishing a dead one.
		Expect(k8sClient.Get(ctx, svcKey, &corev1.Service{})).NotTo(Succeed())
		got := &basquinv1alpha1.BasquinTarget{}
		Expect(k8sClient.Get(ctx, targetKey, got)).To(Succeed())
		Expect(got.Status.CoverageEndpoint).To(BeEmpty())
		Expect(meta.FindStatusCondition(got.Status.Conditions, "Ready").Reason).To(Equal("DeploymentNotFound"))
	})
})

func int32Ptr(i int32) *int32 { return &i }
