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

package main

import (
	"context"
	"flag"
	"fmt"
	"time"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/clientcmd"
	"sigs.k8s.io/controller-runtime/pkg/client"

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

// instrumentOpts is the flattened flag set for `instrument`; buildTarget turns it into a CR.
type instrumentOpts struct {
	name, namespace, deployment, container, jvmOptsVar string
	coverageIncludes                                   string
	coveragePort                                       int
	coverageService, threadTracker                     bool
	invariantMode                                      string
	latencyMaxMs, heapDeltaMaxKb                       int
}

// buildTarget constructs a ClosureJVMTarget from the options. Pure (no cluster access) so it's
// unit-testable. Coverage is enabled iff coverageIncludes is set (the CRD's CEL rule requires
// includes when coverage is on, so we never enable it without them).
func buildTarget(o instrumentOpts) *closurejvmv1alpha1.ClosureJVMTarget {
	spec := closurejvmv1alpha1.ClosureJVMTargetSpec{
		DeploymentRef:   closurejvmv1alpha1.DeploymentReference{Name: o.deployment},
		Container:       o.container,
		JVMOptsVar:      o.jvmOptsVar, // "" => the CRD defaults it (JAVA_TOOL_OPTIONS)
		CoverageService: o.coverageService,
		Agents:          closurejvmv1alpha1.AgentsSpec{ThreadTracker: &o.threadTracker},
	}
	if o.coverageIncludes != "" {
		port := int32(o.coveragePort)
		if port == 0 {
			port = 6300
		}
		spec.Agents.Coverage = closurejvmv1alpha1.CoverageSpec{Enabled: true, Port: port, Includes: o.coverageIncludes}
	}
	if o.invariantMode != "" || o.latencyMaxMs > 0 || o.heapDeltaMaxKb > 0 {
		spec.Invariants = closurejvmv1alpha1.InvariantsSpec{
			Mode: o.invariantMode, LatencyMaxMs: int32(o.latencyMaxMs), HeapDeltaMaxKb: int32(o.heapDeltaMaxKb)}
	}
	return &closurejvmv1alpha1.ClosureJVMTarget{
		ObjectMeta: metav1.ObjectMeta{Name: o.name, Namespace: o.namespace},
		Spec:       spec,
	}
}

// validateInstrument returns a user-facing error for flag combinations the operator would reject.
func validateInstrument(o instrumentOpts) error {
	if o.deployment == "" {
		return fmt.Errorf("--deployment is required")
	}
	if o.coverageService && o.coverageIncludes == "" {
		return fmt.Errorf("--coverage-service needs coverage enabled; pass --coverage-includes (e.g. 'com.example.*')")
	}
	// Fail fast with a clean message on values the CRD's enums would reject server-side anyway.
	switch o.jvmOptsVar {
	case "", "CATALINA_OPTS", "JAVA_TOOL_OPTIONS":
	default:
		return fmt.Errorf("--jvm-opts-var must be CATALINA_OPTS or JAVA_TOOL_OPTIONS, got %q", o.jvmOptsVar)
	}
	switch o.invariantMode {
	case "", "soft", "hard":
	default:
		return fmt.Errorf("--invariant-mode must be soft or hard, got %q", o.invariantMode)
	}
	return nil
}

func runInstrument(args []string) error {
	fs := flag.NewFlagSet("instrument", flag.ContinueOnError)
	var (
		o       instrumentOpts
		kubeCtx string
		wait    bool
		waitFor time.Duration
	)
	fs.StringVar(&o.deployment, "deployment", "", "Deployment to instrument (required).")
	fs.StringVar(&o.name, "name", "", "ClosureJVMTarget name (default: the deployment name).")
	fs.StringVar(&o.namespace, "namespace", "", "Namespace (default: the kube context's namespace, else 'default').")
	fs.StringVar(&o.namespace, "n", "", "Namespace (shorthand).")
	fs.StringVar(&kubeCtx, "context", "", "Kube context to use (default: current-context).")
	fs.StringVar(&o.container, "container", "", "Container in the pod template (default: the sole container).")
	fs.StringVar(&o.jvmOptsVar, "jvm-opts-var", "", "JVM opts env var: CATALINA_OPTS | JAVA_TOOL_OPTIONS (default: JAVA_TOOL_OPTIONS).")
	fs.StringVar(&o.coverageIncludes, "coverage-includes", "", "Enable JaCoCo coverage with this class-include filter (e.g. 'com.example.*').")
	fs.IntVar(&o.coveragePort, "coverage-port", 6300, "Coverage tcpserver port inside the pod.")
	fs.BoolVar(&o.coverageService, "coverage-service", false, "Create the headless coverage Service (needs --coverage-includes).")
	fs.BoolVar(&o.threadTracker, "thread-tracker", true, "Inject the native JVMTI thread/leak agent.")
	fs.StringVar(&o.invariantMode, "invariant-mode", "", "Invariant mode: soft | hard.")
	fs.IntVar(&o.latencyMaxMs, "latency-max-ms", 0, "Per-iteration latency threshold (ms).")
	fs.IntVar(&o.heapDeltaMaxKb, "heap-delta-max-kb", 0, "Per-iteration heap-delta threshold (KB).")
	fs.BoolVar(&wait, "wait", false, "Wait for the target to reach Injected.")
	fs.DurationVar(&waitFor, "wait-timeout", 3*time.Minute, "How long to wait with --wait.")
	if err := fs.Parse(args); err != nil {
		if err == flag.ErrHelp { // -h/--help: flag already printed usage
			return nil
		}
		return err
	}
	if o.name == "" {
		o.name = o.deployment
	}

	// Load kubeconfig (rest config + default namespace) honoring --context.
	rules := clientcmd.NewDefaultClientConfigLoadingRules()
	overrides := &clientcmd.ConfigOverrides{}
	if kubeCtx != "" {
		overrides.CurrentContext = kubeCtx
	}
	cc := clientcmd.NewNonInteractiveDeferredLoadingClientConfig(rules, overrides)
	if o.namespace == "" {
		if ns, _, err := cc.Namespace(); err == nil && ns != "" {
			o.namespace = ns
		} else {
			o.namespace = "default"
		}
	}
	if err := validateInstrument(o); err != nil {
		return err
	}

	cfg, err := cc.ClientConfig()
	if err != nil {
		return fmt.Errorf("loading kubeconfig: %w", err)
	}
	scheme := runtime.NewScheme()
	if err := closurejvmv1alpha1.AddToScheme(scheme); err != nil {
		return err
	}
	c, err := client.New(cfg, client.Options{Scheme: scheme})
	if err != nil {
		return fmt.Errorf("building client: %w", err)
	}

	ctx := context.Background()
	desired := buildTarget(o)
	if err := applyTarget(ctx, c, desired); err != nil {
		return err
	}
	fmt.Printf("Applied ClosureJVMTarget %s/%s (deployment %q)\n", o.namespace, o.name, o.deployment)

	if !wait {
		fmt.Printf("Check progress: kubectl -n %s get closurejvmtarget %s\n", o.namespace, o.name)
		return nil
	}
	return waitForInjected(ctx, c, types.NamespacedName{Namespace: o.namespace, Name: o.name}, waitFor)
}

// applyTarget creates the target, or updates the spec of an existing one (idempotent re-apply).
func applyTarget(ctx context.Context, c client.Client, desired *closurejvmv1alpha1.ClosureJVMTarget) error {
	var existing closurejvmv1alpha1.ClosureJVMTarget
	key := types.NamespacedName{Namespace: desired.Namespace, Name: desired.Name}
	switch err := c.Get(ctx, key, &existing); {
	case apierrors.IsNotFound(err):
		return c.Create(ctx, desired)
	case err != nil:
		return fmt.Errorf("checking for an existing target: %w", err)
	default:
		existing.Spec = desired.Spec
		return c.Update(ctx, &existing)
	}
}

// waitForInjected polls until the target reaches Injected (printing phase changes), or fails/times out.
func waitForInjected(ctx context.Context, c client.Client, key types.NamespacedName, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	last := ""
	for {
		var t closurejvmv1alpha1.ClosureJVMTarget
		if err := c.Get(ctx, key, &t); err != nil {
			// Don't abort the whole wait on a transient API hiccup — retry until the deadline.
			if time.Now().After(deadline) {
				return fmt.Errorf("reading target status: %w", err)
			}
			time.Sleep(2 * time.Second)
			continue
		}
		// Only trust a terminal phase once the controller has observed THIS spec — otherwise a --wait
		// re-apply that changed the spec could read the pre-update Injected status and return early.
		observed := t.Status.ObservedGeneration >= t.Generation
		phase := string(t.Status.Phase)
		if phase != last && phase != "" {
			fmt.Printf("  phase: %s\n", phase)
			last = phase
		}
		if observed {
			switch t.Status.Phase {
			case closurejvmv1alpha1.PhaseInjected:
				fmt.Printf("Injected ✓  coverageEndpoint=%s\n", orNone(t.Status.CoverageEndpoint))
				return nil
			case closurejvmv1alpha1.PhaseError:
				return fmt.Errorf("target entered Error (see: kubectl -n %s describe closurejvmtarget %s)", key.Namespace, key.Name)
			}
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("timed out after %s waiting for Injected (last phase %q)", timeout, orNone(phase))
		}
		time.Sleep(2 * time.Second)
	}
}

func orNone(s string) string {
	if s == "" {
		return "<none>"
	}
	return s
}
