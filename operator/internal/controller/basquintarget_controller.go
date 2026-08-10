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
	"time"

	appsv1 "k8s.io/api/apps/v1"
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

// BasquinTargetReconciler reconciles a BasquinTarget object.
//
// P2 SCOPE (docs/OPERATOR-DESIGN.md §4, DD-024): the reconciler now INJECTS. It patches the
// referenced Deployment's pod template to carry the agents (initContainer + shared emptyDir +
// appended jvmOptsVar + coverage port), idempotently (a spec-hash annotation makes a steady target a
// no-op), and it holds a finalizer so deleting the target reverts the Deployment to exactly its
// pre-injection state. The injection/revert mechanics live in injection.go.
type BasquinTargetReconciler struct {
	client.Client
	Scheme *runtime.Scheme
	// AgentsImage is the image the injected initContainer copies the agents from. Empty uses
	// defaultAgentsImage. Set from a flag/env in main.go.
	AgentsImage string
}

//+kubebuilder:rbac:groups=basquin.dev,resources=basquintargets,verbs=get;list;watch;update;patch
//+kubebuilder:rbac:groups=basquin.dev,resources=basquintargets/status,verbs=get;update;patch
//+kubebuilder:rbac:groups=basquin.dev,resources=basquintargets/finalizers,verbs=update
//+kubebuilder:rbac:groups=apps,resources=deployments,verbs=get;list;watch;update;patch
//+kubebuilder:rbac:groups=core,resources=services,verbs=get;list;watch;create;update;patch;delete

// Reconcile drives the referenced Deployment toward the target's desired instrumentation, and
// reverts it on target deletion.
func (r *BasquinTargetReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	l := log.FromContext(ctx)

	var target basquinv1alpha1.BasquinTarget
	if err := r.Get(ctx, req.NamespacedName, &target); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// --- deletion: revert, then release the finalizer -------------------------------------------
	if !target.DeletionTimestamp.IsZero() {
		if controllerutil.ContainsFinalizer(&target, finalizerName) {
			if err := r.revertDeployment(ctx, &target); err != nil {
				return ctrl.Result{}, err
			}
			controllerutil.RemoveFinalizer(&target, finalizerName)
			if err := r.Update(ctx, &target); err != nil {
				return ctrl.Result{}, err
			}
		}
		return ctrl.Result{}, nil
	}

	// Ensure the finalizer is present before we mutate anything the CR is responsible for reverting.
	if controllerutil.AddFinalizer(&target, finalizerName) {
		if err := r.Update(ctx, &target); err != nil {
			return ctrl.Result{}, err
		}
		// Requeue: the update changed the object; reconcile the injection on the next pass.
		return ctrl.Result{Requeue: true}, nil
	}

	agentsImage := r.AgentsImage
	if agentsImage == "" {
		agentsImage = defaultAgentsImage
	}

	target.Status.ObservedGeneration = target.Generation

	// Load the referenced Deployment.
	var deploy appsv1.Deployment
	depKey := types.NamespacedName{Namespace: target.Namespace, Name: target.Spec.DeploymentRef.Name}
	if err := r.Get(ctx, depKey, &deploy); err != nil {
		if apierrors.IsNotFound(err) {
			target.Status.Phase = basquinv1alpha1.PhasePending
			target.Status.InstrumentedReplicas = 0
			// The Deployment is gone: tear down the coverage Service and clear the endpoint so we
			// don't keep a Service with a dead selector and publish a stale coverage endpoint.
			if cerr := r.removeCoverageService(ctx, &target); cerr != nil {
				return ctrl.Result{}, cerr
			}
			// Leaving the observe path (there is no Deployment left to observe): a stale
			// multi-replica warning must not survive it.
			meta.RemoveStatusCondition(&target.Status.Conditions, conditionReplicaConfigSupported)
			meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
				Type:   "Ready",
				Status: metav1.ConditionFalse,
				Reason: "DeploymentNotFound",
				Message: fmt.Sprintf("spec.deploymentRef.name %q not found in namespace %s",
					target.Spec.DeploymentRef.Name, target.Namespace),
			})
			if uerr := r.Status().Update(ctx, &target); uerr != nil {
				return ctrl.Result{}, uerr
			}
			// The Deployment watch will re-trigger when it appears; the requeue is a belt-and-braces
			// fallback in case the target is created long before its Deployment.
			return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
		}
		return ctrl.Result{}, err
	}

	// --- pre-instrumented observe path (DD-044 / PR-3.5) ------------------------------------------
	// spec.PreInstrumented declares the image already carries Basquin's instrumentation at build
	// time (basquin-maven-injector). The operator OBSERVES the Deployment and never mutates its pod
	// template — except to revert an injection WE previously applied, if preInstrumented was
	// flipped true on a target we had already injected (design §2.2b). Branching here, before the
	// inject-if-drifted block below, is what makes "never mutates" true: this must never fall
	// through to applyInjection.
	if target.Spec.PreInstrumented {
		if err := r.reconcileObserve(ctx, &target, &deploy); err != nil {
			return ctrl.Result{}, err
		}
	} else {
		// The target may carry the ReplicaConfigSupported warning from an earlier
		// preInstrumented:true stint; it's meaningless once back on the injection path.
		meta.RemoveStatusCondition(&target.Status.Conditions, conditionReplicaConfigSupported)

		// --- inject if drifted ----------------------------------------------------------------
		wantHash := specHash(&target.Spec, agentsImage)
		if !injectionApplied(&deploy, &target.Spec, wantHash) {
			// If a previous injection exists (spec changed, or out-of-band content drift), revert it
			// first so applyInjection re-derives from a clean original — this also un-instruments the
			// old container when spec.Container is retargeted, rather than leaving it instrumented.
			reverted := false
			if wasInjected(&deploy) {
				revertInjection(&deploy)
				reverted = true
			}
			if err := applyInjection(&deploy, &target.Spec, agentsImage); err != nil {
				// A bad container reference or a valueFrom-sourced jvmOptsVar is the user's to fix;
				// surface it and stop. Persist the Deployment ONLY if we actually reverted a prior
				// injection — otherwise `deploy` is unchanged, and writing it would emit a no-op
				// MODIFIED event that our own Deployment watch re-enqueues, storming on a target that
				// is permanently misconfigured (e.g. a typo'd spec.Container).
				if reverted {
					if uerr := r.Update(ctx, &deploy); uerr != nil {
						return ctrl.Result{}, uerr
					}
				}
				target.Status.Phase = basquinv1alpha1.PhaseError
				meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
					Type: "Ready", Status: metav1.ConditionFalse, Reason: "InjectionRejected",
					Message: err.Error(),
				})
				if uerr := r.Status().Update(ctx, &target); uerr != nil {
					return ctrl.Result{}, uerr
				}
				return ctrl.Result{}, nil
			}
			if err := r.Update(ctx, &deploy); err != nil {
				return ctrl.Result{}, err
			}
			l.Info("injected agents into Deployment", "deployment", depKey.Name, "hash", wantHash)
		}

		// --- coverage Service (P3) --------------------------------------------------------------
		if err := r.reconcileCoverageService(ctx, &target, &deploy); err != nil {
			return ctrl.Result{}, err
		}

		// --- status ------------------------------------------------------------------------------
		desired := int32(1)
		if deploy.Spec.Replicas != nil {
			desired = *deploy.Spec.Replicas
		}
		// UpdatedReplicas counts pods already on the latest (now injected) template.
		instrumented := deploy.Status.UpdatedReplicas
		target.Status.InstrumentedReplicas = instrumented
		if instrumented >= desired && desired > 0 {
			target.Status.Phase = basquinv1alpha1.PhaseInjected
			meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
				Type: "Ready", Status: metav1.ConditionTrue, Reason: "Injected",
				Message: fmt.Sprintf("all %d replica(s) instrumented", desired),
			})
		} else {
			target.Status.Phase = basquinv1alpha1.PhaseInjecting
			meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
				Type: "Ready", Status: metav1.ConditionFalse, Reason: "RollingOut",
				Message: fmt.Sprintf("%d/%d replica(s) instrumented", instrumented, desired),
			})
		}
	}

	if err := r.Status().Update(ctx, &target); err != nil {
		return ctrl.Result{}, err
	}
	// While a rollout, a revert, or an observe wait is in flight, poll until it catches up (the
	// Deployment watch also nudges us, but its status subresource updates don't always route
	// through our predicate).
	switch target.Status.Phase {
	case basquinv1alpha1.PhaseInjecting, basquinv1alpha1.PhaseReverting, basquinv1alpha1.PhaseObserving:
		return ctrl.Result{RequeueAfter: 5 * time.Second}, nil
	}
	return ctrl.Result{}, nil
}

// conditionReplicaConfigSupported is a WARNING-only condition (design §4): PR-3.5 supports only
// single-replica pre-instrumented targets. It never gates Observed or campaign readiness.
const conditionReplicaConfigSupported = "ReplicaConfigSupported"

// setReplicaConfigCondition always writes conditionReplicaConfigSupported on the observe path —
// True/SingleReplica for the supported case, False/MultiReplicaUnsupported otherwise — so a
// scale-back from >1 replica to 1 is reflected immediately (never left stale) without needing
// removal while still on the observe path. Removal is for LEAVING the observe path entirely; see
// the two meta.RemoveStatusCondition call sites in Reconcile.
func setReplicaConfigCondition(target *basquinv1alpha1.BasquinTarget, desired int32) {
	if desired > 1 {
		meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
			Type: conditionReplicaConfigSupported, Status: metav1.ConditionFalse, Reason: "MultiReplicaUnsupported",
			Message: fmt.Sprintf(
				"pre-instrumented targets support only a single replica (PR-3.5); this Deployment requests %d",
				desired),
		})
		return
	}
	meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
		Type: conditionReplicaConfigSupported, Status: metav1.ConditionTrue, Reason: "SingleReplica",
		Message: "single replica, which PR-3.5 supports",
	})
}

// reconcileObserve computes status for a preInstrumented: true target. It never mutates the
// referenced Deployment's pod template — except to revert an injection WE previously applied
// (design §2.2b) — and it never creates a coverage Service (design §4: coverage for
// build-time-instrumented targets is PR-4's work).
func (r *BasquinTargetReconciler) reconcileObserve(ctx context.Context,
	target *basquinv1alpha1.BasquinTarget, deploy *appsv1.Deployment) error {
	// A pre-instrumented target never has a coverage Service, regardless of spec.CoverageService —
	// tear down any that predates preInstrumented being set, and clear the stale endpoint with it.
	if err := r.removeCoverageService(ctx, target); err != nil {
		return err
	}

	desired := int32(1)
	if deploy.Spec.Replicas != nil {
		desired = *deploy.Spec.Replicas
	}
	// Warning-only condition (design §4): always written on the observe path, never gates Observed
	// or readiness below.
	setReplicaConfigCondition(target, desired)

	if wasInjected(deploy) {
		// design §2.2b — revert-before-observe: our own prior injection is present on this
		// Deployment. Claiming "operator did not modify the pod template" would be a lie until this
		// lands, so revert first and do NOT fall through to the readiness check below. Reusing
		// revertInjection (already exactly pinned by test) rather than adding a second removal path.
		revertInjection(deploy)
		if err := r.Update(ctx, deploy); err != nil {
			return err
		}
		target.Status.Phase = basquinv1alpha1.PhaseReverting
		target.Status.InstrumentedReplicas = 0
		meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionFalse, Reason: "Reverting",
			Message: "reverting a prior injection before observing (preInstrumented was enabled on an already-injected target)",
		})
		return nil
	}

	// Observed is STICKY (Locked decisions): once minted, a transient ReadyReplicas dip must not
	// revert it to Observing — the phase, condition, and InstrumentedReplicas below are all left as
	// they were minted; only removeCoverageService (above) and setReplicaConfigCondition (above,
	// which is warning-only and never gates Observed) still run on every pass while Observed. The
	// target leaves Observed only via a spec change (a different branch of Reconcile entirely) or the
	// Deployment disappearing (the DeploymentNotFound branch above, which resets Phase to Pending
	// before this would run again).
	//
	// KNOWN, ACCEPTED LIMITATION (the zero-pod edge): because this returns before re-reading
	// ReadyReplicas, a target whose pods ALL die *after* reaching Observed stays Observed /
	// Ready:True with InstrumentedReplicas frozen, indefinitely — the campaign gate would treat it
	// as ready against zero live pods. This is the deliberate cost of not spuriously failing a
	// running campaign on a transient dip, and it is shared with the Injected path (which keys off
	// UpdatedReplicas and likewise never reacts to pods crash-looping post-rollout). Surfacing a
	// permanent zero-pod state honestly is future work; see the plan doc's "Observed is STICKY".
	if target.Status.Phase == basquinv1alpha1.PhaseObserved {
		return nil
	}

	// The template is clean (never injected, or a prior revert already landed): observe readiness.
	// ReadyReplicas, not UpdatedReplicas (design §2.2) — there is no rollout to track for an
	// observe-only target, so readiness is the only honest signal for HOW MANY pods are up.
	//
	// But readiness alone is not enough to mint Observed. ReadyReplicas counts Ready pods of ANY
	// ReplicaSet revision — during a rollout (e.g. right after the revert-before-observe branch above
	// updates the pod template), the default RollingUpdate maxUnavailable=25% rounds down to 0 at low
	// replica counts, so the OLD pod stays Ready and keeps ReadyReplicas at desired until its
	// replacement is Ready too. Minting Observed on that signal would attach "operator did not modify
	// the pod template" to a pod that may still be running the pre-revert (possibly still-injected)
	// template — the exact honesty failure this feature exists to prevent. So Observed additionally
	// requires the rollout to have settled: the Deployment controller has observed the latest
	// generation, and every replica is on the latest ReplicaSet (Replicas == UpdatedReplicas ==
	// desired) as well as Ready.
	ready := deploy.Status.ReadyReplicas
	target.Status.InstrumentedReplicas = ready
	settled := deploy.Status.ObservedGeneration >= deploy.Generation &&
		deploy.Status.UpdatedReplicas >= desired &&
		deploy.Status.Replicas == deploy.Status.UpdatedReplicas

	if settled && ready >= desired && desired > 0 {
		target.Status.Phase = basquinv1alpha1.PhaseObserved
		meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionTrue, Reason: "PreInstrumented",
			Message: fmt.Sprintf(
				"%d/%d replica(s) ready; instrumentation is build-time (operator did not modify the pod template)",
				ready, desired),
		})
		return nil
	}

	// design §2.2a — the pre-ready window: Observed must never be minted on first sight of the
	// Deployment, nor mid-rollout (see settled above). The campaign gate (a separate controller)
	// accepts Observed only, never Observing, so a campaign referencing this target stays Pending
	// rather than launching against zero — or not-yet-clean — pods.
	target.Status.Phase = basquinv1alpha1.PhaseObserving
	reason, msg := "WaitingForReplicas", fmt.Sprintf("%d/%d replica(s) ready", ready, desired)
	if ready >= desired && desired > 0 && !settled {
		reason = "RolloutNotSettled"
		msg = fmt.Sprintf(
			"%d/%d replica(s) ready, but the rollout has not settled yet (updated %d/%d, observedGeneration %d/%d); waiting before observing",
			ready, desired, deploy.Status.UpdatedReplicas, desired, deploy.Status.ObservedGeneration, deploy.Generation)
	}
	meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
		Type: "Ready", Status: metav1.ConditionFalse, Reason: reason,
		Message: msg,
	})
	return nil
}

// revertDeployment restores the target's Deployment to its pre-injection state. A missing Deployment
// is fine — there's nothing to revert.
func (r *BasquinTargetReconciler) revertDeployment(ctx context.Context, target *basquinv1alpha1.BasquinTarget) error {
	var deploy appsv1.Deployment
	depKey := types.NamespacedName{Namespace: target.Namespace, Name: target.Spec.DeploymentRef.Name}
	if err := r.Get(ctx, depKey, &deploy); err != nil {
		return client.IgnoreNotFound(err)
	}
	if deploy.Annotations[annInjectedHash] == "" {
		return nil // not injected by us; nothing to undo
	}
	revertInjection(&deploy)
	return r.Update(ctx, &deploy)
}

// reconcileCoverageService ensures the headless coverage Service exists when spec.coverageService is
// on (and coverage is enabled), or is removed otherwise, and sets status.coverageEndpoint. The
// Service is owner-referenced to the target so it's garbage-collected when the target is deleted.
func (r *BasquinTargetReconciler) reconcileCoverageService(ctx context.Context,
	target *basquinv1alpha1.BasquinTarget, deploy *appsv1.Deployment) error {
	if !target.Spec.CoverageService || !target.Spec.Agents.Coverage.Enabled {
		return r.removeCoverageService(ctx, target)
	}

	desired := desiredCoverageService(target, deploy)
	if len(desired.Spec.Selector) == 0 {
		// A selectorless Service gets no auto-managed Endpoints, so DD-023 coverage would silently
		// resolve to nothing. Only happens if the Deployment uses matchExpressions-only selectors
		// (which a Service selector can't represent) — surface it rather than fail silently.
		log.FromContext(ctx).Info("coverage Service selector is empty; it will back no pods "+
			"(Deployment uses matchExpressions?) and DD-023 coverage will not resolve",
			"service", desired.Name)
	}
	if err := controllerutil.SetControllerReference(target, desired, r.Scheme); err != nil {
		return err
	}
	key := types.NamespacedName{Namespace: target.Namespace, Name: coverageServiceName(target)}
	var existing corev1.Service
	switch err := r.Get(ctx, key, &existing); {
	case apierrors.IsNotFound(err):
		if cerr := r.Create(ctx, desired); cerr != nil {
			return cerr
		}
	case err == nil:
		// Reconcile the mutable fields only; clusterIP (headless) is immutable, leave it.
		existing.Spec.Selector = desired.Spec.Selector
		existing.Spec.Ports = desired.Spec.Ports
		if uerr := r.Update(ctx, &existing); uerr != nil {
			return uerr
		}
	default:
		return err
	}
	target.Status.CoverageEndpoint = coverageEndpoint(target)
	return nil
}

// removeCoverageService deletes the coverage Service (if present) and clears status.coverageEndpoint.
// Called when coverage is not wanted AND when the referenced Deployment disappears, so a dead
// endpoint is never left published.
func (r *BasquinTargetReconciler) removeCoverageService(ctx context.Context,
	target *basquinv1alpha1.BasquinTarget) error {
	key := types.NamespacedName{Namespace: target.Namespace, Name: coverageServiceName(target)}
	var existing corev1.Service
	if err := r.Get(ctx, key, &existing); err == nil {
		if derr := r.Delete(ctx, &existing); derr != nil && !apierrors.IsNotFound(derr) {
			return derr
		}
	} else if !apierrors.IsNotFound(err) {
		return err
	}
	target.Status.CoverageEndpoint = ""
	return nil
}

// targetsForDeployment maps a Deployment event to the BasquinTargets in its namespace that
// reference it, so drift on an instrumented Deployment (or a late-appearing one) re-triggers a
// reconcile. This replaces owner references, which would be wrong here — the operator patches a
// Deployment it does not own, and an owner ref would make Kubernetes garbage-collect that Deployment
// when the target is deleted, when the intent is to revert it.
func (r *BasquinTargetReconciler) targetsForDeployment(ctx context.Context, obj client.Object) []reconcile.Request {
	// Full namespace list + filter. Fine at the namespaced scale this operator runs at; if target
	// count or Deployment churn grows, a field indexer on spec.deploymentRef.name would turn this
	// into a targeted lookup (deferred — see TODO backlog).
	var targets basquinv1alpha1.BasquinTargetList
	if err := r.List(ctx, &targets, client.InNamespace(obj.GetNamespace())); err != nil {
		return nil
	}
	var reqs []reconcile.Request
	for i := range targets.Items {
		if targets.Items[i].Spec.DeploymentRef.Name == obj.GetName() {
			reqs = append(reqs, reconcile.Request{NamespacedName: types.NamespacedName{
				Namespace: targets.Items[i].Namespace, Name: targets.Items[i].Name,
			}})
		}
	}
	return reqs
}

// SetupWithManager wires the controller to watch BasquinTargets and the Deployments they
// reference (mapped back via targetsForDeployment, since the operator sets no owner references —
// see that method for why).
func (r *BasquinTargetReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&basquinv1alpha1.BasquinTarget{}).
		// Owns the coverage Service (owner-ref'd), so an out-of-band edit/delete of it self-heals.
		Owns(&corev1.Service{}).
		Watches(&appsv1.Deployment{}, handler.EnqueueRequestsFromMapFunc(r.targetsForDeployment)).
		Complete(r)
}
