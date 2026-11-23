#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# The release's CodeQL gate (#321). The analyze step uploads its SARIF but does not fail on alerts, so this asks code
# scanning, for the tag's ref, whether (1) an analysis of exactly this commit exists, so a missing or stale analysis
# cannot pass, and (2) any CodeQL alert is still open on this commit. Dismissed and fixed alerts are not open.
# Usage: codeql-open-alerts.sh <owner/repo> <full ref> <commit sha>   (GH_TOKEN needs security-events: read)
set -euo pipefail
repo=$1 ref=$2 sha=$3
api="repos/${repo}/code-scanning"

analyses="$(gh api --paginate "${api}/analyses?ref=${ref}&tool_name=CodeQL&per_page=100" \
  --jq ".[] | select(.commit_sha == \"${sha}\") | .id")"
if [ -z "${analyses}" ]; then
  echo "::error::no CodeQL analysis of ${sha} on ${ref} in code scanning; the release does not pass without one"
  exit 1
fi

open="$(gh api --paginate "${api}/alerts?ref=${ref}&tool_name=CodeQL&state=open&per_page=100" \
  --jq ".[] | select(.most_recent_instance.commit_sha == \"${sha}\") |
         \"#\(.number) \(.rule.security_severity_level // .rule.severity) \(.rule.id) \(.html_url)\"")"
if [ -n "${open}" ]; then
  echo "::error::${sha} has open CodeQL alerts; the release stops until each is fixed or dismissed with a reason"
  echo "${open}"
  exit 1
fi
echo "CodeQL: analysis of ${sha} found, 0 open alerts on ${ref}"
