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

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

// ClosureJVMTargetReconciler reconciles a ClosureJVMTarget object.
//
// P2 SCOPE (docs/OPERATOR-DESIGN.md §4, DD-024): the reconciler now INJECTS. It patches the
// referenced Deployment's pod template to carry the agents (initContainer + shared emptyDir +
// appended jvmOptsVar + coverage port), idempotently (a spec-hash annotation makes a steady target a
// no-op), and it holds a finalizer so deleting the target reverts the Deployment to exactly its
// pre-injection state. The injection/revert mechanics live in injection.go.
type ClosureJVMTargetReconciler struct {
	client.Client
	Scheme *runtime.Scheme
	// AgentsImage is the image the injected initContainer copies the agents from. Empty uses
	// defaultAgentsImage. Set from a flag/env in main.go.
	AgentsImage string
}

//+kubebuilder:rbac:groups=closurejvm.dev,resources=closurejvmtargets,verbs=get;list;watch
//+kubebuilder:rbac:groups=closurejvm.dev,resources=closurejvmtargets/status,verbs=get;update;patch
//+kubebuilder:rbac:groups=closurejvm.dev,resources=closurejvmtargets/finalizers,verbs=update
//+kubebuilder:rbac:groups=apps,resources=deployments,verbs=get;list;watch;update;patch

// Reconcile drives the referenced Deployment toward the target's desired instrumentation, and
// reverts it on target deletion.
func (r *ClosureJVMTargetReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	l := log.FromContext(ctx)

	var target closurejvmv1alpha1.ClosureJVMTarget
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
			target.Status.Phase = closurejvmv1alpha1.PhasePending
			target.Status.InstrumentedReplicas = 0
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

	// --- inject if drifted ----------------------------------------------------------------------
	wantHash := specHash(&target.Spec, agentsImage)
	if !injectionApplied(&deploy, wantHash) {
		// Recompute from the original every time (applyInjection reads the stashed original), so a
		// spec change re-derives cleanly rather than layering onto an already-injected value.
		if err := applyInjection(&deploy, &target.Spec, agentsImage); err != nil {
			// A misconfigured container reference is the user's to fix; surface it, don't thrash.
			target.Status.Phase = closurejvmv1alpha1.PhaseError
			meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
				Type: "Ready", Status: metav1.ConditionFalse, Reason: "ContainerNotResolved",
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

	// --- status ---------------------------------------------------------------------------------
	desired := int32(1)
	if deploy.Spec.Replicas != nil {
		desired = *deploy.Spec.Replicas
	}
	// UpdatedReplicas counts pods already on the latest (now injected) template.
	instrumented := deploy.Status.UpdatedReplicas
	target.Status.InstrumentedReplicas = instrumented
	if instrumented >= desired && desired > 0 {
		target.Status.Phase = closurejvmv1alpha1.PhaseInjected
		meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionTrue, Reason: "Injected",
			Message: fmt.Sprintf("all %d replica(s) instrumented", desired),
		})
	} else {
		target.Status.Phase = closurejvmv1alpha1.PhaseInjecting
		meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionFalse, Reason: "RollingOut",
			Message: fmt.Sprintf("%d/%d replica(s) instrumented", instrumented, desired),
		})
	}
	if err := r.Status().Update(ctx, &target); err != nil {
		return ctrl.Result{}, err
	}
	// While a rollout is in flight, poll until instrumented replicas catch up (the Deployment watch
	// also nudges us, but its status subresource updates don't always route through our predicate).
	if target.Status.Phase == closurejvmv1alpha1.PhaseInjecting {
		return ctrl.Result{RequeueAfter: 5 * time.Second}, nil
	}
	return ctrl.Result{}, nil
}

// revertDeployment restores the target's Deployment to its pre-injection state. A missing Deployment
// is fine — there's nothing to revert.
func (r *ClosureJVMTargetReconciler) revertDeployment(ctx context.Context, target *closurejvmv1alpha1.ClosureJVMTarget) error {
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

// targetsForDeployment maps a Deployment event to the ClosureJVMTargets in its namespace that
// reference it, so drift on an instrumented Deployment (or a late-appearing one) re-triggers a
// reconcile. This replaces owner references, which would be wrong here — the operator patches a
// Deployment it does not own, and an owner ref would make Kubernetes garbage-collect that Deployment
// when the target is deleted, when the intent is to revert it.
func (r *ClosureJVMTargetReconciler) targetsForDeployment(ctx context.Context, obj client.Object) []reconcile.Request {
	var targets closurejvmv1alpha1.ClosureJVMTargetList
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

// SetupWithManager wires the controller to watch ClosureJVMTargets and the Deployments they
// reference (mapped back via targetsForDeployment, since the operator sets no owner references —
// see that method for why).
func (r *ClosureJVMTargetReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&closurejvmv1alpha1.ClosureJVMTarget{}).
		Watches(&appsv1.Deployment{}, handler.EnqueueRequestsFromMapFunc(r.targetsForDeployment)).
		Complete(r)
}
