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
	"sigs.k8s.io/controller-runtime/pkg/log"

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

// ClosureJVMTargetReconciler reconciles a ClosureJVMTarget object.
//
// P1 SCOPE (docs/OPERATOR-DESIGN.md §8): this reconciler ONLY observes and writes status. It reads
// the referenced Deployment to confirm it exists and to report replica counts, and it never patches
// a workload — the zero-mutation-risk first phase. Injection (the Deployment patch, finalizer, and
// revert) lands in P2. The RBAC below reflects that: deployments are read-only here.
type ClosureJVMTargetReconciler struct {
	client.Client
	Scheme *runtime.Scheme
}

//+kubebuilder:rbac:groups=closurejvm.dev,resources=closurejvmtargets,verbs=get;list;watch
//+kubebuilder:rbac:groups=closurejvm.dev,resources=closurejvmtargets/status,verbs=get;update;patch
//+kubebuilder:rbac:groups=closurejvm.dev,resources=closurejvmtargets/finalizers,verbs=update
//+kubebuilder:rbac:groups=apps,resources=deployments,verbs=get;list;watch

// Reconcile observes a ClosureJVMTarget and reflects what it sees into status. No workload mutation
// happens in P1: this is the safe wiring-proof phase.
func (r *ClosureJVMTargetReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	l := log.FromContext(ctx)

	var target closurejvmv1alpha1.ClosureJVMTarget
	if err := r.Get(ctx, req.NamespacedName, &target); err != nil {
		// Not found = deleted; nothing to do (no finalizer to clean up in P1).
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	target.Status.ObservedGeneration = target.Generation
	// P1 never injects, so no pod ever carries the agents yet.
	target.Status.InstrumentedReplicas = 0
	target.Status.Phase = closurejvmv1alpha1.PhasePending

	// Read (never write) the referenced Deployment, in the target's own namespace.
	var deploy appsv1.Deployment
	depKey := types.NamespacedName{Namespace: target.Namespace, Name: target.Spec.DeploymentRef.Name}
	if err := r.Get(ctx, depKey, &deploy); err != nil {
		if apierrors.IsNotFound(err) {
			meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
				Type:   "Ready",
				Status: metav1.ConditionFalse,
				Reason: "DeploymentNotFound",
				Message: fmt.Sprintf("spec.deploymentRef.name %q not found in namespace %s",
					target.Spec.DeploymentRef.Name, target.Namespace),
			})
			l.Info("target references a missing Deployment", "deployment", depKey.Name)
			// P1 doesn't watch Deployments (no owner refs to map back yet — see SetupWithManager),
			// so without this a target created before its Deployment would sit DeploymentNotFound
			// until the informer's default resync (hours). A short requeue re-observes soon after
			// the Deployment appears; it touches nothing, so the zero-mutation boundary holds.
			if uerr := r.Status().Update(ctx, &target); uerr != nil {
				return ctrl.Result{}, uerr
			}
			return ctrl.Result{RequeueAfter: 30 * time.Second}, nil
		}
		return ctrl.Result{}, err
	}

	// The Deployment exists. P1 stops here — observed, not instrumented. The Ready=False/ObserveOnly
	// condition states plainly that this build does not inject; P2 flips it to True once it does.
	replicas := int32(1)
	if deploy.Spec.Replicas != nil {
		replicas = *deploy.Spec.Replicas
	}
	meta.SetStatusCondition(&target.Status.Conditions, metav1.Condition{
		Type:   "Ready",
		Status: metav1.ConditionFalse,
		Reason: "ObserveOnly",
		Message: fmt.Sprintf("P1 operator observes only and does not inject agents yet (injection "+
			"lands in P2); target Deployment has %d replica(s)", replicas),
	})
	l.Info("observed target", "deployment", depKey.Name, "replicas", replicas)
	return ctrl.Result{}, r.Status().Update(ctx, &target)
}

// SetupWithManager sets up the controller with the Manager. P1 only watches ClosureJVMTargets;
// re-observation of a target whose Deployment appears later is handled by the manager's periodic
// resync. P2 will add a Deployment watch (mapping Deployment drift back to the owning target) once
// injection sets owner references that make such a watch meaningful.
func (r *ClosureJVMTargetReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&closurejvmv1alpha1.ClosureJVMTarget{}).
		Complete(r)
}
