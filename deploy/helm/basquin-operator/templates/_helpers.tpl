{{/* Chart name (overridable). */}}
{{- define "basquin-operator.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fullname: <release>-<chart>, unless fullnameOverride is set, or the release name already contains the
chart name (avoids basquin-basquin-operator when installed as `helm install basquin ...`).
*/}}
{{- define "basquin-operator.fullname" -}}
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
{{- define "basquin-operator.controllerName" -}}
{{- printf "%s-controller-manager" (include "basquin-operator.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* ServiceAccount name (overridable). */}}
{{- define "basquin-operator.serviceAccountName" -}}
{{- default (include "basquin-operator.controllerName" .) .Values.serviceAccount.name -}}
{{- end -}}

{{/* Common labels. */}}
{{- define "basquin-operator.labels" -}}
app.kubernetes.io/name: {{ include "basquin-operator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: basquin
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
{{- end -}}
