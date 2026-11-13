#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# Runs ZAP's Automation Framework plan (security/zap/plan.yaml) as one scan identity against the stack dast-app.sh
# started (WP-28 part c), then:
#   - fails if the scan lost its session, which would make every later request a scan of the sign-in redirect;
#   - removes the session value from every report before they are uploaded;
#   - lists every alert of Medium or higher in the job summary.
# The plan's exitStatus job makes ZAP exit non-zero on any such alert; this script exits with ZAP's status.
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
status=0
ZAP_AUTH_HEADER=Cookie ZAP_AUTH_HEADER_VALUE="${cookie}" ZAP_AUTH_HEADER_SITE=localhost SCAN_IDENTITY="${identity}" \
  docker run --rm --network host \
  --env ZAP_AUTH_HEADER --env ZAP_AUTH_HEADER_VALUE --env ZAP_AUTH_HEADER_SITE --env SCAN_IDENTITY \
  --volume "${PWD}/${work}:/zap/wrk:rw" \
  "${ZAP_IMAGE}" zap.sh -cmd -port 18080 -autorun /zap/wrk/plan.yaml || status=$?

still="$(curl -s -o /dev/null -w '%{http_code}' -H "Cookie: ${cookie}" http://localhost:8080/)"

# The session dies with this job's stack, but no report leaves the runner carrying it.
session="${cookie#SESSION=}"
# grep -l exits 1 when no report carries the value, which pipefail would turn into a failed scan.
{ grep -rlF "${session}" "${work}" || true; } | while read -r file; do
  sed -i "s|${session}|[session removed]|g" "${file}"
done

report="${work}/reports/zap-${identity}.json"
if [ -s "${report}" ]; then
  {
    echo "### ZAP as ${identity}: alerts of Medium or higher"
    jq -r '[.site[].alerts[] | select((.riskcode | tonumber) >= 2)]
      | if length == 0 then "None." else (.[] | "- **\(.riskdesc)** \(.name) (rule \(.pluginid)), \(.count) instance(s)") end' \
      "${report}"
  } >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
fi

if [ "${still}" != "200" ]; then
  echo "::error::The scan lost its session (the dashboard answered ${still}); its results do not cover the application."
  exit 1
fi
exit "${status}"
