// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Attachment content on PostgreSQL ({@code BYTEA}); the Oracle run is {@link OracleAttachmentContentIT}. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class AttachmentContentIT extends AbstractAttachmentContentIT {}
