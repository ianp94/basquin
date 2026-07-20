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

// EDIT THIS FILE!  THIS IS SCAFFOLDING FOR YOU TO OWN!
// NOTE: json tags are required.  Any new fields you add must have json tags for the fields to be serialized.

// The CRD shape mirrors docs/CAMPAIGN-DESIGN.md (DD-025). A campaign is a bounded test run against an
// already-instrumented ClosureJVMTarget: the operator launches the coverage-guided driver (and, by
// default, a per-campaign dashboard), then aggregates the result.

// TargetReference names the ClosureJVMTarget to drive, in the campaign's own namespace.
type TargetReference struct {
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`
}

// CampaignDriverSpec configures the coverage-guided runner (CoverageGuidedRun).
type CampaignDriverSpec struct {
	// One of Duration / Iterations bounds the run (validated on the campaign). Duration is a Go-style
	// string (e.g. "10m", "30s"); the runner exits cleanly at the deadline so its summary is written.
	// +optional
	Duration string `json:"duration,omitempty"`
	// +kubebuilder:validation:Minimum=1
	// +optional
	Iterations int64 `json:"iterations,omitempty"`

	// Grammar / corpus come from a ConfigMap (structure/values as data, DD-016/DD-018).
	// +optional
	GrammarConfigMap string `json:"grammarConfigMap,omitempty"`
	// +optional
	GrammarKey string `json:"grammarKey,omitempty"` // key within the grammar ConfigMap; default the sole key
	// +optional
	CorpusConfigMap string `json:"corpusConfigMap,omitempty"`

	// Invariants passed through to the driver JVM as -Dclosurejvm.invariant.* flags.
	// +optional
	Invariants InvariantsSpec `json:"invariants,omitempty"`

	// ClassesPath is where the app's .class files live inside the TARGET's container image; an
	// initContainer copies them out for JaCoCo coverage analysis (DD-025 §7b).
	// +kubebuilder:default="/usr/local/tomcat/webapps/ROOT/WEB-INF/classes"
	ClassesPath string `json:"classesPath,omitempty"`

	// Concurrency is the number of parallel in-flight requests in load mode (DD-026). Ignored in
	// explore mode. Defaults to 10 when mode=load.
	// +kubebuilder:validation:Minimum=1
	// +optional
	Concurrency int32 `json:"concurrency,omitempty"`

	// Warmup (load mode) is a Go-style duration excluded from the reported latency percentiles and
	// invariant enforcement, to skip JIT / connection-pool warmup noise (e.g. "30s"). Optional.
	// +optional
	Warmup string `json:"warmup,omitempty"`
}

// CampaignDashboardSpec configures the dashboard for a campaign.
type CampaignDashboardSpec struct {
	// Enabled (default true): the operator creates a per-campaign dashboard Deployment+Service,
	// garbage-collected with the campaign. A *bool (not a plain bool) so `enabled: false` survives
	// admission — with a plain bool + omitempty + a true default, false marshals as "unset" and gets
	// re-defaulted to true, making the dashboard impossible to disable programmatically.
	// +kubebuilder:default=true
	Enabled *bool `json:"enabled,omitempty"`
	// ExternalPush (host:port): push to an existing/shared dashboard instead of creating one.
	// +optional
	ExternalPush string `json:"externalPush,omitempty"`
}

// ClosureJVMCampaignSpec defines the desired state of ClosureJVMCampaign.
// Exactly one of driver.duration / driver.iterations must be set.
// +kubebuilder:validation:XValidation:rule="(has(self.driver.duration) && size(self.driver.duration) > 0) != (has(self.driver.iterations) && self.driver.iterations > 0)",message="set exactly one of driver.duration or driver.iterations"
// load mode replays a saved corpus at volume — it requires driver.corpusConfigMap, and ignores a
// grammar / an iteration count (it is duration-bounded). DD-026.
// +kubebuilder:validation:XValidation:rule="self.mode != 'load' || (has(self.driver.corpusConfigMap) && size(self.driver.corpusConfigMap) > 0)",message="mode: load requires driver.corpusConfigMap (the corpus to replay)"
// +kubebuilder:validation:XValidation:rule="self.mode != 'load' || !(has(self.driver.grammarConfigMap) && size(self.driver.grammarConfigMap) > 0)",message="mode: load replays a fixed corpus and ignores a grammar; remove driver.grammarConfigMap"
// load is duration-bounded; iterations is ignored by the load driver, so require duration explicitly
// (the duration-XOR-iterations rule alone would let an iterations-only load run silently default to 60s).
// +kubebuilder:validation:XValidation:rule="self.mode != 'load' || (has(self.driver.duration) && size(self.driver.duration) > 0)",message="mode: load is duration-bounded; set driver.duration (driver.iterations is ignored)"
type ClosureJVMCampaignSpec struct {
	// TargetRef is the ClosureJVMTarget to drive (must be Injected before the run starts).
	TargetRef TargetReference `json:"targetRef"`

	// BaseURL is the app's HTTP entrypoint the driver hits. REQUIRED — the operator doesn't front the
	// app with a Service of its own, so the campaign names it.
	// +kubebuilder:validation:MinLength=1
	BaseURL string `json:"baseURL"`

	// Mode selects the run objective: explore (coverage-guided fuzz, default) or load (replay a saved
	// corpus at volume, watching the same invariant oracles under sustained traffic). DD-026.
	// +kubebuilder:validation:Enum=explore;load
	// +kubebuilder:default=explore
	// +optional
	Mode string `json:"mode,omitempty"`

	Driver CampaignDriverSpec `json:"driver"`

	// +optional
	Dashboard CampaignDashboardSpec `json:"dashboard,omitempty"`
}

// CampaignPhase is a coarse lifecycle summary.
// +kubebuilder:validation:Enum=Pending;Provisioning;Running;Completed;Failed
type CampaignPhase string

const (
	CampaignPending      CampaignPhase = "Pending"      // target not yet Injected
	CampaignProvisioning CampaignPhase = "Provisioning" // creating dashboard / driver Job
	CampaignRunning      CampaignPhase = "Running"      // driver Job active
	CampaignCompleted    CampaignPhase = "Completed"    // driver Job succeeded, summary read
	CampaignFailed       CampaignPhase = "Failed"       // driver failed, or target went away mid-run
)

// ClosureJVMCampaignStatus defines the observed state of ClosureJVMCampaign.
type ClosureJVMCampaignStatus struct {
	// +optional
	Phase CampaignPhase `json:"phase,omitempty"`
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`
	// +optional
	DriverJob string `json:"driverJob,omitempty"`
	// +optional
	DashboardURL string `json:"dashboardURL,omitempty"`
	// CoveragePct/Findings are read from the driver's end-of-run summary (DD-025 §7a).
	// +optional
	CoveragePct string `json:"coveragePct,omitempty"`
	// +optional
	Findings int32 `json:"findings,omitempty"`
	// CorpusConfigMap names a ConfigMap the operator emits at end-of-run holding the run's interesting
	// "replay corpus" (the inputs that reached new coverage), for reproducibility, the dashboard corpus
	// view, and load-mode replay (DD-026 PR 1). Owner-referenced to the campaign, so it GCs with it.
	// +optional
	CorpusConfigMap string `json:"corpusConfigMap,omitempty"`

	// Load holds load-mode results (throughput, latency percentiles, drift) — set only for mode: load.
	// +optional
	Load *LoadStatus `json:"load,omitempty"`
	// +optional
	StartTime *metav1.Time `json:"startTime,omitempty"`
	// +optional
	CompletionTime *metav1.Time `json:"completionTime,omitempty"`
	// +optional
	// +listType=map
	// +listMapKey=type
	Conditions []metav1.Condition `json:"conditions,omitempty"`
}

// LoadStatus is the load/soak run's result summary (DD-026 PR 2).
type LoadStatus struct {
	// Requests is the total requests fired during the measured (post-warmup) window.
	Requests int64 `json:"requests,omitempty"`
	// ThroughputRps is requests/second over the measured window (formatted, e.g. "713.4").
	ThroughputRps string `json:"throughputRps,omitempty"`
	// LatencyMs holds percentile latencies over the measured window.
	LatencyMs LoadLatency `json:"latencyMs,omitempty"`
	// HeapDriftKb / ThreadDrift are end-minus-start deltas over the soak (slow-leak signal).
	HeapDriftKb int64 `json:"heapDriftKb,omitempty"`
	ThreadDrift int32 `json:"threadDrift,omitempty"`
	// Violations counts invariant breaches under load.
	Violations LoadViolations `json:"violations,omitempty"`
}

// LoadLatency is the p50/p90/p99/max latency (ms) under sustained load.
type LoadLatency struct {
	P50 int32 `json:"p50,omitempty"`
	P90 int32 `json:"p90,omitempty"`
	P99 int32 `json:"p99,omitempty"`
	Max int32 `json:"max,omitempty"`
}

// LoadViolations counts invariant breaches during the load run. First cut: only Latency is evaluated
// (per-request, against invariants.latencyMaxMs). Heap/Thread are reported as end-to-end drift
// (HeapDriftKb/ThreadDrift) rather than a threshold-gated count, so they stay 0 here for now — do NOT
// read them as an active heap/thread gate (DD-026 deferred item).
type LoadViolations struct {
	Latency int32 `json:"latency,omitempty"`
	Heap    int32 `json:"heap,omitempty"`
	Thread  int32 `json:"thread,omitempty"`
}

//+kubebuilder:object:root=true
//+kubebuilder:subresource:status
//+kubebuilder:printcolumn:name="Target",type=string,JSONPath=`.spec.targetRef.name`
//+kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
//+kubebuilder:printcolumn:name="Coverage",type=string,JSONPath=`.status.coveragePct`
//+kubebuilder:printcolumn:name="Findings",type=integer,JSONPath=`.status.findings`
//+kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// ClosureJVMCampaign is the Schema for the closurejvmcampaigns API
type ClosureJVMCampaign struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ClosureJVMCampaignSpec   `json:"spec,omitempty"`
	Status ClosureJVMCampaignStatus `json:"status,omitempty"`
}

//+kubebuilder:object:root=true

// ClosureJVMCampaignList contains a list of ClosureJVMCampaign
type ClosureJVMCampaignList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ClosureJVMCampaign `json:"items"`
}

func init() {
	SchemeBuilder.Register(&ClosureJVMCampaign{}, &ClosureJVMCampaignList{})
}
