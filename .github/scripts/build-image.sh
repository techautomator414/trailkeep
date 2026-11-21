#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# Builds the Containerfile from base images pinned by digest at build time.
# Usage: build-image.sh <image-ref> <version>
# Writes the resolved base digests to target/image/base-images.txt for the evidence pack.
set -euo pipefail

image_ref="$1"
version="$2"

builder_repo="registry.access.redhat.com/ubi9/openjdk-21"
runtime_repo="registry.access.redhat.com/ubi9/openjdk-21-runtime"
base_tag="${UBI_BASE_TAG:-latest}"

# The Maven wrapper's own download of the distribution inside the image build failed when Maven Central refused the
# runner for longer than any retry budget (issue 240). The distribution is pinned by distributionSha256Sum, so a copy
# from anywhere is as trustworthy as a download: it is put in the build context here, in the layout of a Maven
# repository, and the Containerfile points MVNW_REPOURL at it. The wrapper still "downloads" it, from a file: URL, and
# still verifies the checksum. A copy already in place (restored by the workflow's cache step) is used if it verifies;
# otherwise one download is made, from Central and then from the Apache archive, and verified before use.
seed_maven_distribution() {
  local props=.mvn/wrapper/maven-wrapper.properties url sum rel dest
  url="$(sed -n 's/^distributionUrl=//p' "$props")"
  sum="$(sed -n 's/^distributionSha256Sum=//p' "$props")"
  rel="${url#*/org/apache/maven/}"
  dest="target/image/maven-seed/org/apache/maven/${rel}"
  if [ -f "$dest" ] && echo "${sum}  ${dest}" | sha256sum -c - >/dev/null 2>&1; then
    echo "Maven distribution seed: using the verified copy at ${dest}"
    return 0
  fi
  rm -rf target/image/maven-seed
  mkdir -p "$(dirname "$dest")"
  local mirror="https://archive.apache.org/dist/maven/maven-3/${rel#apache-maven/}"
  mirror="${mirror/\/apache-maven-/\/binaries\/apache-maven-}"
  for src in "$url" "$mirror"; do
    echo "Maven distribution seed: downloading ${src}"
    if curl -fsSL --retry 5 --retry-max-time 240 --retry-all-errors -o "$dest" "$src" \
      && echo "${sum}  ${dest}" | sha256sum -c - >/dev/null 2>&1; then
      echo "Maven distribution seed: checksum verified"
      return 0
    fi
    rm -f "$dest"
  done
  echo "Maven distribution seed: could not obtain a copy that matches distributionSha256Sum" >&2
  return 1
}

resolve() {
  skopeo inspect --retry-times 3 --format '{{.Digest}}' "docker://$1:${base_tag}"
}

builder_digest="$(resolve "$builder_repo")"
runtime_digest="$(resolve "$runtime_repo")"
builder_image="${builder_repo}@${builder_digest}"
runtime_image="${runtime_repo}@${runtime_digest}"

mkdir -p target/image
{
  echo "builder ${builder_repo}:${base_tag} -> ${builder_image}"
  echo "runtime ${runtime_repo}:${base_tag} -> ${runtime_image}"
} | tee target/image/base-images.txt

seed_maven_distribution

created="$(git log -1 --format=%cI)"

docker build \
  --file Containerfile \
  --build-arg "BUILDER_IMAGE=${builder_image}" \
  --build-arg "RUNTIME_IMAGE=${runtime_image}" \
  --build-arg "VERSION=${version}" \
  --build-arg "REVISION=$(git rev-parse HEAD)" \
  --build-arg "CREATED=${created}" \
  --tag "${image_ref}" \
  .
