// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders and inserts outbox rows. {@code MANDATORY} propagation makes "in the caller's transaction" a rule rather
 * than a habit: called without one, it refuses instead of committing a mail on its own.
 */
@Service
class DefaultNotificationService implements NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultNotificationService.class);

    private final EntityManager entityManager;
    private final MailTemplates templates;
    private final UserDirectoryService users;
    private final InstantSource clock;
    private final String baseUrl;

    DefaultNotificationService(
            EntityManager entityManager,
            MailTemplates templates,
            UserDirectoryService users,
            InstantSource clock,
            AppProperties app) {
        this.entityManager = entityManager;
        this.templates = templates;
        this.users = users;
        this.clock = clock;
        String base = app.baseUrl().toString();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean notifyUser(
            long userId, String templateId, Map<String, ?> model, @Nullable Long caseId, String path) {
        return tell(userId, templateId, model, caseId, null, path);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean notifyOfReport(long userId, String templateId, Map<String, ?> model, long reportRunId, String path) {
        return tell(userId, templateId, model, null, reportRunId, path);
    }

    private boolean tell(
            long userId,
            String templateId,
            Map<String, ?> model,
            @Nullable Long caseId,
            @Nullable Long reportRunId,
            String path) {
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("a deep link is an absolute path on this application");
        }
        if (users.permissionsOf(userId).disabled()) {
            return false;
        }
        Map<String, Object> withLink = new HashMap<>(model);
        withLink.put("link", baseUrl + path);
        UserAlert alert =
                new UserAlert(userId, templates.renderAlert(templateId, withLink), path, caseId, clock.instant());
        entityManager.persist(alert);
        LOG.info("Alert {} written for user {}", alert.getId(), userId);
        Optional<String> address = users.mailAddress(userId);
        if (address.isEmpty()) {
            LOG.info("User {} has no mail address; no mail queued", userId);
            return false;
        }
        queue(address.get(), templateId, withLink, caseId, reportRunId);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long enqueue(String recipient, String templateId, Map<String, ?> model, @Nullable Long caseId) {
        return queue(recipient, templateId, model, caseId, null);
    }

    private long queue(
            String recipient,
            String templateId,
            Map<String, ?> model,
            @Nullable Long caseId,
            @Nullable Long reportRunId) {
        String address = recipient.strip();
        if (address.isEmpty() || address.length() > MAX_RECIPIENT || address.indexOf('@') < 1) {
            throw new IllegalArgumentException("not a recipient address");
        }
        NotificationOutbox row = new NotificationOutbox(
                address, templateId, templates.render(templateId, model), caseId, reportRunId, clock.instant());
        entityManager.persist(row);
        long id = Objects.requireNonNull(row.getId());
        // Never the address, the template's model or the bodies (Part B "Logging").
        LOG.info("Mail {} queued", id);
        return id;
    }
}
