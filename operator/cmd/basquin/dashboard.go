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
	"net/http"
	"os"
	"os/signal"
	"syscall"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/portforward"
	"k8s.io/client-go/transport/spdy"
)

const dashboardPort = 7070

func runDashboard(args []string) error {
	fs := flag.NewFlagSet("dashboard", flag.ContinueOnError)
	var (
		campaign, namespace, kubeCtx string
		localPort                    int
	)
	fs.StringVar(&campaign, "campaign", "", "Campaign whose dashboard to port-forward (required).")
	fs.StringVar(&namespace, "namespace", "", "Namespace (default: the kube context's namespace, else 'default').")
	fs.StringVar(&namespace, "n", "", "Namespace (shorthand).")
	fs.StringVar(&kubeCtx, "context", "", "Kube context to use (default: current-context).")
	fs.IntVar(&localPort, "local-port", dashboardPort, "Local port to forward from.")
	if err := fs.Parse(args); err != nil {
		if err == flag.ErrHelp {
			return nil
		}
		return err
	}
	if campaign == "" {
		return fmt.Errorf("--campaign is required")
	}

	cc := kubeConfig(kubeCtx)
	ns := resolveNamespace(cc, namespace)
	cfg, err := cc.ClientConfig()
	if err != nil {
		return fmt.Errorf("loading kubeconfig: %w", err)
	}
	clientset, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		return err
	}
	ctx := context.Background()

	// Find a Running dashboard pod for this campaign (labels set by the operator's dashboardSelector).
	pods, err := clientset.CoreV1().Pods(ns).List(ctx, metav1.ListOptions{
		LabelSelector: "basquin.dev/campaign=" + campaign + ",app.kubernetes.io/component=dashboard",
	})
	if err != nil {
		return err
	}
	podName := ""
	for i := range pods.Items {
		// Require Ready, not just Running — the dashboard has a readinessProbe on /api/campaigns, so a
		// freshly-rolled Running pod may not be serving yet (review #31).
		if pods.Items[i].Status.Phase == corev1.PodRunning && podReady(&pods.Items[i]) {
			podName = pods.Items[i].Name
			break
		}
	}
	if podName == "" {
		return fmt.Errorf("no ready dashboard pod for campaign %q in %s (is dashboard.enabled and the campaign past Provisioning?)", campaign, ns)
	}

	// Port-forward to it (dashboard listens on 7070).
	req := clientset.CoreV1().RESTClient().Post().Resource("pods").Namespace(ns).Name(podName).SubResource("portforward")
	transport, upgrader, err := spdy.RoundTripperFor(cfg)
	if err != nil {
		return err
	}
	dialer := spdy.NewDialer(upgrader, &http.Client{Transport: transport}, "POST", req.URL())

	stopCh := make(chan struct{}, 1)
	readyCh := make(chan struct{})
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sig
		close(stopCh)
	}()

	fw, err := portforward.New(dialer, []string{fmt.Sprintf("%d:%d", localPort, dashboardPort)}, stopCh, readyCh, os.Stdout, os.Stderr)
	if err != nil {
		return err
	}
	go func() {
		<-readyCh
		fmt.Printf("Dashboard for %q at http://localhost:%d  (Ctrl-C to stop)\n", campaign, localPort)
	}()
	return fw.ForwardPorts() // blocks until stopCh closed
}

// podReady reports whether the pod's PodReady condition is True.
func podReady(p *corev1.Pod) bool {
	for _, cond := range p.Status.Conditions {
		if cond.Type == corev1.PodReady {
			return cond.Status == corev1.ConditionTrue
		}
	}
	return false
}
