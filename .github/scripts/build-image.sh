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
