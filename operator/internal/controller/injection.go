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

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
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
	finalizerName = "basquin.dev/revert"

	// Annotation on the Deployment recording the spec hash we last injected, so a steady target is a
	// no-op on every resync and re-injection only happens when the spec actually changes.
	annInjectedHash = "basquin.dev/injected"
	// Annotation stashing the app container's original jvmOptsVar value, so revert is exact. Present
	// with value "" means the var was originally unset/empty (revert then removes it).
	annOriginalOpts = "basquin.dev/original-jvmopts"
	// Records which env var name we appended to, so revert targets the right one even if the spec's
	// jvmOptsVar changed between inject and revert.
	annOptsVar = "basquin.dev/jvmopts-var"
	// Records WHICH container we injected into, so revert restores the env var on exactly that
	// container — never an unrelated sidecar that happens to set the same (generic) var name.
	annOptsContainer = "basquin.dev/jvmopts-container"

	// Names for the injected initContainer / shared volume, and where the agents are mounted.
	agentsInitName   = "basquin-agents"
	agentsVolumeName = "basquin-agents"
	agentsMountPath  = "/basquin"
	// Container port names are IANA_SVC_NAME: max 15 chars; "basquin-jacoco" is 14, so it fits.
	coveragePortName = "basquin-jacoco"
)

// defaultAgentsImage is used when the operator is not configured with one. Overridable via the
// reconciler's AgentsImage field (set from a flag/env in main.go).
const defaultAgentsImage = "basquin/agents:latest"

// resolveContainer picks the app container to instrument: spec.container if set, else the sole
// container. Returns its index, or an error the caller surfaces as a status condition.
func resolveContainer(spec *basquinv1alpha1.BasquinTargetSpec, tmpl *corev1.PodSpec) (int, error) {
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
func jvmOptsVarName(spec *basquinv1alpha1.BasquinTargetSpec) string {
	if spec.JVMOptsVar != "" {
		return spec.JVMOptsVar
	}
	return "JAVA_TOOL_OPTIONS"
}

// buildAgentArgs assembles the JVM flags appended to jvmOptsVar. Order is deliberate: the native
// JVMTI agent first, then the Java agent (+ bootclasspath so its classes are visible to
// bootstrap-loaded code), then the JaCoCo coverage agent, then the -D config. The Tomcat valve is
// not mounted by the operator; instead the injected -javaagent installs the server-side request
// boundary itself via bytecode (-Dbasquin.boundary=agent, see agent.Agent.premain /
// RequestBoundary), so the operator path gets the availability oracle on any Tomcat image with no
// context.xml / lib surgery.
func buildAgentArgs(spec *basquinv1alpha1.BasquinTargetSpec) string {
	var args []string
	if spec.Agents.ThreadTracker == nil || *spec.Agents.ThreadTracker { // nil = default (on)
		args = append(args, "-agentpath:"+agentsMountPath+"/libbasquinjvmti.so")
	}
	// The Java agent carries the invariant oracle; inject it whenever any agent/invariant is wanted.
	args = append(args,
		"-javaagent:"+agentsMountPath+"/basquin-agent.jar",
		"-Xbootclasspath/a:"+agentsMountPath+"/basquin-agent.jar")
	// Turn on the agent-installed server-side request boundary (RequestBoundary via ByteBuddy in
	// premain). Default-off in the agent, so the bench path (valve + agent) stays single-boundary; the
	// operator mounts no valve, so it opts in here. This is what gives the operator path its server-side
	// heap/thread/latency oracle + DD-029 /__basquin control surface.
	args = append(args, "-Dbasquin.boundary=agent")

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
		args = append(args, "-Dbasquin.invariant.mode="+spec.Invariants.Mode)
	}
	if spec.Invariants.LatencyMaxMs > 0 {
		args = append(args, fmt.Sprintf("-Dbasquin.invariant.latency.maxMs=%d", spec.Invariants.LatencyMaxMs))
	}
	if spec.Invariants.HeapDeltaMaxKb > 0 {
		args = append(args, fmt.Sprintf("-Dbasquin.invariant.heapDelta.maxKb=%d", spec.Invariants.HeapDeltaMaxKb))
	}
	if spec.DashboardPush != "" {
		args = append(args, "-Dbasquin.dashboard.push="+spec.DashboardPush)
	}
	return strings.Join(args, " ")
}

// specHash is a stable fingerprint of the parts of the spec that affect the injected patch. Injecting
// is a no-op while this matches the Deployment's annInjectedHash annotation. spec.Container is
// included: retargeting from one container to another with nothing else changed must still re-derive.
func specHash(spec *basquinv1alpha1.BasquinTargetSpec, agentsImage string) string {
	h := sha256.New()
	fmt.Fprintf(h, "img=%s;container=%s;var=%s;args=%s;covPort=%d;covEnabled=%t",
		agentsImage, spec.Container, jvmOptsVarName(spec), buildAgentArgs(spec),
		spec.Agents.Coverage.Port, spec.Agents.Coverage.Enabled)
	return hex.EncodeToString(h.Sum(nil))[:16]
}

// injectionApplied reports whether the Deployment already carries this exact injection. Beyond the
// spec-hash annotation, it verifies the actual injected content is still in place — the initContainer,
// the agent flags in the target container's jvmOptsVar, and the shared agents volume mount — so
// out-of-band content drift (a human `kubectl edit`, another webhook stripping the fields) is re-healed
// rather than reported as steady-state Injected forever. Checking only the annotation + initContainer
// would miss the worst drift: the env flags being stripped, which silently stops the agents loading
// while everything else still looks injected (P2 review).
func injectionApplied(deploy *appsv1.Deployment, spec *basquinv1alpha1.BasquinTargetSpec, wantHash string) bool {
	if deploy.Annotations[annInjectedHash] != wantHash {
		return false
	}
	tmpl := &deploy.Spec.Template.Spec
	hasInit := false
	for _, ic := range tmpl.InitContainers {
		if ic.Name == agentsInitName {
			hasInit = true
			break
		}
	}
	if !hasInit {
		return false
	}
	// Resolve the container we instrumented by its STASHED name (recorded at inject time), not
	// resolveContainer — whose "sole container" default would flip to ambiguous if an unrelated sidecar
	// (istio-proxy, vault-agent, …) is injected out-of-band later, wrongly tearing down a healthy
	// injection (review #26). The hash already matched, so the stash reflects the current spec.
	cname := deploy.Annotations[annOptsContainer]
	var c *corev1.Container
	for i := range tmpl.Containers {
		if tmpl.Containers[i].Name == cname {
			c = &tmpl.Containers[i]
			break
		}
	}
	if c == nil {
		return false // the instrumented container is gone — re-derive
	}
	// The target container must still carry the agent flags, the shared volume mount, and (when
	// coverage is on) the coverage port — any stripped piece re-heals.
	if ev := findEnv(c.Env, jvmOptsVarName(spec)); ev == nil || !strings.Contains(ev.Value, buildAgentArgs(spec)) {
		return false
	}
	mounted := false
	for _, vm := range c.VolumeMounts {
		if vm.Name == agentsVolumeName {
			mounted = true
			break
		}
	}
	if !mounted {
		return false
	}
	if spec.Agents.Coverage.Enabled {
		for _, p := range c.Ports {
			if p.Name == coveragePortName {
				return true
			}
		}
		return false
	}
	return true
}

// wasInjected reports whether we have injected this Deployment before (so a re-derive should revert
// the previous injection first, cleanly re-deriving even if spec.Container changed).
func wasInjected(deploy *appsv1.Deployment) bool {
	_, ok := deploy.Annotations[annInjectedHash]
	return ok
}

// applyInjection mutates deploy in place to carry the agents for the given spec. The original
// jvmOptsVar is read from the target container on first inject (and stashed) and reused thereafter,
// so re-reconciling never double-appends. Returns an error if the target container can't be resolved
// or if its jvmOptsVar is sourced from valueFrom (which we could not restore on revert). Callers that
// re-derive after a spec change should revertInjection first so this reads a clean original.
func applyInjection(deploy *appsv1.Deployment, spec *basquinv1alpha1.BasquinTargetSpec, agentsImage string) error {
	tmpl := &deploy.Spec.Template.Spec
	ci, err := resolveContainer(spec, tmpl)
	if err != nil {
		return err
	}
	if deploy.Annotations == nil {
		deploy.Annotations = map[string]string{}
	}
	varName := jvmOptsVarName(spec)
	cname := tmpl.Containers[ci].Name

	// Determine the ORIGINAL jvmOptsVar. Reuse the stash if we've injected this container before
	// (so we never read an already-appended value); otherwise read it from the container now. Absent
	// vs explicit-empty is tracked by the presence of the annOriginalOpts key, so revert restores an
	// explicit "" but removes an originally-absent var.
	var original string
	var originalPresent bool
	if _, injectedBefore := deploy.Annotations[annOptsContainer]; injectedBefore {
		original, originalPresent = deploy.Annotations[annOriginalOpts]
	} else if ev := findEnv(tmpl.Containers[ci].Env, varName); ev != nil {
		if ev.ValueFrom != nil {
			// We can't faithfully restore a Secret/ConfigMap-sourced var, and appending to it means
			// replacing the reference with a literal — silent data loss on revert. Refuse instead.
			return fmt.Errorf("container %q sources %s from valueFrom; refusing to instrument "+
				"(revert could not restore the reference)", cname, varName)
		}
		original, originalPresent = ev.Value, true
	}

	// initContainer: copy the agents into the shared volume.
	setInitContainer(tmpl, corev1.Container{
		Name:  agentsInitName,
		Image: agentsImage,
		// IfNotPresent, not the tag-derived default: a `:latest` agents image would otherwise default
		// to Always and ImagePullBackOff on a kind-loaded / air-gapped node. The agents image is a
		// pinned build (DD-022), so re-pulling on every start buys nothing.
		ImagePullPolicy: corev1.PullIfNotPresent,
		Command:         []string{"sh", "-c", "cp -r /agents/. " + agentsMountPath + "/"},
		VolumeMounts:    []corev1.VolumeMount{{Name: agentsVolumeName, MountPath: agentsMountPath}},
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
	deploy.Annotations[annOptsVar] = varName
	deploy.Annotations[annOptsContainer] = cname
	if originalPresent {
		deploy.Annotations[annOriginalOpts] = original
	} else {
		delete(deploy.Annotations, annOriginalOpts) // absent original: revert removes the var
	}
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
	cname := deploy.Annotations[annOptsContainer]
	original, originalPresent := deploy.Annotations[annOriginalOpts]
	for i := range tmpl.Containers {
		// The volume mount and coverage port carry names namespaced to us (basquin-agents,
		// basquin-jacoco), so removing them from any container is safe.
		removeVolumeMount(&tmpl.Containers[i], agentsVolumeName)
		removeContainerPort(&tmpl.Containers[i], coveragePortName)
		// The env var name is generic (CATALINA_OPTS/JAVA_TOOL_OPTIONS), so restore/remove it ONLY on
		// the container we actually injected — never an unrelated sidecar that sets the same name.
		if varName != "" && tmpl.Containers[i].Name == cname {
			if originalPresent {
				setEnv(&tmpl.Containers[i], varName, original)
			} else {
				removeEnv(&tmpl.Containers[i], varName)
			}
		}
	}
	delete(deploy.Annotations, annInjectedHash)
	delete(deploy.Annotations, annOriginalOpts)
	delete(deploy.Annotations, annOptsVar)
	delete(deploy.Annotations, annOptsContainer)
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

// findEnv returns a pointer to the named env var (so callers can inspect ValueFrom), or nil if absent.
func findEnv(env []corev1.EnvVar, name string) *corev1.EnvVar {
	for i := range env {
		if env[i].Name == name {
			return &env[i]
		}
	}
	return nil
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
