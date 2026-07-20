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
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"

	closurejvmv1alpha1 "github.com/ianp94/closureJVM/operator/api/v1alpha1"
)

// P2 injection (docs/OPERATOR-DESIGN.md §4, DD-024). The operator instruments an unmodified app
// image at deploy time by patching its Deployment's pod template:
//   - an initContainer copies the agents from a versioned image into a shared emptyDir,
//   - the emptyDir is mounted into the app container,
//   - the app container's jvmOptsVar (CATALINA_OPTS / JAVA_TOOL_OPTIONS) is APPENDED with the agent
//     flags — never replaced, so the app's own heap/GC flags survive,
//   - the coverage port is exposed when coverage is enabled.
// All of this is reversible: the original jvmOptsVar is stashed in an annotation so deleting the CR
// restores the Deployment exactly.

const (
	// Finalizer that guarantees the Deployment is reverted before a target is deleted.
	finalizerName = "closurejvm.dev/revert"

	// Annotation on the Deployment recording the spec hash we last injected, so a steady target is a
	// no-op on every resync and re-injection only happens when the spec actually changes.
	annInjectedHash = "closurejvm.dev/injected"
	// Annotation stashing the app container's original jvmOptsVar value, so revert is exact. Present
	// with value "" means the var was originally unset/empty (revert then removes it).
	annOriginalOpts = "closurejvm.dev/original-jvmopts"
	// Records which env var name we appended to, so revert targets the right one even if the spec's
	// jvmOptsVar changed between inject and revert.
	annOptsVar = "closurejvm.dev/jvmopts-var"

	// Names for the injected initContainer / shared volume, and where the agents are mounted.
	agentsInitName   = "closurejvm-agents"
	agentsVolumeName = "closurejvm-agents"
	agentsMountPath  = "/closurejvm"
	// Container port names are IANA_SVC_NAME: max 15 chars. "closurejvm-jacoco" (17) is too long.
	coveragePortName = "cjvm-jacoco"
)

// defaultAgentsImage is used when the operator is not configured with one. Overridable via the
// reconciler's AgentsImage field (set from a flag/env in main.go).
const defaultAgentsImage = "closurejvm/agents:latest"

// resolveContainer picks the app container to instrument: spec.container if set, else the sole
// container. Returns its index, or an error the caller surfaces as a status condition.
func resolveContainer(spec *closurejvmv1alpha1.ClosureJVMTargetSpec, tmpl *corev1.PodSpec) (int, error) {
	if spec.Container != "" {
		for i := range tmpl.Containers {
			if tmpl.Containers[i].Name == spec.Container {
				return i, nil
			}
		}
		return -1, fmt.Errorf("spec.container %q not found in the pod template", spec.Container)
	}
	if len(tmpl.Containers) == 1 {
		return 0, nil
	}
	return -1, fmt.Errorf("pod template has %d containers; spec.container must name one", len(tmpl.Containers))
}

// jvmOptsVarName returns the env var the JVM reads flags from, defaulting per the CRD default.
func jvmOptsVarName(spec *closurejvmv1alpha1.ClosureJVMTargetSpec) string {
	if spec.JVMOptsVar != "" {
		return spec.JVMOptsVar
	}
	return "JAVA_TOOL_OPTIONS"
}

// buildAgentArgs assembles the JVM flags appended to jvmOptsVar. Order is deliberate: the native
// JVMTI agent first, then the Java agent (+ bootclasspath so its classes are visible to
// bootstrap-loaded code), then the JaCoCo coverage agent, then the -D config. The valve is NOT a
// JVM flag (it needs a Tomcat context.xml Valve entry) and is handled separately (deferred; see the
// backlog) — enabling agents.valve alone does not add anything here.
func buildAgentArgs(spec *closurejvmv1alpha1.ClosureJVMTargetSpec) string {
	var args []string
	if spec.Agents.ThreadTracker {
		args = append(args, "-agentpath:"+agentsMountPath+"/libclosurejvmti.so")
	}
	// The Java agent carries the invariant oracle; inject it whenever any agent/invariant is wanted.
	args = append(args,
		"-javaagent:"+agentsMountPath+"/closurejvm-agent.jar",
		"-Xbootclasspath/a:"+agentsMountPath+"/closurejvm-agent.jar")

	if spec.Agents.Coverage.Enabled {
		port := spec.Agents.Coverage.Port
		if port == 0 {
			port = 6300
		}
		args = append(args, fmt.Sprintf(
			"-javaagent:%s/jacocoagent.jar=output=tcpserver,address=0.0.0.0,port=%d,includes=%s",
			agentsMountPath, port, spec.Agents.Coverage.Includes))
	}

	if spec.Invariants.Mode != "" {
		args = append(args, "-Dclosurejvm.invariant.mode="+spec.Invariants.Mode)
	}
	if spec.Invariants.LatencyMaxMs > 0 {
		args = append(args, fmt.Sprintf("-Dclosurejvm.invariant.latency.maxMs=%d", spec.Invariants.LatencyMaxMs))
	}
	if spec.Invariants.HeapDeltaMaxKb > 0 {
		args = append(args, fmt.Sprintf("-Dclosurejvm.invariant.heapDelta.maxKb=%d", spec.Invariants.HeapDeltaMaxKb))
	}
	if spec.DashboardPush != "" {
		args = append(args, "-Dclosurejvm.dashboard.push="+spec.DashboardPush)
	}
	return strings.Join(args, " ")
}

// specHash is a stable fingerprint of the parts of the spec that affect the injected patch. Injecting
// is a no-op while this matches the Deployment's annInjectedHash annotation.
func specHash(spec *closurejvmv1alpha1.ClosureJVMTargetSpec, agentsImage string) string {
	h := sha256.New()
	fmt.Fprintf(h, "img=%s;var=%s;args=%s;covPort=%d;covEnabled=%t",
		agentsImage, jvmOptsVarName(spec), buildAgentArgs(spec),
		spec.Agents.Coverage.Port, spec.Agents.Coverage.Enabled)
	return hex.EncodeToString(h.Sum(nil))[:16]
}

// injectionApplied reports whether the Deployment already carries this exact injection.
func injectionApplied(deploy *appsv1.Deployment, wantHash string) bool {
	return deploy.Annotations[annInjectedHash] == wantHash
}

// applyInjection mutates deploy in place to carry the agents for the given spec. It is computed from
// the container's ORIGINAL jvmOptsVar (stashed in an annotation on first inject, reused thereafter),
// so re-reconciling never double-appends. Returns an error if the target container can't be resolved.
func applyInjection(deploy *appsv1.Deployment, spec *closurejvmv1alpha1.ClosureJVMTargetSpec, agentsImage string) error {
	tmpl := &deploy.Spec.Template.Spec
	ci, err := resolveContainer(spec, tmpl)
	if err != nil {
		return err
	}
	if deploy.Annotations == nil {
		deploy.Annotations = map[string]string{}
	}
	varName := jvmOptsVarName(spec)

	// Original opts: from the stash if we've injected before, else the container's current value.
	original, stashed := deploy.Annotations[annOriginalOpts]
	if !stashed {
		original = envValue(tmpl.Containers[ci].Env, varName)
	}

	// initContainer: copy the agents into the shared volume.
	setInitContainer(tmpl, corev1.Container{
		Name:         agentsInitName,
		Image:        agentsImage,
		Command:      []string{"sh", "-c", "cp -r /agents/. " + agentsMountPath + "/"},
		VolumeMounts: []corev1.VolumeMount{{Name: agentsVolumeName, MountPath: agentsMountPath}},
	})
	// Shared emptyDir volume + mount into the app container.
	setVolume(tmpl, corev1.Volume{
		Name:         agentsVolumeName,
		VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}},
	})
	setVolumeMount(&tmpl.Containers[ci], corev1.VolumeMount{Name: agentsVolumeName, MountPath: agentsMountPath})

	// Append the agent flags to the original jvmOptsVar (never replace).
	appended := buildAgentArgs(spec)
	if original != "" {
		appended = original + " " + appended
	}
	setEnv(&tmpl.Containers[ci], varName, appended)

	// Expose the coverage port when enabled.
	if spec.Agents.Coverage.Enabled {
		port := spec.Agents.Coverage.Port
		if port == 0 {
			port = 6300
		}
		setContainerPort(&tmpl.Containers[ci], corev1.ContainerPort{
			Name:          coveragePortName,
			ContainerPort: port,
		})
	}

	deploy.Annotations[annInjectedHash] = specHash(spec, agentsImage)
	deploy.Annotations[annOriginalOpts] = original
	deploy.Annotations[annOptsVar] = varName
	return nil
}

// revertInjection mutates deploy in place back to its pre-injection state: removes the initContainer,
// volume, volume mount, coverage port, and restores (or clears) the original jvmOptsVar. Safe to call
// on a Deployment that was never injected (it just finds nothing to remove).
func revertInjection(deploy *appsv1.Deployment) {
	tmpl := &deploy.Spec.Template.Spec
	removeInitContainer(tmpl, agentsInitName)
	removeVolume(tmpl, agentsVolumeName)

	varName := deploy.Annotations[annOptsVar]
	original, hadStash := deploy.Annotations[annOriginalOpts]
	for i := range tmpl.Containers {
		removeVolumeMount(&tmpl.Containers[i], agentsVolumeName)
		removeContainerPort(&tmpl.Containers[i], coveragePortName)
		if varName != "" {
			if hadStash && original != "" {
				setEnv(&tmpl.Containers[i], varName, original)
			} else {
				removeEnv(&tmpl.Containers[i], varName)
			}
		}
	}
	delete(deploy.Annotations, annInjectedHash)
	delete(deploy.Annotations, annOriginalOpts)
	delete(deploy.Annotations, annOptsVar)
}

// --- small pod-spec helpers (idempotent upserts / removals) -------------------------------------

func envValue(env []corev1.EnvVar, name string) string {
	for _, e := range env {
		if e.Name == name {
			return e.Value
		}
	}
	return ""
}

func setEnv(c *corev1.Container, name, value string) {
	for i := range c.Env {
		if c.Env[i].Name == name {
			c.Env[i].Value = value
			c.Env[i].ValueFrom = nil
			return
		}
	}
	c.Env = append(c.Env, corev1.EnvVar{Name: name, Value: value})
}

func removeEnv(c *corev1.Container, name string) {
	out := c.Env[:0]
	for _, e := range c.Env {
		if e.Name != name {
			out = append(out, e)
		}
	}
	c.Env = out
}

func setInitContainer(tmpl *corev1.PodSpec, ic corev1.Container) {
	for i := range tmpl.InitContainers {
		if tmpl.InitContainers[i].Name == ic.Name {
			tmpl.InitContainers[i] = ic
			return
		}
	}
	tmpl.InitContainers = append(tmpl.InitContainers, ic)
}

func removeInitContainer(tmpl *corev1.PodSpec, name string) {
	out := tmpl.InitContainers[:0]
	for _, c := range tmpl.InitContainers {
		if c.Name != name {
			out = append(out, c)
		}
	}
	tmpl.InitContainers = out
}

func setVolume(tmpl *corev1.PodSpec, v corev1.Volume) {
	for i := range tmpl.Volumes {
		if tmpl.Volumes[i].Name == v.Name {
			tmpl.Volumes[i] = v
			return
		}
	}
	tmpl.Volumes = append(tmpl.Volumes, v)
}

func removeVolume(tmpl *corev1.PodSpec, name string) {
	out := tmpl.Volumes[:0]
	for _, v := range tmpl.Volumes {
		if v.Name != name {
			out = append(out, v)
		}
	}
	tmpl.Volumes = out
}

func setVolumeMount(c *corev1.Container, vm corev1.VolumeMount) {
	for i := range c.VolumeMounts {
		if c.VolumeMounts[i].Name == vm.Name {
			c.VolumeMounts[i] = vm
			return
		}
	}
	c.VolumeMounts = append(c.VolumeMounts, vm)
}

func removeVolumeMount(c *corev1.Container, name string) {
	out := c.VolumeMounts[:0]
	for _, vm := range c.VolumeMounts {
		if vm.Name != name {
			out = append(out, vm)
		}
	}
	c.VolumeMounts = out
}

func setContainerPort(c *corev1.Container, p corev1.ContainerPort) {
	for i := range c.Ports {
		if c.Ports[i].Name == p.Name {
			c.Ports[i] = p
			return
		}
	}
	c.Ports = append(c.Ports, p)
}

func removeContainerPort(c *corev1.Container, name string) {
	out := c.Ports[:0]
	for _, p := range c.Ports {
		if p.Name != name {
			out = append(out, p)
		}
	}
	c.Ports = out
}
