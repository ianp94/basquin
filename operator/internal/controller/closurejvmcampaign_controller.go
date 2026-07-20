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
	"encoding/json"
	"fmt"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/log"

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

// ClosureJVMCampaignReconciler drives a bounded coverage-guided test run against an instrumented
// ClosureJVMTarget (docs/CAMPAIGN-DESIGN.md, DD-025). P5a scope: gate on the target, launch the
// driver Job (with the coverage-classes initContainer), and aggregate its result from the pod's
// termination message. The per-campaign dashboard is P5b.
type ClosureJVMCampaignReconciler struct {
	client.Client
	Scheme *runtime.Scheme
	// RunnerImage is the coverage-guided runner image the driver Job runs. Empty uses the default.
	RunnerImage string
}

//+kubebuilder:rbac:groups=closurejvm.dev,resources=closurejvmcampaigns,verbs=get;list;watch;update;patch
//+kubebuilder:rbac:groups=closurejvm.dev,resources=closurejvmcampaigns/status,verbs=get;update;patch
//+kubebuilder:rbac:groups=closurejvm.dev,resources=closurejvmcampaigns/finalizers,verbs=update
//+kubebuilder:rbac:groups=batch,resources=jobs,verbs=get;list;watch;create;delete
//+kubebuilder:rbac:groups=core,resources=pods,verbs=get;list;watch
//+kubebuilder:rbac:groups=core,resources=configmaps,verbs=get;list;watch

func (r *ClosureJVMCampaignReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	l := log.FromContext(ctx)

	var campaign closurejvmv1alpha1.ClosureJVMCampaign
	if err := r.Get(ctx, req.NamespacedName, &campaign); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}
	campaign.Status.ObservedGeneration = campaign.Generation

	// A terminal campaign stays put — a bounded run doesn't restart itself on resync.
	if campaign.Status.Phase == closurejvmv1alpha1.CampaignCompleted ||
		campaign.Status.Phase == closurejvmv1alpha1.CampaignFailed {
		return ctrl.Result{}, nil
	}

	runnerImage := r.RunnerImage
	if runnerImage == "" {
		runnerImage = defaultRunnerImage
	}

	// --- gate on the target being instrumented -------------------------------------------------
	var target closurejvmv1alpha1.ClosureJVMTarget
	tkey := types.NamespacedName{Namespace: campaign.Namespace, Name: campaign.Spec.TargetRef.Name}
	if err := r.Get(ctx, tkey, &target); err != nil {
		if apierrors.IsNotFound(err) {
			// If a run was already going and the target vanished, that's a distinct terminal failure —
			// not silent connection-refused "findings".
			if campaign.Status.Phase == closurejvmv1alpha1.CampaignRunning {
				return r.fail(ctx, &campaign, "TargetGone", "the referenced ClosureJVMTarget was deleted mid-run")
			}
			return r.pending(ctx, &campaign, "TargetNotFound",
				fmt.Sprintf("ClosureJVMTarget %q not found", campaign.Spec.TargetRef.Name))
		}
		return ctrl.Result{}, err
	}
	if target.Status.Phase != closurejvmv1alpha1.PhaseInjected {
		return r.pending(ctx, &campaign, "TargetNotInjected",
			fmt.Sprintf("target %q is %q, waiting for Injected", target.Name, target.Status.Phase))
	}
	if target.Status.CoverageEndpoint == "" {
		return r.pending(ctx, &campaign, "NoCoverageEndpoint",
			"target has no status.coverageEndpoint (set spec.coverageService on the target)")
	}

	// The .class files come from the target's app-container image; find it via the target's Deployment.
	appImage, err := r.targetAppImage(ctx, &target)
	if err != nil {
		return r.pending(ctx, &campaign, "TargetDeploymentNotReady", err.Error())
	}

	// --- ensure the driver Job -----------------------------------------------------------------
	var job batchv1.Job
	jkey := types.NamespacedName{Namespace: campaign.Namespace, Name: driverJobName(&campaign)}
	switch err := r.Get(ctx, jkey, &job); {
	case apierrors.IsNotFound(err):
		desired := buildDriverJob(&campaign, appImage, target.Status.CoverageEndpoint, runnerImage)
		if serr := controllerutil.SetControllerReference(&campaign, desired, r.Scheme); serr != nil {
			return ctrl.Result{}, serr
		}
		if cerr := r.Create(ctx, desired); cerr != nil {
			return ctrl.Result{}, cerr
		}
		l.Info("launched driver Job", "job", desired.Name)
		now := metav1.Now()
		campaign.Status.StartTime = &now
		campaign.Status.DriverJob = desired.Name
		campaign.Status.Phase = closurejvmv1alpha1.CampaignRunning
		meta.SetStatusCondition(&campaign.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionFalse, Reason: "Running", Message: "driver Job launched"})
		if uerr := r.Status().Update(ctx, &campaign); uerr != nil {
			return ctrl.Result{}, uerr
		}
		return ctrl.Result{RequeueAfter: 5 * time.Second}, nil
	case err != nil:
		return ctrl.Result{}, err
	}

	// --- aggregate the Job's outcome -----------------------------------------------------------
	if job.Status.Succeeded > 0 {
		campaign.Status.Phase = closurejvmv1alpha1.CampaignCompleted
		if s := r.readDriverSummary(ctx, &campaign); s != nil {
			campaign.Status.CoveragePct = fmt.Sprintf("%.1f", s.Exploration.Coverage.Pct)
			campaign.Status.Findings = s.Exploration.Corpus
		}
		now := metav1.Now()
		campaign.Status.CompletionTime = &now
		meta.SetStatusCondition(&campaign.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionTrue, Reason: "Completed",
			Message: fmt.Sprintf("run complete: coverage %s%%, %d findings",
				campaign.Status.CoveragePct, campaign.Status.Findings)})
		return ctrl.Result{}, r.Status().Update(ctx, &campaign)
	}
	if job.Status.Failed > 0 && jobBackoffExhausted(&job) {
		return r.fail(ctx, &campaign, "DriverFailed", "the driver Job failed")
	}

	// Still running.
	campaign.Status.Phase = closurejvmv1alpha1.CampaignRunning
	if err := r.Status().Update(ctx, &campaign); err != nil {
		return ctrl.Result{}, err
	}
	return ctrl.Result{RequeueAfter: 10 * time.Second}, nil
}

// driverSummary is the subset of StatusReporter.snapshotJson the campaign surfaces.
type driverSummary struct {
	Exploration struct {
		Corpus   int32 `json:"corpus"`
		Coverage struct {
			Pct float64 `json:"pct"`
		} `json:"coverage"`
	} `json:"exploration"`
}

// readDriverSummary finds the driver Job's pod and parses the summary the runner wrote to its
// termination message (DD-025 §7a).
func (r *ClosureJVMCampaignReconciler) readDriverSummary(ctx context.Context, c *closurejvmv1alpha1.ClosureJVMCampaign) *driverSummary {
	var pods corev1.PodList
	if err := r.List(ctx, &pods, client.InNamespace(c.Namespace),
		client.MatchingLabels{"closurejvm.dev/campaign": c.Name}); err != nil {
		return nil
	}
	for i := range pods.Items {
		for _, cs := range pods.Items[i].Status.ContainerStatuses {
			if cs.Name == "driver" && cs.State.Terminated != nil && cs.State.Terminated.Message != "" {
				var s driverSummary
				if err := json.Unmarshal([]byte(cs.State.Terminated.Message), &s); err == nil {
					return &s
				}
			}
		}
	}
	return nil
}

// targetAppImage resolves the app container's image from the target's Deployment (the .class source).
func (r *ClosureJVMCampaignReconciler) targetAppImage(ctx context.Context, target *closurejvmv1alpha1.ClosureJVMTarget) (string, error) {
	var deploy appsv1.Deployment
	if err := r.Get(ctx, types.NamespacedName{Namespace: target.Namespace, Name: target.Spec.DeploymentRef.Name}, &deploy); err != nil {
		return "", fmt.Errorf("target Deployment not readable: %w", err)
	}
	cs := deploy.Spec.Template.Spec.Containers
	if target.Spec.Container != "" {
		for i := range cs {
			if cs[i].Name == target.Spec.Container {
				return cs[i].Image, nil
			}
		}
		return "", fmt.Errorf("container %q not found in target Deployment", target.Spec.Container)
	}
	if len(cs) == 1 {
		return cs[0].Image, nil
	}
	return "", fmt.Errorf("target Deployment has %d containers; its spec.container must name one", len(cs))
}

func jobBackoffExhausted(job *batchv1.Job) bool {
	limit := int32(0)
	if job.Spec.BackoffLimit != nil {
		limit = *job.Spec.BackoffLimit
	}
	return job.Status.Failed > limit
}

func (r *ClosureJVMCampaignReconciler) pending(ctx context.Context, c *closurejvmv1alpha1.ClosureJVMCampaign, reason, msg string) (ctrl.Result, error) {
	c.Status.Phase = closurejvmv1alpha1.CampaignPending
	meta.SetStatusCondition(&c.Status.Conditions, metav1.Condition{
		Type: "Ready", Status: metav1.ConditionFalse, Reason: reason, Message: msg})
	if err := r.Status().Update(ctx, c); err != nil {
		return ctrl.Result{}, err
	}
	return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
}

func (r *ClosureJVMCampaignReconciler) fail(ctx context.Context, c *closurejvmv1alpha1.ClosureJVMCampaign, reason, msg string) (ctrl.Result, error) {
	c.Status.Phase = closurejvmv1alpha1.CampaignFailed
	now := metav1.Now()
	c.Status.CompletionTime = &now
	meta.SetStatusCondition(&c.Status.Conditions, metav1.Condition{
		Type: "Ready", Status: metav1.ConditionFalse, Reason: reason, Message: msg})
	return ctrl.Result{}, r.Status().Update(ctx, c)
}

// SetupWithManager wires the controller to watch campaigns and the driver Jobs they own.
func (r *ClosureJVMCampaignReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&closurejvmv1alpha1.ClosureJVMCampaign{}).
		Owns(&batchv1.Job{}).
		Complete(r)
}
