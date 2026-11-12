// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.hibernate.cfg.MappingSettings;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Persistence settings that depend on {@code app.db}: the optional default schema (for Hibernate's mapped tables and,
 * through {@code SessionSchema}, for every connection), and a start-up guard that the
 * configured vendor (which chose the Flyway placeholders and the dialect version) is the vendor actually connected
 * (which chose the {@code {vendor}} migration folder).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
public class PersistenceConfig {

    /**
     * Sets {@code hibernate.default_schema} only when {@code app.db.schema} is non-blank; an empty value would make
     * Hibernate qualify every table with an empty schema.
     *
     * @param properties the database settings
     * @return the customizer
     */
    @Bean
    HibernatePropertiesCustomizer defaultSchemaCustomizer(DatabaseProperties properties) {
        return hibernateProperties -> {
            String schema = properties.schema();
            if (StringUtils.hasText(schema)) {
                hibernateProperties.put(MappingSettings.DEFAULT_SCHEMA, schema);
            }
        };
    }

    /**
     * Makes {@code app.db.schema}, when set, every pooled connection's default schema ({@link SessionSchema}). Static,
     * as a post-processor must be, so it reads {@code app.db} from the environment itself.
     *
     * @param environment the environment
     * @return the post-processor
     */
    @Bean
    static BeanPostProcessor sessionSchema(Environment environment) {
        return new SessionSchema(environment);
    }

    /**
     * Refuses to migrate when {@code app.db.vendor} disagrees with the connected database, before any script runs.
     *
     * @param properties the database settings
     * @return the customizer
     */
    @Bean
    FlywayConfigurationCustomizer vendorGuard(DatabaseProperties properties) {
        return configuration -> {
            DataSource dataSource = configuration.getDataSource();
            String product = productName(dataSource);
            if (!properties.vendor().matches(product)) {
                throw new IllegalStateException("app.db.vendor is " + properties.vendor()
                        + " but the DataSource is " + product
                        + "; activate the matching 'oracle' or 'postgresql' profile");
            }
        };
    }

    private static String productName(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the database product name", e);
        }
    }
}
