// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * Binds and validates {@code app.attachments.*} at startup, and checks that the multipart request limit leaves room
 * for the largest file: a request is the file plus the form's CSRF token, its other fields and the multipart framing,
 * so {@code spring.servlet.multipart.max-request-size} must be at least {@code app.attachments.max-bytes} plus
 * {@link #MULTIPART_HEADROOM}. Otherwise a file the form advertises as allowed is refused by the container.
 * {@code max-file-size} follows {@code max-bytes} by placeholder; {@code max-request-size} is a separate setting, so a
 * deployment that raises {@code max-bytes} raises it too or does not start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentProperties.class)
class AttachmentConfig implements InitializingBean {

    /** What a request carries beyond the file itself. */
    static final DataSize MULTIPART_HEADROOM = DataSize.ofMegabytes(1);

    private static final String REQUEST_SIZE = "spring.servlet.multipart.max-request-size";

    /** Spring Boot's own default for {@code max-request-size} when the property is unset. */
    private static final DataSize DEFAULT_REQUEST_SIZE = DataSize.ofMegabytes(10);

    private final AttachmentProperties properties;
    private final Environment environment;

    AttachmentConfig(AttachmentProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        BindResult<DataSize> configured = Binder.get(environment).bind(REQUEST_SIZE, DataSize.class);
        DataSize requestSize = configured.isBound() ? configured.get() : DEFAULT_REQUEST_SIZE;
        long needed = properties.maxBytes().toBytes() + MULTIPART_HEADROOM.toBytes();
        // A negative limit means unlimited to the container.
        if (!requestSize.isNegative() && requestSize.toBytes() < needed) {
            throw new IllegalStateException(
                    "app.attachments.max-bytes is " + properties.maxBytes().toBytes()
                            + " bytes; with " + MULTIPART_HEADROOM.toBytes()
                            + " bytes of multipart headroom a request needs " + needed + " bytes, but " + REQUEST_SIZE
                            + " is " + requestSize.toBytes() + " bytes. Raise " + REQUEST_SIZE + " or lower"
                            + " app.attachments.max-bytes.");
        }
    }
}
