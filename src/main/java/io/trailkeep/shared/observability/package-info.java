// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Logging correlation and the operational conventions that go with it (BUILD_PLAN §16, Part B "Logging").
 *
 * <p><b>Logging.</b> {@link io.trailkeep.shared.observability.RequestIdFilter} sets the request id; the other
 * {@link io.trailkeep.shared.observability.MdcKeys} are filled by the modules that own them. Lines are JSON in the
 * {@code prod}, {@code purge} and {@code importer} profiles and plain text elsewhere ({@code logback-spring.xml}).
 * Request bodies, descriptions, subjects, restricted attachment names and email addresses are never logged.
 *
 * <p><b>Health probe groups.</b> The {@code liveness} and {@code readiness} groups in {@code application.yml} list
 * their members explicitly instead of inheriting "all indicators". A Kubernetes probe acts on one pod, so it must
 * only see that pod's own state. Cluster-level indicators, such as the last run of a scheduled job that executes on
 * whichever replica holds the lock, would fail on every replica at once and make the kubelet restart healthy pods
 * (Part A #30). Enumerating members means such an indicator is excluded by construction; it still appears in the
 * aggregate {@code /actuator/health} and as a metric.
 */
@NullMarked
package io.trailkeep.shared.observability;

import org.jspecify.annotations.NullMarked;
