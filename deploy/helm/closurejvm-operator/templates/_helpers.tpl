{{/* Chart name (overridable). */}}
{{- define "closurejvm-operator.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fullname: <release>-<chart>, unless fullnameOverride is set, or the release name already contains the
chart name (avoids closurejvm-closurejvm-operator when installed as `helm install closurejvm ...`).
*/}}
{{- define "closurejvm-operator.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* The controller Deployment / ServiceAccount base name. */}}
{{- define "closurejvm-operator.controllerName" -}}
{{- printf "%s-controller-manager" (include "closurejvm-operator.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* ServiceAccount name (overridable). */}}
{{- define "closurejvm-operator.serviceAccountName" -}}
{{- default (include "closurejvm-operator.controllerName" .) .Values.serviceAccount.name -}}
{{- end -}}

{{/* Common labels. */}}
{{- define "closurejvm-operator.labels" -}}
app.kubernetes.io/name: {{ include "closurejvm-operator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: closurejvm
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
{{- end -}}
