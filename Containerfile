# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# Trailkeep runtime image on Red Hat UBI 9 (BUILD_PLAN §16). Build with either tool:
#   docker build -f Containerfile -t trailkeep .
#   buildah bud -f Containerfile -t trailkeep .
#
# Base images: CI resolves each tag to its digest and passes BUILDER_IMAGE / RUNTIME_IMAGE as
# "<repository>@sha256:<digest>" (main.yml, release.yml), so every published image is built from a pinned base and
# the digest is recorded in the build log and the SLSA provenance. The tag defaults below exist only for local builds;
# see the PR 12 "Plan deviations" note for why the digests are not literal in this file yet.
ARG BUILDER_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:latest
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:latest

# ---------------------------------------------------------------------------------------------------------------
FROM ${BUILDER_IMAGE} AS build

# The Maven wrapper verifies the SHA-256 of the .zip distribution, which needs unzip.
USER 0
RUN microdnf -y --nodocs --setopt=install_weak_deps=0 install unzip && microdnf clean all
USER 185

# COPY creates src/ owned by 185 (a WORKDIR-created directory would be root-owned and unwritable for target/).
WORKDIR /home/default
COPY --chown=185:0 . src/
WORKDIR /home/default/src

# Tests are not run here: the same commit has already passed `./mvnw verify` (ci.yml on main, the verify job in
# release.yml), and the integration tests need a container runtime that an image build does not have.
RUN ./mvnw -B -ntp -DskipTests package \
 && cp target/trailkeep-*.jar /home/default/app.jar \
 && java -Djarmode=tools -jar /home/default/app.jar extract --layers --destination /home/default/extracted

# ---------------------------------------------------------------------------------------------------------------
FROM ${RUNTIME_IMAGE}

ARG VERSION=0.0.0-dev
ARG REVISION=unknown
ARG CREATED=1970-01-01T00:00:00Z

LABEL org.opencontainers.image.title="Trailkeep" \
      org.opencontainers.image.description="Case tracking with SLA monitoring, closure approval and an audit trail." \
      org.opencontainers.image.url="https://github.com/techautomator414/trailkeep" \
      org.opencontainers.image.source="https://github.com/techautomator414/trailkeep" \
      org.opencontainers.image.documentation="https://github.com/techautomator414/trailkeep/tree/main/deploy" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.vendor="Trailkeep contributors" \
      io.trailkeep.third-party-notice="The Oracle JDBC driver in this image is redistributed under the Oracle Free Use Terms and Conditions (FUTC). See /licenses and the release THIRD-PARTY.txt." \
      name="trailkeep" \
      summary="Trailkeep case tracking" \
      io.k8s.display-name="Trailkeep" \
      io.openshift.expose-services="8080:http,8081:management"

WORKDIR /deployments

# Least-changing layers first, so a code-only change re-pushes only the last layer.
COPY --from=build --chown=185:0 /home/default/extracted/dependencies/ ./
COPY --from=build --chown=185:0 /home/default/extracted/spring-boot-loader/ ./
COPY --from=build --chown=185:0 /home/default/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=185:0 /home/default/extracted/application/ ./
COPY --from=build --chown=185:0 /home/default/src/LICENSE /home/default/src/NOTICE /licenses/

# UID 185 is the unprivileged user UBI's OpenJDK images provide. OpenShift's restricted-v2 SCC replaces it with an
# arbitrary UID in group 0, which can still read these files.
USER 185

EXPOSE 8080 8081

# Exec form: no shell is involved at runtime. The root filesystem is mounted read-only in deploy/, so the temp
# directory, the multipart location and Tomcat's base directory are emptyDir volumes.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/deployments/app.jar"]
