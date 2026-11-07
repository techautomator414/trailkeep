// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.shared.access.CaseScope;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A person's in-app alerts (BUILD_PLAN §4.7 "email + in-app list"), written by {@link NotificationService#notifyUser}
 * with the mail for the same event. Every method takes the person's own id and touches only their alerts: another
 * person's alert, or an unknown id, is {@link AlertNotFoundException} (404), so an alert's existence stays private.
 * Reading never marks anything read; only {@link #markRead} does. A list or count takes the person's {@link
 * CaseScope}, so an alert whose case the person can no longer read is neither shown nor counted.
 */
public interface UserAlertService {

    /** Alerts per page. */
    int PAGE_SIZE = 50;

    /**
     * One alert as the Alerts page shows it.
     *
     * @param id the alert's id, which the mark-read and delete forms name
     * @param message what happened; never a description or subject text
     * @param link the path of the page it is about
     * @param at when it happened
     * @param local the same moment in {@code app.timezone}, to the minute
     * @param read whether the person marked it read
     */
    record Alert(long id, String message, String link, Instant at, LocalDateTime local, boolean read) {}

    /**
     * One page of a person's alerts, newest first.
     *
     * @param page the page shown, from 1
     * @param pages how many pages there are, at least 1
     * @param total how many alerts the person has
     * @param alerts the alerts of the page
     */
    record AlertPage(int page, int pages, long total, List<Alert> alerts) {

        /**
         * Copies the list, so the record holds nothing mutable.
         *
         * @param page the page shown
         * @param pages how many pages there are
         * @param total how many alerts there are
         * @param alerts the alerts of the page
         */
        public AlertPage {
            alerts = List.copyOf(alerts);
        }
    }

    /**
     * A page of the person's alerts, newest first; a page past the last shows the last.
     *
     * @param scope the person's scope: an alert about a case the scope no longer reads is not listed (an alert
     *     about no case always is), and is not counted in {@code total}
     * @param userId the person
     * @param page the page asked for, from 1
     * @return the page
     */
    AlertPage alerts(CaseScope scope, long userId, int page);

    /**
     * How many of the person's alerts are unread, for the navigation's badge.
     *
     * @param scope the person's scope, applied as for {@link #alerts}, so the badge and the page agree
     * @param userId the person
     * @return the count
     */
    long unread(CaseScope scope, long userId);

    /**
     * Marks one of the person's alerts read; marking a read alert again changes nothing.
     *
     * @param userId the person
     * @param alertId the alert
     * @throws AlertNotFoundException if it is not one of the person's alerts
     */
    void markRead(long userId, long alertId);

    /**
     * Deletes one of the person's alerts.
     *
     * @param userId the person
     * @param alertId the alert
     * @throws AlertNotFoundException if it is not one of the person's alerts
     */
    void delete(long userId, long alertId);
}
