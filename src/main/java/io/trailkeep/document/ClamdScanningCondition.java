// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches unless {@code app.attachments.scanning} is {@code external} (ADR-0013). The value is bound the way
 * {@link AttachmentProperties} binds it, so the condition and the properties cannot disagree: an unset value is
 * {@link ScanMode#CLAMD}, and an unknown one fails startup.
 */
final class ClamdScanningCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ScanMode mode = Binder.get(context.getEnvironment())
                .bind(AttachmentProperties.SCANNING, ScanMode.class)
                .orElse(ScanMode.CLAMD);
        return mode == ScanMode.CLAMD
                ? ConditionOutcome.match(AttachmentProperties.SCANNING + " is clamd")
                : ConditionOutcome.noMatch(AttachmentProperties.SCANNING + " is external: no clamd client");
    }
}
