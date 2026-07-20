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
	"fmt"
	"strconv"
	"strings"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

// P5a (docs/CAMPAIGN-DESIGN.md, DD-025). The driver Job runs the coverage-guided runner against an
// instrumented target: an initContainer copies the app's .class files out of the TARGET's own image
// (so they always match what's running, §7b) into a shared volume, and the runner container reads
// coverage from the target's headless coverage Service (P3) and writes its end-of-run summary to the
// termination message, which the operator reads back for status (§7a — terminationMessage is used
// for the first cut; it's size-capped at ~4 KiB, ample for the one-line summary).

const (
	defaultRunnerImage = "closurejvm/runner:latest"

	campaignClassesVol = "closurejvm-classes"
	campaignClassesDir = "/closurejvm-classes"
	campaignGrammarVol = "closurejvm-grammar"
	campaignGrammarDir = "/closurejvm-grammar"
	campaignGrammarKey = "grammar" // the grammar is projected into the volume under this fixed filename

	campaignCorpusVol = "closurejvm-corpus"
	campaignCorpusDir = "/closurejvm-corpus"
)

func driverJobName(c *closurejvmv1alpha1.ClosureJVMCampaign) string { return c.Name + "-driver" }

// buildDriverJob builds the coverage-guided driver Job. appImage is the target's app-container image
// (source of the .class files); coverageEndpoint is the target's status.coverageEndpoint;
// dashboardPush is the resolved dashboard host:port to push status/findings to ("" = don't push).
func buildDriverJob(c *closurejvmv1alpha1.ClosureJVMCampaign, appImage, coverageEndpoint, runnerImage, dashboardPush string) *batchv1.Job {
	d := &c.Spec.Driver
	load := c.Spec.Mode == "load"

	props := []string{
		"-Dexamples.http.baseUrl=" + c.Spec.BaseURL,
		"-Dclosurejvm.summary.out=/dev/termination-log", // operator reads this back as the pod's termination message
		"-Dclosurejvm.status=true",
	}
	if load {
		// Load/soak mode (DD-026): replay the corpus at volume; no coverage sampling / classes needed.
		conc := d.Concurrency
		if conc == 0 {
			conc = 10
		}
		props = append(props, "-Dclosurejvm.mode=load", fmt.Sprintf("-Dclosurejvm.concurrency=%d", conc))
		if d.Warmup != "" {
			props = append(props, "-Dclosurejvm.warmup="+d.Warmup)
		}
	} else {
		props = append(props,
			"-Dclosurejvm.coverage.jacoco="+coverageEndpoint,
			"-Dclosurejvm.coverage.classes="+campaignClassesDir)
	}
	if d.Duration != "" {
		props = append(props, "-Dclosurejvm.run.duration="+d.Duration)
	}
	if d.Invariants.Mode != "" {
		props = append(props, "-Dclosurejvm.invariant.mode="+d.Invariants.Mode)
	}
	if d.Invariants.LatencyMaxMs > 0 {
		props = append(props, fmt.Sprintf("-Dclosurejvm.invariant.latency.maxMs=%d", d.Invariants.LatencyMaxMs))
	}
	if d.Invariants.HeapDeltaMaxKb > 0 {
		props = append(props, fmt.Sprintf("-Dclosurejvm.invariant.heapDelta.maxKb=%d", d.Invariants.HeapDeltaMaxKb))
	}
	// Push status/findings to the resolved dashboard (per-campaign or external; "" when disabled).
	// The id groups this campaign's pushes under /api/campaign/<name>/… on the dashboard.
	if dashboardPush != "" {
		props = append(props,
			"-Dclosurejvm.dashboard.push="+dashboardPush,
			"-Dclosurejvm.dashboard.id="+c.Name)
	}

	// The shared classes volume + extract/verify initContainers are for JaCoCo coverage — explore only.
	var volumes []corev1.Volume
	var mounts []corev1.VolumeMount
	if !load {
		volumes = append(volumes, corev1.Volume{Name: campaignClassesVol,
			VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}})
		mounts = append(mounts, corev1.VolumeMount{Name: campaignClassesVol, MountPath: campaignClassesDir})
	}
	if d.GrammarConfigMap != "" {
		// d.GrammarKey is resolved to a concrete key by the reconciler before this is called (the
		// ConfigMap's sole key when the user didn't name one). Always project THAT key to the fixed
		// filename and point the flag at it — projecting under original names would leave the flag
		// pointing at a directory and the grammar would silently never load (review #2).
		volumes = append(volumes, corev1.Volume{Name: campaignGrammarVol, VolumeSource: corev1.VolumeSource{
			ConfigMap: &corev1.ConfigMapVolumeSource{
				LocalObjectReference: corev1.LocalObjectReference{Name: d.GrammarConfigMap},
				Items:                []corev1.KeyToPath{{Key: d.GrammarKey, Path: campaignGrammarKey}},
			}}})
		mounts = append(mounts, corev1.VolumeMount{Name: campaignGrammarVol, MountPath: campaignGrammarDir})
		props = append(props, "-Dclosurejvm.grammar="+campaignGrammarDir+"/"+campaignGrammarKey)
	}
	if d.CorpusConfigMap != "" {
		// Flat corpus mount: every ConfigMap key becomes a file under campaignCorpusDir (keys are
		// valid filenames — no key projection needed). loadSeeds walks this dir for /-prefixed route
		// seeds, and the grammar's @-value-file refs fall back to <corpusDir>/<basename> (DD-018).
		volumes = append(volumes, corev1.Volume{Name: campaignCorpusVol, VolumeSource: corev1.VolumeSource{
			ConfigMap: &corev1.ConfigMapVolumeSource{
				LocalObjectReference: corev1.LocalObjectReference{Name: d.CorpusConfigMap}}}})
		mounts = append(mounts, corev1.VolumeMount{Name: campaignCorpusVol, MountPath: campaignCorpusDir})
		props = append(props, "-Dclosurejvm.corpusDir="+campaignCorpusDir)
	}

	var args []string
	if d.Iterations > 0 {
		args = []string{strconv.FormatInt(d.Iterations, 10)}
	}
	backoff := int32(2)
	classesPath := d.ClassesPath
	if classesPath == "" {
		classesPath = "/usr/local/tomcat/webapps/ROOT/WEB-INF/classes"
	}

	// Coverage initContainers (extract + verify the app's .class files) are explore-only — load mode
	// doesn't sample coverage.
	var initContainers []corev1.Container
	if !load {
		initContainers = []corev1.Container{{
			Name:            "extract-classes",
			Image:           appImage,
			ImagePullPolicy: corev1.PullIfNotPresent,
			// exec cp directly, NOT `sh -c "cp ... " + classesPath` — classesPath is a free-form spec
			// field, and interpolating it into a shell string would let a campaign author inject
			// commands into the initContainer (review #1). `--` so a classesPath starting with `-` is
			// treated as a path, not a cp flag.
			Command:      []string{"cp", "-r", "--", classesPath + "/.", campaignClassesDir + "/"},
			VolumeMounts: []corev1.VolumeMount{{Name: campaignClassesVol, MountPath: campaignClassesDir}},
		}, {
			// Fail loud if the extract produced no .class files, instead of letting the driver run and
			// report a silent coveragePct=0 (the common cause is a war-only target image). Only the
			// fixed dest dir is referenced (no user input), so this shell string is injection-safe.
			Name:            "verify-classes",
			Image:           runnerImage,
			ImagePullPolicy: corev1.PullIfNotPresent,
			Command: []string{"sh", "-c",
				"if [ -z \"$(find " + campaignClassesDir + " -name '*.class' -print -quit 2>/dev/null)\" ]; then " +
					"msg='no .class files extracted into " + campaignClassesDir +
					" — check spec.driver.classesPath (war-only images expose classes only at runtime, not in the image)'; " +
					"echo \"closurejvm: $msg\"; printf '%s' \"$msg\" > /dev/termination-log; exit 1; fi"},
			TerminationMessagePath:   "/dev/termination-log",
			TerminationMessagePolicy: corev1.TerminationMessageReadFile,
			VolumeMounts:             []corev1.VolumeMount{{Name: campaignClassesVol, MountPath: campaignClassesDir}},
		}}
	}

	return &batchv1.Job{
		ObjectMeta: metav1.ObjectMeta{
			Name:      driverJobName(c),
			Namespace: c.Namespace,
			Labels: map[string]string{
				"app.kubernetes.io/managed-by": "closurejvm-operator",
				"closurejvm.dev/campaign":      c.Name,
			},
		},
		Spec: batchv1.JobSpec{
			BackoffLimit: &backoff,
			Template: corev1.PodTemplateSpec{
				// component=driver distinguishes these from the per-campaign dashboard pod, which shares
				// the closurejvm.dev/campaign label — so the rerun pod-cleanup can target driver pods only.
				ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{
					"closurejvm.dev/campaign":     c.Name,
					"app.kubernetes.io/component": "driver",
				}},
				Spec: corev1.PodSpec{
					RestartPolicy:  corev1.RestartPolicyNever,
					Volumes:        volumes,
					InitContainers: initContainers,
					Containers: []corev1.Container{{
						Name:                     "driver",
						Image:                    runnerImage,
						ImagePullPolicy:          corev1.PullIfNotPresent,
						Env:                      []corev1.EnvVar{{Name: "JAVA_TOOL_OPTIONS", Value: strings.Join(props, " ")}},
						Args:                     args,
						VolumeMounts:             mounts,
						TerminationMessagePath:   "/dev/termination-log",
						TerminationMessagePolicy: corev1.TerminationMessageReadFile,
					}},
				},
			},
		},
	}
}
