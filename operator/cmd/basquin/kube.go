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
	"fmt"

	"k8s.io/apimachinery/pkg/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/tools/clientcmd"
	"sigs.k8s.io/controller-runtime/pkg/client"

	basquinv1alpha1 "github.com/ianp94/basquin/operator/api/v1alpha1"
)

// kubeConfig loads the standard kubeconfig (KUBECONFIG / ~/.kube/config), honoring an explicit context.
func kubeConfig(kubeCtx string) clientcmd.ClientConfig {
	rules := clientcmd.NewDefaultClientConfigLoadingRules()
	overrides := &clientcmd.ConfigOverrides{}
	if kubeCtx != "" {
		overrides.CurrentContext = kubeCtx
	}
	return clientcmd.NewNonInteractiveDeferredLoadingClientConfig(rules, overrides)
}

// resolveNamespace returns the flag value, else the context's default namespace, else "default".
func resolveNamespace(cc clientcmd.ClientConfig, nsFlag string) string {
	if nsFlag != "" {
		return nsFlag
	}
	if ns, _, err := cc.Namespace(); err == nil && ns != "" {
		return ns
	}
	return "default"
}

// newClient builds a controller-runtime client whose scheme knows the Basquin CRDs + core types
// (core is needed to create the grammar/corpus ConfigMaps).
func newClient(cc clientcmd.ClientConfig) (client.Client, error) {
	cfg, err := cc.ClientConfig()
	if err != nil {
		return nil, fmt.Errorf("loading kubeconfig: %w", err)
	}
	scheme := runtime.NewScheme()
	if err := clientgoscheme.AddToScheme(scheme); err != nil {
		return nil, err
	}
	if err := basquinv1alpha1.AddToScheme(scheme); err != nil {
		return nil, err
	}
	c, err := client.New(cfg, client.Options{Scheme: scheme})
	if err != nil {
		return nil, fmt.Errorf("building client: %w", err)
	}
	return c, nil
}
