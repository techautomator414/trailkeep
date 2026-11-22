#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# Runs ZAP's Automation Framework plan (security/zap/plan.yaml) as one scan identity against the stack dast-app.sh
# started (WP-28 parts c, d and e), with security/zap/openapi.yaml filled in for the plan's API import, then:
#   - copies ZAP's own zap.log next to the reports, and removes the session value and its CSRF token from all of them
#     before upload;
#   - lists every alert of Medium or higher in the job summary, with each active scan's duration and the peak heap;
#   - hands what it read to dast-verdict.sh, which decides pass or fail (in release, a partial scan fails; in
#     nightly, it warns) and is unit-tested by DastVerdictTest.
# ZAP runs with an explicit heap ($ZAP_HEAP, default 6g) instead of a quarter of the runner's memory, and with
# insights.exitAuto=false: ZAP must never exit itself mid-plan on a High insight, which discards the session under
# the report jobs (#211); the plan's insights job stops the plan instead.
# The alertFilter job is filled from security/zap/suppressions.yaml, keeping only entries whose expiry has not passed;
# ZapPlanTest checks every entry's justification, review link and expiry on each PR.
# Usage: dast-zap.sh <identity>   (needs target/dast/<identity>.cookie, <identity>.csrf and api-values.txt, which
# DastSessionIT writes, $ZAP_IMAGE and $ZAP_MODE: release|nightly)
set -euo pipefail

identity="$1"
mode="${ZAP_MODE:?ZAP_MODE must be release or nightly}"
heap="${ZAP_HEAP:-6g}"
cookie="$(cat "target/dast/${identity}.cookie")"
work="target/zap/${identity}"
rm -rf "${work}" && mkdir -p "${work}/reports"
chmod -R a+rwX target/zap # the image runs as its own user

# map, not [.suppressions[] | {...}]: yq builds one stray object from an empty list with the latter.
today="$(date -u +%F)" yq --output-format=yaml '
  .suppressions | map(select(.expires >= strenv(today)))
    | map({"ruleId": .ruleId, "newRisk": "False Positive", "url": .url, "urlRegex": true})
' security/zap/suppressions.yaml > "${work}/filters.yaml"
# ZAP substitutes ${SCAN_IDENTITY} in file names but not in a report's title, so the title is filled in here.
filters="${work}/filters.yaml" identity="${identity}" yq '
  (.jobs[] | select(.type == "alertFilter") | .alertFilters) = load(strenv(filters))
  | (.jobs[] | select(.parameters.reportTitle != null) | .parameters.reportTitle) |= sub("\$\{SCAN_IDENTITY\}"; strenv(identity))
' security/zap/plan.yaml > "${work}/plan.yaml"

# The API description (part d), with this session's CSRF token where ZAP's requests send it (X-CSRF-TOKEN) and the
# seeded data set's identifiers DastSessionIT wrote. A placeholder left unfilled would send a literal to every
# operation that names it, so it stops the run.
csrf="$(cat "target/dast/${identity}.csrf")"
cp security/zap/openapi.yaml "${work}/openapi.yaml"
while IFS='=' read -r key value; do
  sed -i "s|__${key}__|${value}|g" "${work}/openapi.yaml"
done < target/dast/api-values.txt
sed -i "s|__CSRF_TOKEN__|${csrf}|g" "${work}/openapi.yaml"
if left="$(grep -o -E '__[A-Z_]+__' "${work}/openapi.yaml" | sort -u | tr '\n' ' ')" && [ -n "${left}" ]; then
  echo "::error title=ZAP (${identity})::security/zap/openapi.yaml has placeholders nothing filled in: ${left}"
  exit 1
fi

# ZAP's authentication variables add the cookie to every request to localhost (the site is matched by host name; a
# host:port value matches nothing). Its own proxy listens on 18080, because the application holds 8080 on the host.
# The container is kept until ZAP's own log is copied out of it.
container="zap-${identity}"
docker rm --force "${container}" > /dev/null 2>&1 || true
# The heap in use (garbage included), sampled every 30 s while ZAP runs; the summary reports the peak.
(
  while sleep 30; do
    docker exec "${container}" jcmd 1 GC.heap_info 2> /dev/null | sed -n 's/.* used \([0-9]*\)K.*/\1/p' | head -1
  done
) > "${work}/heap-samples.txt" &
sampler=$!
status=0
ZAP_AUTH_HEADER=Cookie ZAP_AUTH_HEADER_VALUE="${cookie}" ZAP_AUTH_HEADER_SITE=localhost SCAN_IDENTITY="${identity}" \
  docker run --name "${container}" --network host \
  --env ZAP_AUTH_HEADER --env ZAP_AUTH_HEADER_VALUE --env ZAP_AUTH_HEADER_SITE --env SCAN_IDENTITY \
  --volume "${PWD}/${work}:/zap/wrk:rw" \
  "${ZAP_IMAGE}" zap.sh "-Xmx${heap}" -cmd -port 18080 -config insights.exitAuto=false -autorun /zap/wrk/plan.yaml \
  || status=$?
kill "${sampler}" 2> /dev/null || true
docker cp "${container}:/home/zap/.ZAP/zap.log" "${work}/zap.log" || echo "::warning::ZAP left no zap.log."
docker rm --force "${container}" > /dev/null || true
touch "${work}/zap.log"

still="$(curl -s -o /dev/null -w '%{http_code}' -H "Cookie: ${cookie}" http://localhost:8080/)"

# The session dies with this job's stack, but no report leaves the runner carrying it or its CSRF token.
session="${cookie#SESSION=}"
for value in "${session}" "${csrf}"; do
  # grep -l exits 1 when no report carries the value, which pipefail would turn into a failed scan.
  { grep -rlF "${value}" "${work}" || true; } | while read -r file; do
    sed -i "s|${value}|[removed]|g" "${file}"
  done
done

report="${work}/reports/zap-${identity}.json"
written=0
gating=0
stopped=""
if [ -s "${report}" ]; then
  written=1
  gating="$(jq '[.site[].alerts[] | select((.riskcode | tonumber) >= 2 and .confidence != "0")] | length' "${report}")"
  stopped="$(jq -r '.stoppingInsight // empty | "\(.key) at \(.statistic) (\(.reason))"' "${report}")"
fi
# The plan's active scan runs as several activeScan jobs, each with its own context and cap (#231). Each job's part of
# zap.log runs from its "Job activeScan started" line to the next job's. There, the scanner logs "completed host <site>
# in <seconds>s" for each host of the job's context when it ends, whether it finished or reached its cap: the job's
# time is the longest, and a host with no completion means no completion for the job ("-"). When the scanner overruns
# its cap, the Automation Framework terminates the job itself and the scanner logs no completion: that is a cut-off
# job too. dast-verdict.sh gets one time and one cap per job, in plan order.
active=""
cap=""
times=""
part="$(mktemp)"
count="$(yq '[.jobs[] | select(.type == "activeScan")] | length' security/zap/plan.yaml)"
for ((job = 0; job < count; job++)); do
  context="$(yq "[.jobs[] | select(.type == \"activeScan\")][${job}].parameters.context" security/zap/plan.yaml)"
  limit="$(yq "[.jobs[] | select(.type == \"activeScan\")][${job}].parameters.maxScanDurationInMins * 60" \
    security/zap/plan.yaml)"
  awk -v job="$((job + 1))" '/Job activeScan started/ { started++ } started == job' "${work}/zap.log" > "${part}"
  longest=""
  for host in $(context="${context}" yq '.env.contexts[] | select(.name == strenv(context)) | .urls[]
      | capture("^(?P<host>https?://[^/]+)").host' security/zap/plan.yaml | sort -u); do
    seconds="$({ grep -o -E "completed host ${host} in [0-9.]+s" "${part}" || true; } \
      | tail -1 | sed -E 's/.* in ([0-9.]+)s/\1/')"
    if [ -z "${seconds}" ]; then
      longest="-"
    elif [ "${longest}" != "-" ]; then
      longest="$(printf '%s\n%s\n' "${longest:-0}" "${seconds}" | sort -g | tail -1)"
    fi
  done
  if grep -q -F 'Job activeScan terminated' "${part}"; then
    longest="${limit}"
  fi
  active="${active:+${active} }${longest:--}"
  cap="${cap:+${cap} }${limit}"
  times="${times:+${times}; }$((job + 1)) of ${count} (${context}) ${longest:--} s of a ${limit} s cap"
done
rm -f "${part}"
skipped="$({ grep -c -F 'skipped plugin [exceeded max rule time]' "${work}/zap.log" || true; })"
peak="$(sort -n "${work}/heap-samples.txt" | tail -1)"

{
  echo "### ZAP as ${identity} (${mode}): alerts of Medium or higher"
  if [ "${written}" = "1" ]; then
    jq -r '[.site[].alerts[] | select((.riskcode | tonumber) >= 2 and .confidence != "0")]
      | if length == 0 then "None." else (.[] | "- **\(.riskdesc)** \(.name) (rule \(.pluginid)), \(.count) instance(s)") end' \
      "${report}"
    # Suppressed alerts stay in the report as False Positive (confidence 0); they do not gate, but they are listed.
    jq -r '.site[].alerts[] | select(.confidence == "0")
      | "- suppressed (security/zap/suppressions.yaml): \(.name) (rule \(.pluginid))"' "${report}"
  fi
  echo "Active scans (\"-\": no completion in zap.log): ${times}. Peak heap in use: $((${peak:-0} / 1024)) MiB of ${heap}."
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"

bash "$(dirname "$0")/dast-verdict.sh" "${mode}" "${identity}" "${still}" "${status}" "${written}" "${gating}" \
  "${stopped}" "${active}" "${cap}" "${skipped}"
