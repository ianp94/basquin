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
	"io"
	"os"
	"text/tabwriter"
	"time"

	"sigs.k8s.io/controller-runtime/pkg/client"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

func runStatus(args []string) error {
	fs := flag.NewFlagSet("status", flag.ContinueOnError)
	var (
		namespace, kubeCtx string
		watch              bool
		every              time.Duration
	)
	fs.StringVar(&namespace, "namespace", "", "Namespace (default: the kube context's namespace, else 'default').")
	fs.StringVar(&namespace, "n", "", "Namespace (shorthand).")
	fs.StringVar(&kubeCtx, "context", "", "Kube context to use (default: current-context).")
	fs.BoolVar(&watch, "watch", false, "Re-render every few seconds until interrupted.")
	fs.DurationVar(&every, "interval", 3*time.Second, "Refresh interval with --watch.")
	if err := fs.Parse(args); err != nil {
		if err == flag.ErrHelp {
			return nil
		}
		return err
	}

	cc := kubeConfig(kubeCtx)
	ns := resolveNamespace(cc, namespace)
	c, err := newClient(cc)
	if err != nil {
		return err
	}
	ctx := context.Background()

	if !watch {
		return printStatus(ctx, c, ns, os.Stdout)
	}
	for {
		fmt.Fprint(os.Stdout, "\033[H\033[2J") // clear screen
		fmt.Fprintf(os.Stdout, "basquin status — namespace %s — %s\n\n", ns, "refreshing, Ctrl-C to stop")
		if err := printStatus(ctx, c, ns, os.Stdout); err != nil {
			return err
		}
		time.Sleep(every)
	}
}

// printStatus lists the targets and campaigns in ns as two aligned tables.
func printStatus(ctx context.Context, c client.Client, ns string, out io.Writer) error {
	var targets basquinv1alpha1.BasquinTargetList
	if err := c.List(ctx, &targets, client.InNamespace(ns)); err != nil {
		return err
	}
	tw := tabwriter.NewWriter(out, 0, 0, 3, ' ', 0)
	fmt.Fprintln(tw, "TARGET\tDEPLOYMENT\tPHASE\tCOVERAGE-ENDPOINT")
	if len(targets.Items) == 0 {
		fmt.Fprintln(tw, "(none)\t\t\t")
	}
	for i := range targets.Items {
		t := &targets.Items[i]
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\n", t.Name, t.Spec.DeploymentRef.Name, orNone(string(t.Status.Phase)), orNone(t.Status.CoverageEndpoint))
	}
	tw.Flush()

	var campaigns basquinv1alpha1.BasquinCampaignList
	if err := c.List(ctx, &campaigns, client.InNamespace(ns)); err != nil {
		return err
	}
	fmt.Fprintln(out)
	fmt.Fprintln(tw, "CAMPAIGN\tTARGET\tMODE\tPHASE\tMETRICS\tDASHBOARD")
	if len(campaigns.Items) == 0 {
		fmt.Fprintln(tw, "(none)\t\t\t\t\t")
	}
	for i := range campaigns.Items {
		cp := &campaigns.Items[i]
		fmt.Fprintln(tw, campaignRow(cp))
	}
	return tw.Flush()
}

// campaignRow renders one tab-separated CAMPAIGN/TARGET/MODE/PHASE/METRICS/DASHBOARD row. METRICS is
// mode-aware: explore shows coverage% / findings count (from the driver's end-of-run summary); load
// shows throughput (rps) and p99 latency from cp.Status.Load (DD-026/DD-033), since coverage/findings
// are meaningless for a load run (a fixed corpus replay, not a fuzz search). A load campaign with no
// Status.Load yet (still Running, no results published) renders "pending" rather than falling through
// to the explore-shaped "0 finds", which would misleadingly imply a fuzz search found nothing.
func campaignRow(cp *basquinv1alpha1.BasquinCampaign) string {
	mode := cp.Spec.Mode
	if mode == "" {
		mode = "explore"
	}
	var metrics string
	switch {
	case mode == "load" && cp.Status.Load != nil:
		metrics = fmt.Sprintf("%s rps · p99 %dms", orNone(cp.Status.Load.ThroughputRps), cp.Status.Load.LatencyMs.P99)
	case mode == "load":
		metrics = "pending"
	default:
		metrics = fmt.Sprintf("%s · %d finds", orNone(cp.Status.CoveragePct), cp.Status.Findings)
	}
	return fmt.Sprintf("%s\t%s\t%s\t%s\t%s\t%s", cp.Name, cp.Spec.TargetRef.Name, mode,
		orNone(string(cp.Status.Phase)), metrics, orNone(cp.Status.DashboardURL))
}
