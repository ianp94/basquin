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
// already-instrumented BasquinTarget: the operator launches the coverage-guided driver (and, by
// default, a per-campaign dashboard), then aggregates the result.

// TargetReference names the BasquinTarget to drive, in the campaign's own namespace.
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

	// Invariants passed through to the driver JVM as -Dbasquin.invariant.* flags.
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

// BasquinCampaignSpec defines the desired state of BasquinCampaign.
// Exactly one of driver.duration / driver.iterations must be set.
// +kubebuilder:validation:XValidation:rule="(has(self.driver.duration) && size(self.driver.duration) > 0) != (has(self.driver.iterations) && self.driver.iterations > 0)",message="set exactly one of driver.duration or driver.iterations"
// load mode replays a saved corpus at volume — it requires driver.corpusConfigMap, and ignores a
// grammar / an iteration count (it is duration-bounded). DD-026.
// +kubebuilder:validation:XValidation:rule="self.mode != 'load' || (has(self.driver.corpusConfigMap) && size(self.driver.corpusConfigMap) > 0)",message="mode: load requires driver.corpusConfigMap (the corpus to replay)"
// +kubebuilder:validation:XValidation:rule="self.mode != 'load' || !(has(self.driver.grammarConfigMap) && size(self.driver.grammarConfigMap) > 0)",message="mode: load replays a fixed corpus and ignores a grammar; remove driver.grammarConfigMap"
// load is duration-bounded; iterations is ignored by the load driver, so require duration explicitly
// (the duration-XOR-iterations rule alone would let an iterations-only load run silently default to 60s).
// +kubebuilder:validation:XValidation:rule="self.mode != 'load' || (has(self.driver.duration) && size(self.driver.duration) > 0)",message="mode: load is duration-bounded; set driver.duration (driver.iterations is ignored)"
type BasquinCampaignSpec struct {
	// TargetRef is the BasquinTarget to drive (must be Injected before the run starts).
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

// BasquinCampaignStatus defines the observed state of BasquinCampaign.
type BasquinCampaignStatus struct {
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

	// FindingsLowerBound / ReportMisses qualify Findings for an EXPLORE run (DD-040).
	//
	// The driver polls the target for each request's measurements; a poll that misses yields an
	// unmeasured sample, never a zero, and ticks reportMisses. A run with any miss therefore knows
	// its own finding count is incomplete — and the driver has emitted exactly that, as
	// findingsLowerBound, since DD-040. It reached no consumer: `kubectl get basquincampaign` read
	// "run complete: …, 12 findings" for a partially blind run, which is precisely the
	// emitted-since-DD-035-and-parsed-nowhere defect DD-040 fixes for driftUnavailable.
	//
	// FindingsLowerBound true means Findings is a floor, not a count.
	// +optional
	FindingsLowerBound bool `json:"findingsLowerBound,omitempty"`

	// ReportMisses is how many requests the driver could not obtain measurements for. A POINTER, on
	// the same reasoning as LoadStatus.HeapDriftKb: nil means the driver never reported the figure
	// (an older driver image), a non-nil 0 means it reported and nothing missed. A plain int64 would
	// render both as a reassuring "0 misses".
	// +optional
	ReportMisses *int64 `json:"reportMisses,omitempty"`

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

	// HeapDriftKb / ThreadDrift are end-minus-start deltas over the soak (slow-leak signal), polled
	// from the TARGET over /__basquin/drift.
	//
	// Pointers, deliberately (DD-040). The driver already omitted these whenever the drift could not
	// be trusted, emitting driftUnavailable:true instead (DD-035) — but as non-pointer int64/int32
	// they unmarshalled straight back to 0, so "we could not measure the target's heap" and "the
	// target's heap was perfectly flat" arrived here as the same value, and the dashboard printed
	// `0 KiB` for both. nil means the run did not measure it; see DriftUnavailable for why.
	// +optional
	HeapDriftKb *int64 `json:"heapDriftKb,omitempty"`
	// +optional
	ThreadDrift *int32 `json:"threadDrift,omitempty"`

	// DriftUnavailable is the driver's own signal (DD-035) that this run's heap/thread drift is not a
	// trustworthy number: the baseline or terminal poll failed, or the target never confirmed
	// load mode. Emitted by the driver since DD-035 but, until DD-040, parsed nowhere at all.
	// +optional
	DriftUnavailable bool `json:"driftUnavailable,omitempty"`

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

// LoadViolations counts invariant breaches during the load run.
//
// Every field is a POINTER, and that is the whole point (DD-040). Load mode is lock-free passthrough
// (DD-029): the target's valve evaluates nothing, so the driver is the only evaluator, it only
// evaluates latency, and only when a latencyMaxMs threshold actually reached it. Heap and Thread are
// therefore NEVER evaluated under load (they surface as end-to-end drift instead), and Latency is
// unevaluated whenever no threshold was configured.
//
// As non-pointer int32 with omitempty, a field the driver omitted unmarshalled back to 0 — so the
// operator re-fabricated, one layer up, exactly the zero the driver had refused to print, and
// omitempty additionally made a real measured 0 indistinguishable from absent on the way back out.
// A Roller run reported violations.latency: 0 at a p50 of 503ms against a 250ms budget on that path.
//
// nil = this run did not check it. A non-nil 0 = checked, and clean. NotEvaluated names the omitted
// invariants explicitly, so the distinction survives for a human reading the status too.
type LoadViolations struct {
	// +optional
	Latency *int32 `json:"latency,omitempty"`
	// +optional
	Heap *int32 `json:"heap,omitempty"`
	// +optional
	Thread *int32 `json:"thread,omitempty"`
	// NotEvaluated lists the invariants this run never checked ("latency", "heap", "thread").
	// +optional
	NotEvaluated []string `json:"notEvaluated,omitempty"`
}

//+kubebuilder:object:root=true
//+kubebuilder:subresource:status
//+kubebuilder:printcolumn:name="Target",type=string,JSONPath=`.spec.targetRef.name`
//+kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
//+kubebuilder:printcolumn:name="Coverage",type=string,JSONPath=`.status.coveragePct`
//+kubebuilder:printcolumn:name="Findings",type=integer,JSONPath=`.status.findings`
//+kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`

// BasquinCampaign is the Schema for the basquincampaigns API
type BasquinCampaign struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   BasquinCampaignSpec   `json:"spec,omitempty"`
	Status BasquinCampaignStatus `json:"status,omitempty"`
}

//+kubebuilder:object:root=true

// BasquinCampaignList contains a list of BasquinCampaign
type BasquinCampaignList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []BasquinCampaign `json:"items"`
}

func init() {
	SchemeBuilder.Register(&BasquinCampaign{}, &BasquinCampaignList{})
}
