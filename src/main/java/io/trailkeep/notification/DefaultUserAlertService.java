// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.shared.config.AppProperties;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists, marks read and deletes a person's alerts; every lookup carries the person's id. */
@Service
@Transactional
class DefaultUserAlertService implements UserAlertService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultUserAlertService.class);

    private final UserAlertRepository alerts;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultUserAlertService(UserAlertRepository alerts, InstantSource clock, AppProperties app) {
        this.alerts = alerts;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    @Transactional(readOnly = true)
    public AlertPage alerts(long userId, int page) {
        long total = alerts.countByUserId(userId);
        int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int shown = Math.min(Math.max(page, 1), pages);
        return new AlertPage(
                shown,
                pages,
                total,
                alerts.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(shown - 1, PAGE_SIZE)).stream()
                        .map(alert -> new Alert(
                                Objects.requireNonNull(alert.getId()),
                                alert.getMessage(),
                                alert.getLink(),
                                alert.getCreatedAt(),
                                LocalDateTime.ofInstant(alert.getCreatedAt(), zone)
                                        .truncatedTo(ChronoUnit.MINUTES),
                                alert.getReadAt() != null))
                        .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long unread(long userId) {
        return alerts.countByUserIdAndReadAtIsNull(userId);
    }

    @Override
    public void markRead(long userId, long alertId) {
        alerts.findByIdAndUserId(alertId, userId)
                .orElseThrow(AlertNotFoundException::new)
                .markRead(clock.instant());
        LOG.info("Alert {} marked read", alertId);
    }

    @Override
    public void delete(long userId, long alertId) {
        alerts.delete(alerts.findByIdAndUserId(alertId, userId).orElseThrow(AlertNotFoundException::new));
        LOG.info("Alert {} deleted", alertId);
    }
}
