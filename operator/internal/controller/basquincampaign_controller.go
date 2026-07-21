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
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
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
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

// BasquinCampaignReconciler drives a bounded coverage-guided test run against an instrumented
// BasquinTarget (docs/CAMPAIGN-DESIGN.md, DD-025). P5a scope: gate on the target, launch the
// driver Job (with the coverage-classes initContainer), and aggregate its result from the pod's
// termination message. The per-campaign dashboard is P5b.
type BasquinCampaignReconciler struct {
	client.Client
	Scheme *runtime.Scheme
	// RunnerImage is the coverage-guided runner image the driver Job runs. Empty uses the default.
	RunnerImage string
	// DashboardImage is the per-campaign dashboard image (P5b). Empty uses the default.
	DashboardImage string
}

//+kubebuilder:rbac:groups=basquin.dev,resources=basquincampaigns,verbs=get;list;watch;update;patch
//+kubebuilder:rbac:groups=basquin.dev,resources=basquincampaigns/status,verbs=get;update;patch
//+kubebuilder:rbac:groups=basquin.dev,resources=basquincampaigns/finalizers,verbs=update
//+kubebuilder:rbac:groups=batch,resources=jobs,verbs=get;list;watch;create;delete
//+kubebuilder:rbac:groups=apps,resources=deployments,verbs=get;list;watch;create;delete
//+kubebuilder:rbac:groups=core,resources=services,verbs=get;list;watch;create;delete
//+kubebuilder:rbac:groups=core,resources=pods,verbs=get;list;watch
//+kubebuilder:rbac:groups=core,resources=configmaps,verbs=get;list;watch;create;update
// The per-campaign dashboard token Secret. No delete verb: the Secret is owner-referenced to the
// campaign, so Kubernetes GCs it when the campaign goes away. No update: the token is minted once
// and never rotated mid-campaign.
//+kubebuilder:rbac:groups=core,resources=secrets,verbs=get;list;watch;create

func (r *BasquinCampaignReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	l := log.FromContext(ctx)

	var campaign basquinv1alpha1.BasquinCampaign
	if err := r.Get(ctx, req.NamespacedName, &campaign); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}
	campaign.Status.ObservedGeneration = campaign.Generation

	// A terminal campaign stays put — a bounded run doesn't restart itself on resync.
	if campaign.Status.Phase == basquinv1alpha1.CampaignCompleted ||
		campaign.Status.Phase == basquinv1alpha1.CampaignFailed {
		return ctrl.Result{}, nil
	}

	runnerImage := r.RunnerImage
	if runnerImage == "" {
		runnerImage = defaultRunnerImage
	}

	// --- gate on the target being instrumented -------------------------------------------------
	var target basquinv1alpha1.BasquinTarget
	tkey := types.NamespacedName{Namespace: campaign.Namespace, Name: campaign.Spec.TargetRef.Name}
	if err := r.Get(ctx, tkey, &target); err != nil {
		if apierrors.IsNotFound(err) {
			// If a run was already going and the target vanished, that's a distinct terminal failure —
			// not silent connection-refused "findings".
			if campaign.Status.Phase == basquinv1alpha1.CampaignRunning {
				return r.fail(ctx, &campaign, "TargetGone", "the referenced BasquinTarget was deleted mid-run")
			}
			return r.pending(ctx, &campaign, "TargetNotFound",
				fmt.Sprintf("BasquinTarget %q not found", campaign.Spec.TargetRef.Name))
		}
		return ctrl.Result{}, err
	}
	if target.Status.Phase != basquinv1alpha1.PhaseInjected {
		// A target that drops out of Injected mid-run is the same class of event as it being deleted
		// (§7c): a Running campaign fails terminally rather than silently regressing to Pending and
		// never inspecting the driver Job again.
		if campaign.Status.Phase == basquinv1alpha1.CampaignRunning {
			return r.fail(ctx, &campaign, "TargetGone",
				fmt.Sprintf("target %q dropped out of Injected (now %q) mid-run", target.Name, target.Status.Phase))
		}
		return r.pending(ctx, &campaign, "TargetNotInjected",
			fmt.Sprintf("target %q is %q, waiting for Injected", target.Name, target.Status.Phase))
	}
	// Coverage is explore-only; a load run replays a corpus and doesn't sample coverage.
	if campaign.Spec.Mode != "load" && target.Status.CoverageEndpoint == "" {
		return r.pending(ctx, &campaign, "NoCoverageEndpoint",
			"target has no status.coverageEndpoint (set spec.coverageService on the target)")
	}

	// The .class files come from the target's app-container image (explore only — load skips them, but
	// resolving the image is cheap and harmless).
	appImage, err := r.targetAppImage(ctx, &target)
	if err != nil {
		return r.pending(ctx, &campaign, "TargetDeploymentNotReady", err.Error())
	}

	// --- ensure the dashboard (P5b) ------------------------------------------------------------
	// Resolve where the driver pushes status/findings: a per-campaign dashboard the operator brings
	// up (default), an external/shared one, or nowhere (disabled). Sets status.dashboardURL as a
	// side effect; the value is persisted by the Status().Update calls below.
	dashboardPush, dashboardTokenSecret, derr := r.ensureDashboard(ctx, &campaign)
	if derr != nil {
		return ctrl.Result{}, derr
	}

	// --- ensure the driver Job -----------------------------------------------------------------
	// Hash the run-defining spec now, from the spec as stored (before the NotFound branch resolves
	// GrammarKey in-memory), so the created-Job annotation and the job-exists comparison agree.
	specHash := driverSpecHash(&campaign)
	var job batchv1.Job
	jkey := types.NamespacedName{Namespace: campaign.Namespace, Name: driverJobName(&campaign)}
	switch err := r.Get(ctx, jkey, &job); {
	case apierrors.IsNotFound(err):
		// Resolve the grammar ConfigMap's sole key when the user didn't name one, so the driver's
		// -Dbasquin.grammar points at a real file rather than a directory.
		if campaign.Spec.Driver.GrammarConfigMap != "" && campaign.Spec.Driver.GrammarKey == "" {
			key, kerr := r.resolveGrammarKey(ctx, campaign.Namespace, campaign.Spec.Driver.GrammarConfigMap)
			if kerr != nil {
				return r.pending(ctx, &campaign, "GrammarUnresolved", kerr.Error())
			}
			campaign.Spec.Driver.GrammarKey = key // in-memory, consumed by buildDriverJob
		}
		desired := buildDriverJob(&campaign, appImage, target.Status.CoverageEndpoint, runnerImage, dashboardPush, dashboardTokenSecret)
		// Stamp the run-defining spec hash so a later spec edit is detected as a NEW run (§7c). Compute
		// it from the spec as stored (before the in-memory GrammarKey resolution above), so the value
		// matches what the job-exists branch recomputes from the unmutated spec on the next reconcile.
		if desired.Annotations == nil {
			desired.Annotations = map[string]string{}
		}
		desired.Annotations[specHashAnnotation] = specHash
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
		campaign.Status.Phase = basquinv1alpha1.CampaignRunning
		meta.SetStatusCondition(&campaign.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionFalse, Reason: "Running", Message: "driver Job launched"})
		if uerr := r.Status().Update(ctx, &campaign); uerr != nil {
			return ctrl.Result{}, uerr
		}
		return ctrl.Result{RequeueAfter: 5 * time.Second}, nil
	case err != nil:
		return ctrl.Result{}, err
	}

	// --- spec edit mid-run → new run (§7c) -----------------------------------------------------
	// The driver Job is immutable and create-if-missing, so without this a spec edit while Running is
	// a silent no-op. If the run-defining spec changed, delete the stale Job (propagating to its pods)
	// and recreate on the next reconcile as a fresh run.
	if job.Annotations[specHashAnnotation] != specHash {
		l.Info("campaign spec changed since the driver Job was created; recreating", "job", job.Name)
		policy := metav1.DeletePropagationBackground
		if derr := r.Delete(ctx, &job, &client.DeleteOptions{PropagationPolicy: &policy}); derr != nil && !apierrors.IsNotFound(derr) {
			return ctrl.Result{}, derr
		}
		// Drop the stale run's DRIVER pods so the recreated run's status isn't read from the old pod.
		// Scope to component=driver: the dashboard pod shares the basquin.dev/campaign label, and it
		// must survive a rerun (only the driver run restarts).
		_ = r.DeleteAllOf(ctx, &corev1.Pod{}, client.InNamespace(campaign.Namespace),
			client.MatchingLabels{"basquin.dev/campaign": campaign.Name, "app.kubernetes.io/component": "driver"})
		campaign.Status.Phase = basquinv1alpha1.CampaignProvisioning
		if uerr := r.Status().Update(ctx, &campaign); uerr != nil {
			return ctrl.Result{}, uerr
		}
		return ctrl.Result{RequeueAfter: 3 * time.Second}, nil
	}

	// --- aggregate the Job's outcome -----------------------------------------------------------
	if job.Status.Succeeded > 0 {
		if s := r.readDriverSummary(ctx, &campaign); s != nil {
			if campaign.Spec.Mode == "load" {
				// Load/soak result (DD-026 PR 2): throughput/latency/drift. No coverage, no corpus emit
				// (a load run consumes a corpus, it doesn't produce one).
				campaign.Status.Load = s.Load
			} else {
				campaign.Status.CoveragePct = fmt.Sprintf("%.1f", s.Exploration.Coverage.Pct)
				campaign.Status.Findings = s.Exploration.Corpus
				// Persist the interesting "replay corpus" as a campaign-owned ConfigMap (DD-026 PR 1) —
				// for reproducibility, the dashboard corpus view, and load-mode replay. The driver stays
				// credential-less: it wrote the corpus into its summary (termination message); the
				// operator, which holds the RBAC, materializes the ConfigMap here. Emit BEFORE the
				// terminal phase flip: a terminal campaign never reconciles again (top-of-func guard), so
				// a transient failure would otherwise permanently forfeit the corpus. On failure, stay
				// Running and retry next reconcile (the create-or-update is idempotent).
				if len(s.ReplayCorpus) > 0 {
					if err := r.emitCorpusConfigMap(ctx, &campaign, s.ReplayCorpus); err != nil {
						l.Error(err, "emitting replay-corpus ConfigMap; will retry")
						campaign.Status.Phase = basquinv1alpha1.CampaignRunning
						if uerr := r.Status().Update(ctx, &campaign); uerr != nil {
							return ctrl.Result{}, uerr
						}
						return ctrl.Result{RequeueAfter: 5 * time.Second}, nil
					}
				}
			}
		}
		campaign.Status.Phase = basquinv1alpha1.CampaignCompleted
		now := metav1.Now()
		campaign.Status.CompletionTime = &now
		msg := fmt.Sprintf("run complete: coverage %s%%, %d findings", campaign.Status.CoveragePct, campaign.Status.Findings)
		if campaign.Spec.Mode == "load" && campaign.Status.Load != nil {
			ld := campaign.Status.Load
			msg = fmt.Sprintf("load complete: %d requests, %s rps, p99 %dms, %d latency violations",
				ld.Requests, ld.ThroughputRps, ld.LatencyMs.P99, ld.Violations.Latency)
		}
		meta.SetStatusCondition(&campaign.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionTrue, Reason: "Completed", Message: msg})
		return ctrl.Result{}, r.Status().Update(ctx, &campaign)
	}
	if job.Status.Failed > 0 && jobBackoffExhausted(&job) {
		// Surface a failed initContainer's reason (e.g. verify-classes: "no .class files extracted …")
		// in campaign status, so the failure is legible in `kubectl get basquincampaign`, not only in
		// pod logs. Falls back to the generic reason for a driver-container crash (review #24).
		if name, msg := r.failedInitContainer(ctx, &campaign); name != "" {
			return r.fail(ctx, &campaign, "InitContainerFailed", fmt.Sprintf("%s: %s", name, msg))
		}
		return r.fail(ctx, &campaign, "DriverFailed", "the driver Job failed")
	}

	// Still running.
	campaign.Status.Phase = basquinv1alpha1.CampaignRunning
	if err := r.Status().Update(ctx, &campaign); err != nil {
		return ctrl.Result{}, err
	}
	return ctrl.Result{RequeueAfter: 10 * time.Second}, nil
}

// driverSummary is the subset of the driver's end-of-run summary the campaign surfaces.
type driverSummary struct {
	Exploration struct {
		Corpus   int32 `json:"corpus"`
		Coverage struct {
			Pct float64 `json:"pct"`
		} `json:"coverage"`
	} `json:"exploration"`
	// ReplayCorpus is the capped set of interesting inputs the run fired (DD-026 PR 1); the operator
	// materializes it into status.corpusConfigMap.
	ReplayCorpus []string `json:"replayCorpus"`
	// Load is the load/soak run's result (DD-026 PR 2); the JSON tags match the API LoadStatus so it
	// unmarshals straight into status.load.
	Load *basquinv1alpha1.LoadStatus `json:"load"`
}

const corpusOutKey = "corpus.txt"

func corpusConfigMapName(c *basquinv1alpha1.BasquinCampaign) string {
	return c.Name + "-corpus-out"
}

// emitCorpusConfigMap materializes the run's replay corpus into a campaign-owned ConfigMap (one key,
// newline-joined) and records it in status.corpusConfigMap. Create-or-update (idempotent on requeue).
func (r *BasquinCampaignReconciler) emitCorpusConfigMap(ctx context.Context, c *basquinv1alpha1.BasquinCampaign, corpus []string) error {
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: corpusConfigMapName(c), Namespace: c.Namespace},
		Data:       map[string]string{corpusOutKey: strings.Join(corpus, "\n") + "\n"},
	}
	if err := controllerutil.SetControllerReference(c, cm, r.Scheme); err != nil {
		return err
	}
	var existing corev1.ConfigMap
	switch err := r.Get(ctx, types.NamespacedName{Namespace: cm.Namespace, Name: cm.Name}, &existing); {
	case apierrors.IsNotFound(err):
		if cerr := r.Create(ctx, cm); cerr != nil && !apierrors.IsAlreadyExists(cerr) {
			return cerr
		}
	case err != nil:
		return err
	default:
		existing.Data = cm.Data
		existing.OwnerReferences = cm.OwnerReferences
		if uerr := r.Update(ctx, &existing); uerr != nil {
			return uerr
		}
	}
	c.Status.CorpusConfigMap = cm.Name
	return nil
}

// readDriverSummary finds the driver Job's pod and parses the summary the runner wrote to its
// termination message (DD-025 §7a).
func (r *BasquinCampaignReconciler) readDriverSummary(ctx context.Context, c *basquinv1alpha1.BasquinCampaign) *driverSummary {
	var pods corev1.PodList
	if err := r.List(ctx, &pods, client.InNamespace(c.Namespace),
		client.MatchingLabels{"basquin.dev/campaign": c.Name}); err != nil {
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

// failedInitContainer finds a driver pod whose initContainer terminated non-zero and returns its name
// and termination message (e.g. verify-classes explaining an empty class extract). Returns "","" if
// no init container failed — the caller then reports the generic driver-failure reason.
func (r *BasquinCampaignReconciler) failedInitContainer(ctx context.Context, c *basquinv1alpha1.BasquinCampaign) (string, string) {
	var pods corev1.PodList
	if err := r.List(ctx, &pods, client.InNamespace(c.Namespace),
		client.MatchingLabels{"basquin.dev/campaign": c.Name, "app.kubernetes.io/component": "driver"}); err != nil {
		return "", ""
	}
	for i := range pods.Items {
		for _, cs := range pods.Items[i].Status.InitContainerStatuses {
			if t := cs.State.Terminated; t != nil && t.ExitCode != 0 {
				msg := t.Message
				if msg == "" {
					msg = fmt.Sprintf("exited %d (%s)", t.ExitCode, t.Reason)
				}
				return cs.Name, msg
			}
		}
	}
	return "", ""
}

// targetAppImage resolves the app container's image from the target's Deployment (the .class source).
func (r *BasquinCampaignReconciler) targetAppImage(ctx context.Context, target *basquinv1alpha1.BasquinTarget) (string, error) {
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

// ensureDashboard resolves where the driver pushes status/findings and, for a per-campaign dashboard,
// brings up its Deployment + Service (P5b). It returns the push target as host:port ("" = don't push)
// and sets c.Status.DashboardURL as a side effect (persisted by the caller's Status().Update).
//   - disabled                → no dashboard, no push
//   - externalPush set        → push to that shared dashboard; the operator creates nothing
//   - enabled, no externalPush → create/own a per-campaign dashboard and push to it
func (r *BasquinCampaignReconciler) ensureDashboard(ctx context.Context, c *basquinv1alpha1.BasquinCampaign) (string, string, error) {
	// Default (nil) is enabled; only an explicit false disables it. If a per-campaign dashboard was
	// created earlier and the spec later flips to disabled / externalPush, tear it down now (it's
	// otherwise orphaned until the whole CR is deleted) — spec.dashboard isn't immutable, so a
	// `kubectl apply` edit reaches these branches on the next reconcile.
	if c.Spec.Dashboard.Enabled != nil && !*c.Spec.Dashboard.Enabled {
		if err := r.deleteDashboard(ctx, c); err != nil {
			return "", "", err
		}
		c.Status.DashboardURL = ""
		return "", "", nil
	}
	if push := c.Spec.Dashboard.ExternalPush; push != "" {
		if err := r.deleteDashboard(ctx, c); err != nil {
			return "", "", err
		}
		c.Status.DashboardURL = "http://" + push
		// externalPush: not our dashboard, so we have no token to authenticate with.
		return push, "", nil
	}

	image := r.DashboardImage
	if image == "" {
		image = defaultDashboardImage
	}

	// Token Secret FIRST: both the dashboard and the driver mount it, and a pod referencing a missing
	// Secret stays stuck in CreateContainerConfigError. Get-or-create, never rotate: the dashboard
	// reads the token once at JVM start, so re-generating it mid-campaign would leave a running
	// dashboard and a running driver disagreeing about the shared secret.
	tokenSecret := dashboardTokenSecretName(c)
	var sec corev1.Secret
	skey := types.NamespacedName{Namespace: c.Namespace, Name: tokenSecret}
	if err := r.Get(ctx, skey, &sec); apierrors.IsNotFound(err) {
		token, terr := newDashboardToken()
		if terr != nil {
			return "", "", terr
		}
		desired := buildDashboardTokenSecret(c, token)
		if serr := controllerutil.SetControllerReference(c, desired, r.Scheme); serr != nil {
			return "", "", serr
		}
		if cerr := r.Create(ctx, desired); cerr != nil && !apierrors.IsAlreadyExists(cerr) {
			return "", "", cerr
		}
	} else if err != nil {
		return "", "", err
	}

	// Deployment (create-if-missing; the read-only DashboardServer needs no spec-driven updates).
	var dep appsv1.Deployment
	dkey := types.NamespacedName{Namespace: c.Namespace, Name: dashboardName(c)}
	if err := r.Get(ctx, dkey, &dep); apierrors.IsNotFound(err) {
		desired := buildDashboardDeployment(c, image, tokenSecret)
		if serr := controllerutil.SetControllerReference(c, desired, r.Scheme); serr != nil {
			return "", "", serr
		}
		if cerr := r.Create(ctx, desired); cerr != nil && !apierrors.IsAlreadyExists(cerr) {
			return "", "", cerr
		}
	} else if err != nil {
		return "", "", err
	}

	// Service (create-if-missing).
	var svc corev1.Service
	if err := r.Get(ctx, dkey, &svc); apierrors.IsNotFound(err) {
		desired := buildDashboardService(c)
		if serr := controllerutil.SetControllerReference(c, desired, r.Scheme); serr != nil {
			return "", "", serr
		}
		if cerr := r.Create(ctx, desired); cerr != nil && !apierrors.IsAlreadyExists(cerr) {
			return "", "", cerr
		}
	} else if err != nil {
		return "", "", err
	}

	// In-cluster host:port; the driver posts to http://<host>/ingest/... (DashboardClient adds scheme).
	push := fmt.Sprintf("%s.%s.svc.cluster.local:%d", dashboardName(c), c.Namespace, dashboardPort)
	c.Status.DashboardURL = "http://" + push
	return push, tokenSecret, nil
}

// newDashboardToken mints a 256-bit random shared secret for one campaign's dashboard.
func newDashboardToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// deleteDashboard removes a per-campaign dashboard Deployment + Service if present (idempotent). Used
// when a campaign's dashboard config flips away from "own a dashboard" after one was already created.
// The token Secret is deliberately NOT deleted here: RBAC grants no delete verb on secrets, and the
// Secret is owner-referenced to the campaign so Kubernetes GCs it with the campaign — until then a
// disabled/externalPush campaign keeps a consumer-less credential in the namespace (#43 review).
func (r *BasquinCampaignReconciler) deleteDashboard(ctx context.Context, c *basquinv1alpha1.BasquinCampaign) error {
	name := types.NamespacedName{Namespace: c.Namespace, Name: dashboardName(c)}
	if err := r.Delete(ctx, &appsv1.Deployment{ObjectMeta: metav1.ObjectMeta{Name: name.Name, Namespace: name.Namespace}}); err != nil && !apierrors.IsNotFound(err) {
		return err
	}
	if err := r.Delete(ctx, &corev1.Service{ObjectMeta: metav1.ObjectMeta{Name: name.Name, Namespace: name.Namespace}}); err != nil && !apierrors.IsNotFound(err) {
		return err
	}
	return nil
}

// resolveGrammarKey returns the grammar ConfigMap's sole key (used when the campaign didn't name one),
// erroring if the ConfigMap is unreadable or ambiguous (more than one key).
func (r *BasquinCampaignReconciler) resolveGrammarKey(ctx context.Context, ns, name string) (string, error) {
	var cm corev1.ConfigMap
	if err := r.Get(ctx, types.NamespacedName{Namespace: ns, Name: name}, &cm); err != nil {
		return "", fmt.Errorf("grammar ConfigMap %q not readable: %w", name, err)
	}
	if len(cm.Data) == 1 {
		for k := range cm.Data {
			return k, nil
		}
	}
	return "", fmt.Errorf("grammar ConfigMap %q has %d keys; set spec.driver.grammarKey to pick one", name, len(cm.Data))
}

// campaignsForTarget maps a BasquinTarget event to the campaigns that reference it, so a Pending
// campaign starts the moment its target becomes Injected (rather than only on the 15s poll).
func (r *BasquinCampaignReconciler) campaignsForTarget(ctx context.Context, obj client.Object) []reconcile.Request {
	var campaigns basquinv1alpha1.BasquinCampaignList
	if err := r.List(ctx, &campaigns, client.InNamespace(obj.GetNamespace())); err != nil {
		return nil
	}
	var reqs []reconcile.Request
	for i := range campaigns.Items {
		if campaigns.Items[i].Spec.TargetRef.Name == obj.GetName() {
			reqs = append(reqs, reconcile.Request{NamespacedName: types.NamespacedName{
				Namespace: campaigns.Items[i].Namespace, Name: campaigns.Items[i].Name}})
		}
	}
	return reqs
}

// specHashAnnotation stores the hash of a campaign's run-defining spec on its driver Job, so a later
// spec edit is detected as a new run rather than silently ignored (DD-025 §7c).
const specHashAnnotation = "basquin.dev/spec-hash"

// driverSpecHash hashes the campaign fields that define a run (what buildDriverJob consumes). It's
// computed from the spec as stored — the in-memory GrammarKey resolution isn't persisted, so both the
// create-time stamp and the later comparison hash the same value. Target-driven inputs (app image,
// coverage endpoint) are deliberately excluded: those changing is handled as TargetGone, not a rerun.
//
// This hashes the Driver/Dashboard structs whole (today every field of both feeds the Job, so that
// equals an allowlist). Note for future edits: ANY new field added to CampaignDriverSpec or
// CampaignDashboardSpec joins this hash and will trigger a rerun on edit even if buildDriverJob never
// reads it — exclude display-only/non-run-defining fields here if that's not desired.
// Upgrade note: driver Jobs created before this annotation existed hash-mismatch on first reconcile,
// so every in-flight Running campaign restarts once on rollout to this version.
func driverSpecHash(c *basquinv1alpha1.BasquinCampaign) string {
	payload := struct {
		BaseURL   string
		Mode      string
		Driver    basquinv1alpha1.CampaignDriverSpec
		Dashboard basquinv1alpha1.CampaignDashboardSpec
	}{c.Spec.BaseURL, c.Spec.Mode, c.Spec.Driver, c.Spec.Dashboard}
	b, _ := json.Marshal(payload)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:8]) // 16 hex chars — ample to distinguish spec revisions
}

func jobBackoffExhausted(job *batchv1.Job) bool {
	limit := int32(0)
	if job.Spec.BackoffLimit != nil {
		limit = *job.Spec.BackoffLimit
	}
	return job.Status.Failed > limit
}

func (r *BasquinCampaignReconciler) pending(ctx context.Context, c *basquinv1alpha1.BasquinCampaign, reason, msg string) (ctrl.Result, error) {
	c.Status.Phase = basquinv1alpha1.CampaignPending
	meta.SetStatusCondition(&c.Status.Conditions, metav1.Condition{
		Type: "Ready", Status: metav1.ConditionFalse, Reason: reason, Message: msg})
	if err := r.Status().Update(ctx, c); err != nil {
		return ctrl.Result{}, err
	}
	return ctrl.Result{RequeueAfter: 15 * time.Second}, nil
}

func (r *BasquinCampaignReconciler) fail(ctx context.Context, c *basquinv1alpha1.BasquinCampaign, reason, msg string) (ctrl.Result, error) {
	c.Status.Phase = basquinv1alpha1.CampaignFailed
	now := metav1.Now()
	c.Status.CompletionTime = &now
	meta.SetStatusCondition(&c.Status.Conditions, metav1.Condition{
		Type: "Ready", Status: metav1.ConditionFalse, Reason: reason, Message: msg})
	return ctrl.Result{}, r.Status().Update(ctx, c)
}

// SetupWithManager wires the controller to watch campaigns and the driver Jobs they own.
func (r *BasquinCampaignReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&basquinv1alpha1.BasquinCampaign{}).
		Owns(&batchv1.Job{}).
		// Re-reconcile a campaign when its referenced target changes (e.g. becomes Injected), so a
		// Pending campaign starts promptly instead of waiting for the poll.
		Watches(&basquinv1alpha1.BasquinTarget{}, handler.EnqueueRequestsFromMapFunc(r.campaignsForTarget)).
		Complete(r)
}
