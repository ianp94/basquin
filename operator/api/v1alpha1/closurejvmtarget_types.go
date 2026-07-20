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

package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// The CRD shape mirrors docs/OPERATOR-DESIGN.md §3. The spec is the entire user-facing API: name a
// Deployment, choose which agents to inject and how the JVM picks them up. P1 (this scaffold) only
// observes and writes status; no field here mutates a workload yet — the injection those fields
// describe lands in P2.

// DeploymentReference names the Deployment to instrument, in the CR's own namespace.
type DeploymentReference struct {
	// Name of the Deployment to instrument.
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`
}

// CoverageSpec configures the JaCoCo tcpserver for coverage-guided-over-HTTP (v0.10 / DD-023).
type CoverageSpec struct {
	// Enabled turns on the JaCoCo agent + coverage port.
	Enabled bool `json:"enabled"`

	// Port the JaCoCo tcpserver listens on inside the pod.
	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=65535
	// +kubebuilder:default=6300
	Port int32 `json:"port,omitempty"`

	// Includes is the JaCoCo class-include filter (e.g. "org.mybatis.jpetstore.*"). It is REQUIRED
	// when coverage is enabled and must never default to "*": DD-022 showed a "*" filter silently
	// instruments Tomcat/MyBatis, inflating the coverage denominator and adding enough overhead to
	// fake latency violations. A validating webhook (P2) rejects enabled-without-includes; there is
	// deliberately no default to fall back to.
	// +optional
	Includes string `json:"includes,omitempty"`
}

// AgentsSpec selects which ClosureJVM agents to inject. Each is independently toggleable.
type AgentsSpec struct {
	// ThreadTracker injects the native JVMTI agent (-agentpath) for the leak/thread oracle.
	// +kubebuilder:default=true
	ThreadTracker bool `json:"threadTracker,omitempty"`

	// Valve injects the Tomcat valve for server-side invariants. Tomcat targets only.
	// +kubebuilder:default=true
	Valve bool `json:"valve,omitempty"`

	// Coverage configures the JaCoCo coverage agent.
	// +optional
	Coverage CoverageSpec `json:"coverage,omitempty"`
}

// InvariantsSpec is passed through as -Dclosurejvm.invariant.* flags.
type InvariantsSpec struct {
	// Mode is "soft" (collect signals) or "hard" (fail the iteration).
	// +kubebuilder:validation:Enum=soft;hard
	// +kubebuilder:default=soft
	Mode string `json:"mode,omitempty"`

	// LatencyMaxMs is the per-iteration latency threshold in milliseconds.
	// +optional
	LatencyMaxMs int32 `json:"latencyMaxMs,omitempty"`

	// HeapDeltaMaxKb is the per-iteration heap-delta threshold in KB.
	// +optional
	HeapDeltaMaxKb int32 `json:"heapDeltaMaxKb,omitempty"`
}

// ClosureJVMTargetSpec defines the desired state of ClosureJVMTarget.
type ClosureJVMTargetSpec struct {
	// DeploymentRef is the Deployment to instrument, in this namespace.
	DeploymentRef DeploymentReference `json:"deploymentRef"`

	// Container in the pod template to instrument. Optional: defaults to the sole container, else
	// required (a validating webhook enforces this in P2).
	// +optional
	Container string `json:"container,omitempty"`

	// Agents selects which agents to inject.
	// +optional
	Agents AgentsSpec `json:"agents,omitempty"`

	// JVMOptsVar is the env var the JVM reads launch flags from. Tomcat images honor CATALINA_OPTS;
	// everything else uses JAVA_TOOL_OPTIONS. The operator APPENDS to any existing value (DD-024),
	// never replaces it, so the app's own heap/GC flags survive instrumentation.
	// +kubebuilder:validation:Enum=CATALINA_OPTS;JAVA_TOOL_OPTIONS
	// +kubebuilder:default=JAVA_TOOL_OPTIONS
	JVMOptsVar string `json:"jvmOptsVar,omitempty"`

	// Invariants configures the -Dclosurejvm.invariant.* flags.
	// +optional
	Invariants InvariantsSpec `json:"invariants,omitempty"`

	// DashboardPush is host:port of the standalone dashboard aggregator (DD-013) to push
	// status/findings to. Empty disables push.
	// +optional
	DashboardPush string `json:"dashboardPush,omitempty"`

	// CoverageService, when true, creates a headless Service selecting this target's pods on the
	// coverage port so one driver can reach every replica by DNS (DD-023). Opt-in.
	// +optional
	CoverageService bool `json:"coverageService,omitempty"`
}

// TargetPhase is a coarse lifecycle summary for humans and kubectl printing.
// +kubebuilder:validation:Enum=Pending;Injecting;Injected;Reverting;Error
type TargetPhase string

const (
	PhasePending   TargetPhase = "Pending"
	PhaseInjecting TargetPhase = "Injecting"
	PhaseInjected  TargetPhase = "Injected"
	PhaseReverting TargetPhase = "Reverting"
	PhaseError     TargetPhase = "Error"
)

// ClosureJVMTargetStatus defines the observed state of ClosureJVMTarget.
type ClosureJVMTargetStatus struct {
	// Phase is a coarse lifecycle summary. In P1 the controller only ever reports Pending (observed,
	// not yet injected) — injection phases arrive with P2.
	// +optional
	Phase TargetPhase `json:"phase,omitempty"`

	// ObservedGeneration is the spec generation this status was computed from.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// InstrumentedReplicas is how many of the target's pods currently carry the agents. 0 in P1.
	// +optional
	InstrumentedReplicas int32 `json:"instrumentedReplicas,omitempty"`

	// CoverageEndpoint is the host:port a driver points the DD-023 coverage flag at, once P3 creates
	// the headless Service. Empty until then.
	// +optional
	CoverageEndpoint string `json:"coverageEndpoint,omitempty"`

	// Conditions follow the standard Kubernetes condition convention (Ready, etc.).
	// +optional
	// +listType=map
	// +listMapKey=type
	Conditions []metav1.Condition `json:"conditions,omitempty"`
}

//+kubebuilder:object:root=true
//+kubebuilder:subresource:status
//+kubebuilder:printcolumn:name="Deployment",type=string,JSONPath=`.spec.deploymentRef.name`
//+kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
//+kubebuilder:printcolumn:name="Instrumented",type=integer,JSONPath=`.status.instrumentedReplicas`
//+kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// ClosureJVMTarget is the Schema for the closurejvmtargets API
type ClosureJVMTarget struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ClosureJVMTargetSpec   `json:"spec,omitempty"`
	Status ClosureJVMTargetStatus `json:"status,omitempty"`
}

//+kubebuilder:object:root=true

// ClosureJVMTargetList contains a list of ClosureJVMTarget
type ClosureJVMTargetList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ClosureJVMTarget `json:"items"`
}

func init() {
	SchemeBuilder.Register(&ClosureJVMTarget{}, &ClosureJVMTargetList{})
}
