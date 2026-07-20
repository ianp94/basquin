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
	"os"
	"path/filepath"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

type runOpts struct {
	name, namespace, target, baseURL string
	iterations                       int
	duration                         string
	grammarFile, corpusDir           string
	classesPath                      string
	dashboard                        bool
	externalPush                     string
}

// buildCampaign constructs a ClosureJVMCampaign from the options + the resolved ConfigMap names/keys.
// Pure (no cluster access) so it's unit-testable.
func buildCampaign(o runOpts, grammarCM, grammarKey, corpusCM string) *closurejvmv1alpha1.ClosureJVMCampaign {
	d := closurejvmv1alpha1.CampaignDriverSpec{
		GrammarConfigMap: grammarCM,
		GrammarKey:       grammarKey,
		CorpusConfigMap:  corpusCM,
		ClassesPath:      o.classesPath, // "" => CRD default
	}
	if o.duration != "" {
		d.Duration = o.duration
	} else {
		d.Iterations = int64(o.iterations)
	}
	dash := closurejvmv1alpha1.CampaignDashboardSpec{Enabled: &o.dashboard, ExternalPush: o.externalPush}
	return &closurejvmv1alpha1.ClosureJVMCampaign{
		ObjectMeta: metav1.ObjectMeta{Name: o.name, Namespace: o.namespace},
		Spec: closurejvmv1alpha1.ClosureJVMCampaignSpec{
			TargetRef: closurejvmv1alpha1.TargetReference{Name: o.target},
			BaseURL:   o.baseURL,
			Driver:    d,
			Dashboard: dash,
		},
	}
}

func validateRun(o runOpts) error {
	if o.target == "" {
		return fmt.Errorf("--target is required")
	}
	if o.baseURL == "" {
		return fmt.Errorf("--base-url is required (the app's in-cluster HTTP entrypoint)")
	}
	if (o.iterations > 0) == (o.duration != "") {
		return fmt.Errorf("set exactly one of --iterations or --duration")
	}
	return nil
}

func runRun(args []string) error {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	var (
		o          runOpts
		kubeCtx    string
		watch      bool
		watchFor   time.Duration
		noDashbard bool
	)
	fs.StringVar(&o.target, "target", "", "ClosureJVMTarget to drive (required; must be Injected).")
	fs.StringVar(&o.baseURL, "base-url", "", "The app's in-cluster HTTP entrypoint (required).")
	fs.StringVar(&o.name, "name", "", "Campaign name (default: <target>-campaign).")
	fs.StringVar(&o.namespace, "namespace", "", "Namespace (default: the kube context's namespace, else 'default').")
	fs.StringVar(&o.namespace, "n", "", "Namespace (shorthand).")
	fs.StringVar(&kubeCtx, "context", "", "Kube context to use (default: current-context).")
	fs.IntVar(&o.iterations, "iterations", 0, "Bound the run by iteration count (set this OR --duration).")
	fs.StringVar(&o.duration, "duration", "", "Bound the run by a Go duration, e.g. 10m (set this OR --iterations).")
	fs.StringVar(&o.grammarFile, "grammar", "", "Path to a grammar file (creates a ConfigMap from it).")
	fs.StringVar(&o.corpusDir, "corpus", "", "Path to a corpus dir (creates a flat ConfigMap from its files).")
	fs.StringVar(&o.classesPath, "classes-path", "", "Where .class files live in the target image (default: the CRD default).")
	fs.BoolVar(&noDashbard, "no-dashboard", false, "Don't create a per-campaign dashboard.")
	fs.StringVar(&o.externalPush, "external-push", "", "Push to an existing dashboard at host:port instead of creating one.")
	fs.BoolVar(&watch, "watch", false, "Tail campaign status until it completes.")
	fs.DurationVar(&watchFor, "watch-timeout", 15*time.Minute, "How long to tail with --watch.")
	if err := fs.Parse(args); err != nil {
		if err == flag.ErrHelp {
			return nil
		}
		return err
	}
	o.dashboard = !noDashbard
	if o.name == "" && o.target != "" {
		o.name = o.target + "-campaign"
	}

	cc := kubeConfig(kubeCtx)
	o.namespace = resolveNamespace(cc, o.namespace)
	if err := validateRun(o); err != nil {
		return err
	}
	c, err := newClient(cc)
	if err != nil {
		return err
	}
	ctx := context.Background()

	// Create the campaign FIRST so the grammar/corpus ConfigMaps can be owner-referenced to it (GC on
	// `kubectl delete campaign`; the owner UID only exists once the campaign is created). If the
	// reconciler launches the driver Job in the brief window before the ConfigMaps land, that's safe by
	// kubelet mount-retry semantics — a pod referencing a not-yet-existent ConfigMap volume stays
	// Pending/FailedMount and is retried automatically; it never terminates, so it can't count toward
	// the Job's backoff or push the campaign to a terminal Failed. (review #30: this is the actual
	// safety mechanism — there is no app-level "ConfigMap exists" gate in the reconciler.)
	grammarCM, grammarKey := "", ""
	corpusCM := ""
	if o.grammarFile != "" {
		grammarCM = o.name + "-grammar"
		grammarKey = filepath.Base(o.grammarFile)
	}
	if o.corpusDir != "" {
		corpusCM = o.name + "-corpus"
	}
	campaign := buildCampaign(o, grammarCM, grammarKey, corpusCM)
	if err := c.Create(ctx, campaign); err != nil {
		if apierrors.IsAlreadyExists(err) {
			return fmt.Errorf("campaign %q already exists in %s (delete it first, or use --name)", o.name, o.namespace)
		}
		return err
	}
	fmt.Printf("Created ClosureJVMCampaign %s/%s (target %q)\n", o.namespace, o.name, o.target)
	owner := campaignOwnerRef(campaign)

	if o.grammarFile != "" {
		if err := createConfigMapFromFile(ctx, c, grammarCM, o.namespace, o.grammarFile, owner); err != nil {
			return fmt.Errorf("creating grammar ConfigMap: %w", err)
		}
		fmt.Printf("  grammar ConfigMap %s (key %s)\n", grammarCM, grammarKey)
	}
	if o.corpusDir != "" {
		n, err := createConfigMapFromDir(ctx, c, corpusCM, o.namespace, o.corpusDir, owner)
		if err != nil {
			return fmt.Errorf("creating corpus ConfigMap: %w", err)
		}
		fmt.Printf("  corpus ConfigMap %s (%d file(s))\n", corpusCM, n)
	}

	if !watch {
		fmt.Printf("Watch progress: kubectl -n %s get closurejvmcampaign %s -w\n", o.namespace, o.name)
		return nil
	}
	return watchCampaign(ctx, c, types.NamespacedName{Namespace: o.namespace, Name: o.name}, watchFor)
}

// campaignOwnerRef builds a controller owner reference to a just-created campaign (has a UID).
func campaignOwnerRef(c *closurejvmv1alpha1.ClosureJVMCampaign) metav1.OwnerReference {
	t := true
	return metav1.OwnerReference{
		APIVersion: closurejvmv1alpha1.GroupVersion.String(),
		Kind:       "ClosureJVMCampaign",
		Name:       c.Name,
		UID:        c.UID,
		Controller: &t,
	}
}

// createConfigMapFromFile creates a one-key ConfigMap (key = the file's basename).
func createConfigMapFromFile(ctx context.Context, c client.Client, name, ns, path string, owner metav1.OwnerReference) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: ns, OwnerReferences: []metav1.OwnerReference{owner}},
		Data:       map[string]string{filepath.Base(path): string(b)},
	}
	return createOrReplaceConfigMap(ctx, c, cm)
}

// createConfigMapFromDir creates a flat ConfigMap keyed by basename from the files directly in dir AND
// in its values/ subdir (the corpus layout — route-seed files at top level, value files under values/),
// mirroring `kubectl create configmap --from-file=dir/ --from-file=dir/values/`. Returns the file count.
func createConfigMapFromDir(ctx context.Context, c client.Client, name, ns, dir string, owner metav1.OwnerReference) (int, error) {
	data, err := readCorpusDir(dir)
	if err != nil {
		return 0, err
	}
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: ns, OwnerReferences: []metav1.OwnerReference{owner}},
		Data:       data,
	}
	return len(data), createOrReplaceConfigMap(ctx, c, cm)
}

// readCorpusDir reads the corpus into a basename-keyed map: files directly in dir AND in its values/
// subdir (route-seed files at top level, value files under values/), non-recursive, mirroring
// `kubectl create configmap --from-file=dir/ --from-file=dir/values/`. Pure (no cluster) for testing.
func readCorpusDir(dir string) (map[string]string, error) {
	if fi, err := os.Stat(dir); err != nil || !fi.IsDir() {
		return nil, fmt.Errorf("--corpus %q is not a readable directory", dir)
	}
	data := map[string]string{}
	for _, d := range []string{dir, filepath.Join(dir, "values")} {
		entries, err := os.ReadDir(d)
		if err != nil {
			if os.IsNotExist(err) {
				continue // no values/ subdir is fine
			}
			return nil, err
		}
		for _, e := range entries {
			if e.IsDir() {
				continue // non-recursive, like --from-file on a dir
			}
			b, err := os.ReadFile(filepath.Join(d, e.Name()))
			if err != nil {
				return nil, err
			}
			if _, dup := data[e.Name()]; dup {
				return nil, fmt.Errorf("corpus basename %q appears in both %s and its values/ subdir; basenames must be unique", e.Name(), dir)
			}
			data[e.Name()] = string(b)
		}
	}
	if len(data) == 0 {
		return nil, fmt.Errorf("no files found under %s", dir)
	}
	return data, nil
}

// createOrReplaceConfigMap creates the ConfigMap, or replaces an existing one's data (idempotent).
func createOrReplaceConfigMap(ctx context.Context, c client.Client, cm *corev1.ConfigMap) error {
	var existing corev1.ConfigMap
	key := types.NamespacedName{Namespace: cm.Namespace, Name: cm.Name}
	switch err := c.Get(ctx, key, &existing); {
	case apierrors.IsNotFound(err):
		return c.Create(ctx, cm)
	case err != nil:
		return err
	default:
		existing.Data = cm.Data
		// Re-own it: replacing a stale/pre-existing same-named ConfigMap must re-point it at THIS
		// campaign, or it keeps its old (possibly deleted) owner and gets orphan-GC'd out from under
		// the driver Job (review #30).
		existing.OwnerReferences = cm.OwnerReferences
		return c.Update(ctx, &existing)
	}
}

// watchCampaign tails the campaign to a terminal phase (gated on observedGeneration), printing phase
// changes and the final coverage/findings/dashboard.
func watchCampaign(ctx context.Context, c client.Client, key types.NamespacedName, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	last := ""
	for {
		var cp closurejvmv1alpha1.ClosureJVMCampaign
		if err := c.Get(ctx, key, &cp); err != nil {
			if time.Now().After(deadline) {
				return fmt.Errorf("reading campaign status: %w", err)
			}
			time.Sleep(3 * time.Second)
			continue
		}
		phase := string(cp.Status.Phase)
		if phase != last && phase != "" {
			fmt.Printf("  phase: %s\n", phase)
			last = phase
		}
		if cp.Status.ObservedGeneration >= cp.Generation {
			switch cp.Status.Phase {
			case closurejvmv1alpha1.CampaignCompleted:
				fmt.Printf("Completed ✓  coverage=%s%%  findings=%d  dashboard=%s\n",
					orNone(cp.Status.CoveragePct), cp.Status.Findings, orNone(cp.Status.DashboardURL))
				return nil
			case closurejvmv1alpha1.CampaignFailed:
				return fmt.Errorf("campaign Failed (see: kubectl -n %s describe closurejvmcampaign %s)", key.Namespace, key.Name)
			}
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("timed out after %s tailing the campaign (last phase %q)", timeout, orNone(phase))
		}
		time.Sleep(3 * time.Second)
	}
}
