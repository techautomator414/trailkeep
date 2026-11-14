#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# The DAST verdict (WP-28 parts c and e): turns what dast-zap.sh read from one ZAP run into pass or fail, ::warning
# and ::error annotations and job-summary lines. It reads no file, so DastVerdictTest pins every rule on each PR.
#   - Fails in both modes: the scan lost its session; ZAP wrote no JSON report; the gate (ZAP's exit status) and the
#     report disagree; an alert of Medium or higher (ZAP exit 1); an undocumented exit status.
#   - A partial scan, i.e. the active scan cut off at its maxScanDurationInMins cap (or its completion missing from
#     zap.log), or the plan stopped by a High insight such as memory: a ::warning and a summary line in nightly, a
#     failure in release, so that a release never passes on a scan that did not cover the application.
#   - ZAP exit 2 (Low alerts or plan warnings only) passes with a ::warning; 0 passes.
#   - Rules the active scan skipped at their 5-minute limit are counted in the summary, in both modes.
# Usage: dast-verdict.sh <mode: release|nightly> <identity> <dashboard status after the scan> <ZAP exit status>
#          <report written: 1|0> <alerts of Medium or higher in the report> <stopping insight, or "">
#          <active scan seconds from zap.log, or ""> <active scan cap in seconds> <rules skipped at their limit>
set -uo pipefail

mode="$1" identity="$2" still="$3" status="$4" report="$5" gating="$6" stopped="$7" active="$8" cap="$9"
skipped="${10}"
summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
title="ZAP (${identity})"

fail() {
  echo "::error title=${title}::$1"
  echo "ZAP as ${identity}: FAILED: $1" >> "${summary}"
  exit 1
}

case "${mode}" in
  release | nightly) ;;
  *) fail "Unknown mode '${mode}'; zap.yml must pass release or nightly." ;;
esac
if [ "${still}" != "200" ]; then
  fail "The scan lost its session (the dashboard answered ${still}); its results do not cover the application."
fi
if [ "${report}" != "1" ]; then
  fail "ZAP wrote no JSON report (ZAP exit ${status}); see zap.log."
fi
# The gate (exitStatus) and the report must count the same alerts, or no reviewer can attribute or suppress a failure.
if [ "${status}" = "1" ] && [ "${gating}" = "0" ]; then
  fail "The gate and the report disagree: ZAP exited 1 (an alert of Medium or higher, or a plan error), but the report holds no alert of Medium or higher; see zap.log."
fi
if [ "${status}" != "1" ] && [ "${gating}" != "0" ]; then
  fail "The gate and the report disagree: the report holds ${gating} alert(s) of Medium or higher, but ZAP exited ${status}; see zap.log."
fi

if [ "${skipped}" != "0" ]; then
  echo "ZAP as ${identity}: ${skipped} active scan rule(s) stopped at their 5-minute limit; see zap.log." >> "${summary}"
fi

# A partial scan: warned in nightly, fatal in release.
partial=()
if [ -n "${stopped}" ]; then
  partial+=("ZAP stopped the plan early on a High insight (${stopped}), so the scan is partial.")
fi
if [ -z "${active}" ]; then
  partial+=("zap.log does not show the active scan completing, so the scan may be partial.")
elif [ "${active%%.*}" -ge $((cap - 1)) ]; then
  partial+=("The active scan was cut off at its $((cap / 60))-minute cap (it ran ${active} s), so the scan is partial.")
fi
for reason in "${partial[@]}"; do
  if [ "${mode}" = "release" ]; then
    fail "${reason} A release needs a complete scan."
  fi
  echo "::warning title=${title}::${reason}"
  echo "ZAP as ${identity}: WARNING: ${reason}" >> "${summary}"
done

case "${status}" in
  0)
    echo "ZAP as ${identity}: no alert of Low or higher."
    ;;
  2)
    echo "::warning title=${title}::Low alerts or plan warnings only (ZAP exit 2); the gate is Medium, so it passes."
    echo "ZAP as ${identity}: passed with Low alerts or plan warnings (exit 2); see the reports." >> "${summary}"
    ;;
  1)
    fail "${gating} alert(s) of Medium or higher (ZAP exit 1), listed in the job summary and the reports."
    ;;
  *)
    fail "ZAP exited ${status}, which is not one of its documented statuses."
    ;;
esac
exit 0
