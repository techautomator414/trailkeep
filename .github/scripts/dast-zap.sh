#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# Runs ZAP's Automation Framework plan (security/zap/plan.yaml) as one scan identity against the stack dast-app.sh
# started (WP-28 part c), then:
#   - fails if the scan lost its session, which would make every later request a scan of the sign-in redirect;
#   - copies ZAP's own zap.log next to the reports, and removes the session value from all of them before upload;
#   - lists every alert of Medium or higher in the job summary;
#   - fails if ZAP wrote no JSON report, or if a High insight stopped the plan early (the scan is then partial);
#   - fails with its own error if the gate and the report disagree: ZAP exited 1 but the report holds no alert of
#     Medium or higher, or the report holds one but ZAP did not exit 1.
# ZAP 2.17.0's exit status (checked against the pinned image): 0 when no alert reaches Low, 1 when one reaches the
# plan's errorLevel (Medium) or the plan fails, 2 when the worst is Low or the plan only warns. This script maps it:
# 0 passes, 2 passes with a warning, 1 and anything else fail.
# The alertFilter job is filled from security/zap/suppressions.yaml, keeping only entries whose expiry has not passed;
# ZapPlanTest checks every entry's justification, review link and expiry on each PR.
# Usage: dast-zap.sh <identity>   (needs target/dast/<identity>.cookie and $ZAP_IMAGE)
set -euo pipefail

identity="$1"
cookie="$(cat "target/dast/${identity}.cookie")"
work="target/zap/${identity}"
rm -rf "${work}" && mkdir -p "${work}/reports"
chmod -R a+rwX target/zap # the image runs as its own user

# map, not [.suppressions[] | {...}]: yq builds one stray object from an empty list with the latter.
today="$(date -u +%F)" yq --output-format=yaml '
  .suppressions | map(select(.expires >= strenv(today)))
    | map({"ruleId": .ruleId, "newRisk": "False Positive", "url": .url, "urlRegex": true})
' security/zap/suppressions.yaml > "${work}/filters.yaml"
filters="${work}/filters.yaml" yq '(.jobs[] | select(.type == "alertFilter") | .alertFilters) = load(strenv(filters))' \
  security/zap/plan.yaml > "${work}/plan.yaml"

# ZAP's authentication variables add the cookie to every request to localhost (the site is matched by host name; a
# host:port value matches nothing). Its own proxy listens on 18080, because the application holds 8080 on the host.
# insights.exitAuto=false: ZAP must never exit itself mid-plan on a High insight, which discards the session under
# the report jobs (#211); the plan's insights job stops the plan instead. The container is kept until ZAP's own log
# is copied out of it.
container="zap-${identity}"
docker rm --force "${container}" > /dev/null 2>&1 || true
status=0
ZAP_AUTH_HEADER=Cookie ZAP_AUTH_HEADER_VALUE="${cookie}" ZAP_AUTH_HEADER_SITE=localhost SCAN_IDENTITY="${identity}" \
  docker run --name "${container}" --network host \
  --env ZAP_AUTH_HEADER --env ZAP_AUTH_HEADER_VALUE --env ZAP_AUTH_HEADER_SITE --env SCAN_IDENTITY \
  --volume "${PWD}/${work}:/zap/wrk:rw" \
  "${ZAP_IMAGE}" zap.sh -cmd -port 18080 -config insights.exitAuto=false -autorun /zap/wrk/plan.yaml || status=$?
docker cp "${container}:/home/zap/.ZAP/zap.log" "${work}/zap.log" || echo "::warning::ZAP left no zap.log."
docker rm --force "${container}" > /dev/null || true

still="$(curl -s -o /dev/null -w '%{http_code}' -H "Cookie: ${cookie}" http://localhost:8080/)"

# The session dies with this job's stack, but no report leaves the runner carrying it.
session="${cookie#SESSION=}"
# grep -l exits 1 when no report carries the value, which pipefail would turn into a failed scan.
{ grep -rlF "${session}" "${work}" || true; } | while read -r file; do
  sed -i "s|${session}|[session removed]|g" "${file}"
done

report="${work}/reports/zap-${identity}.json"
gating=0
stopped=""
if [ -s "${report}" ]; then
  gating="$(jq '[.site[].alerts[] | select((.riskcode | tonumber) >= 2)] | length' "${report}")"
  stopped="$(jq -r '.stoppingInsight // empty | "\(.key) at \(.statistic) (\(.reason))"' "${report}")"
  {
    echo "### ZAP as ${identity}: alerts of Medium or higher"
    jq -r '[.site[].alerts[] | select((.riskcode | tonumber) >= 2)]
      | if length == 0 then "None." else (.[] | "- **\(.riskdesc)** \(.name) (rule \(.pluginid)), \(.count) instance(s)") end' \
      "${report}"
    if [ -n "${stopped}" ]; then
      echo "ZAP stopped the plan early on a High insight: ${stopped}."
    fi
  } >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
fi

if [ "${still}" != "200" ]; then
  echo "::error::The scan lost its session (the dashboard answered ${still}); its results do not cover the application."
  exit 1
fi
if [ ! -s "${report}" ]; then
  echo "::error title=ZAP (${identity})::ZAP wrote no JSON report (ZAP exit ${status}); see zap.log."
  exit 1
fi
# A plan the Insights add-on stopped covered only part of the application, whatever ZAP's exit status says.
if [ -n "${stopped}" ]; then
  echo "::error title=ZAP (${identity})::ZAP stopped the plan early on a High insight (${stopped}), so the scan is partial; see zap.log."
  exit 1
fi
# The gate (exitStatus) and the report must count the same alerts, or no reviewer can attribute or suppress a failure.
if [ "${status}" = "1" ] && [ "${gating}" = "0" ]; then
  echo "::error title=ZAP (${identity})::The gate and the report disagree: ZAP exited 1 (an alert of Medium or higher, or a plan error), but the report holds no alert of Medium or higher; see zap.log."
  exit 1
fi
if [ "${status}" != "1" ] && [ "${gating}" != "0" ]; then
  echo "::error title=ZAP (${identity})::The gate and the report disagree: the report holds ${gating} alert(s) of Medium or higher, but ZAP exited ${status}; see zap.log."
  exit 1
fi
case "${status}" in
  0)
    echo "ZAP as ${identity}: no alert of Low or higher."
    exit 0
    ;;
  2)
    echo "::warning title=ZAP (${identity})::Low alerts or plan warnings only (ZAP exit 2); the gate is Medium, so it passes."
    echo "ZAP as ${identity}: passed with Low alerts or plan warnings (exit 2); see the reports." \
      >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
    exit 0
    ;;
  1)
    echo "::error title=ZAP (${identity})::${gating} alert(s) of Medium or higher (ZAP exit 1), listed in the job summary and the reports."
    exit 1
    ;;
  *)
    echo "::error title=ZAP (${identity})::ZAP exited ${status}, which is not one of its documented statuses."
    exit 1
    ;;
esac
