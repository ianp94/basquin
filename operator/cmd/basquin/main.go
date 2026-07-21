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

// Command basquin is a thin CLI over the Basquin operator's CRDs: it builds and applies real
// typed custom resources (reusing the operator's api/v1alpha1 types + a controller-runtime client)
// so you don't hand-write YAML + kubectl. Ships `instrument`, `run`, `status`, and `dashboard`.
package main

import (
	"fmt"
	"os"
	"runtime"
)

// version is the release version, stamped at build time by release.yml via
//
//	-ldflags "-X main.version=<tag>"
//
// so a downloaded binary can identify which release it came from. Unstamped builds (a plain
// `go build` / `go install` from a checkout) report "dev".
var version = "dev"

// versionString reports the release plus the platform and toolchain it was built for — the platform
// matters because we publish six CLI binaries (linux/darwin/windows x amd64/arm64).
func versionString() string {
	return fmt.Sprintf("basquin %s (%s/%s, %s)", version, runtime.GOOS, runtime.GOARCH, runtime.Version())
}

const usage = `basquin — drive the Basquin Kubernetes operator

Usage:
  basquin <command> [flags]

Commands:
  instrument   Apply a BasquinTarget to instrument an app Deployment (and optionally wait for it).
  run          Apply a BasquinCampaign (with grammar/corpus ConfigMaps) and optionally tail it.
  status       Show the targets and campaigns in a namespace (optionally --watch).
  dashboard    Port-forward a campaign's dashboard to localhost.
  version      Print the CLI version, platform, and Go toolchain.
  help         Show this help.

Run "basquin <command> -h" for a command's flags.
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	cmd := os.Args[1]
	args := os.Args[2:]
	switch cmd {
	case "instrument":
		if err := runInstrument(args); err != nil {
			fmt.Fprintln(os.Stderr, "error: "+err.Error())
			os.Exit(1)
		}
	case "run":
		if err := runRun(args); err != nil {
			fmt.Fprintln(os.Stderr, "error: "+err.Error())
			os.Exit(1)
		}
	case "status":
		if err := runStatus(args); err != nil {
			fmt.Fprintln(os.Stderr, "error: "+err.Error())
			os.Exit(1)
		}
	case "dashboard":
		if err := runDashboard(args); err != nil {
			fmt.Fprintln(os.Stderr, "error: "+err.Error())
			os.Exit(1)
		}
	case "version", "--version":
		fmt.Println(versionString())
	case "help", "-h", "--help":
		fmt.Print(usage)
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n%s", cmd, usage)
		os.Exit(2)
	}
}
