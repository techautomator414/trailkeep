// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import jakarta.persistence.EntityManager;
import java.time.InstantSource;
import java.util.Map;
import java.util.Objects;
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
    private final InstantSource clock;

    DefaultNotificationService(EntityManager entityManager, MailTemplates templates, InstantSource clock) {
        this.entityManager = entityManager;
        this.templates = templates;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long enqueue(String recipient, String templateId, Map<String, ?> model, @Nullable Long caseId) {
        String address = recipient.strip();
        if (address.isEmpty() || address.length() > MAX_RECIPIENT || address.indexOf('@') < 1) {
            throw new IllegalArgumentException("not a recipient address");
        }
        NotificationOutbox row = new NotificationOutbox(
                address, templateId, templates.render(templateId, model), caseId, clock.instant());
        entityManager.persist(row);
        long id = Objects.requireNonNull(row.getId());
        // Never the address, the template's model or the bodies (Part B "Logging").
        LOG.info("Mail {} queued", id);
        return id;
    }
}
