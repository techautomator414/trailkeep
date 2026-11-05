// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds and validates {@code app.attachments.*} at startup. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentProperties.class)
class AttachmentConfig {}
