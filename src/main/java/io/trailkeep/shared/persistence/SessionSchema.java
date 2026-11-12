// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Makes {@code app.db.schema} every pooled connection's default schema, so that a user who does not own the tables
 * (the runtime, purge and import users, BUILD_PLAN §8 rule 7) finds them by their plain names. Hibernate already
 * qualifies the tables it maps ({@code hibernate.default_schema}); this covers every statement it does not write: the
 * native {@code *JdbcDao} SQL, Spring Session's {@code SPRING_SESSION}, ShedLock's {@code SHEDLOCK}. One session
 * setting rather than a prefix in each statement, so a statement added later cannot forget it.
 *
 * <p>The pool runs, on each new connection, {@code ALTER SESSION SET CURRENT_SCHEMA = <schema>} on Oracle or
 * {@code SET search_path TO <schema>} on PostgreSQL (Hikari {@code connectionInitSql}). Neither statement takes a bind
 * parameter, so the schema is spliced in, and only a plain identifier is accepted ({@link #IDENTIFIER}; {@code
 * DatabaseProperties} rejects anything else at start-up, and so does this class). A blank or absent schema changes
 * nothing. A connection-init statement configured some other way is not overwritten: the start fails and says so.
 */
final class SessionSchema implements BeanPostProcessor {

    /** A plain, unquoted SQL identifier, short enough for both vendors. */
    static final String IDENTIFIER_REGEX = "[A-Za-z][A-Za-z0-9_]{0,62}";

    private static final Pattern IDENTIFIER = Pattern.compile(IDENTIFIER_REGEX);

    private final Environment environment;

    SessionSchema(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource pool) {
            Binder binder = Binder.get(environment);
            String schema = binder.bind("app.db.schema", String.class)
                    .map(String::strip)
                    .orElse("");
            if (StringUtils.hasText(schema)) {
                DatabaseVendor vendor = binder.bind("app.db.vendor", DatabaseVendor.class)
                        .orElseThrow(() -> new IllegalStateException("app.db.schema is set but app.db.vendor is not"));
                pool.setConnectionInitSql(initSql(vendor, schema, pool.getConnectionInitSql()));
            }
        }
        return bean;
    }

    /**
     * The statement that makes {@code schema} a new connection's default schema.
     *
     * @param vendor the database vendor
     * @param schema the schema, a plain identifier
     * @param configured a connection-init statement already set on the pool, or {@code null}
     * @return the statement
     * @throws IllegalStateException if the schema is not a plain identifier or another statement is configured
     */
    static String initSql(DatabaseVendor vendor, String schema, @Nullable String configured) {
        if (!IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalStateException("app.db.schema must be a plain identifier (" + IDENTIFIER_REGEX + ")");
        }
        if (StringUtils.hasText(configured)) {
            throw new IllegalStateException("app.db.schema sets the pool's connection-init SQL, but"
                    + " spring.datasource.hikari.connection-init-sql is already set; remove one of them");
        }
        return switch (vendor) {
            case ORACLE -> "ALTER SESSION SET CURRENT_SCHEMA = " + schema;
            case POSTGRESQL -> "SET search_path TO " + schema;
        };
    }
}
