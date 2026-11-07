// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.time;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider.ColumnNames;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock over the {@code SHEDLOCK} table (V6), so a cluster-wide job such as the SLA evaluator runs on one replica
 * at a time (BUILD_PLAN §6, §7.3). Locks use database time ({@code usingDbTime()}), the one place the database clock
 * counts (BUILD_PLAN §9 "Audit"), so replicas with drifting clocks still agree on when a lock expires. Each job names
 * its own {@code lockAtMostFor}; the default here only bounds a job that forgets to.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT14M")
public class SchedulerLockConfig {

    /**
     * The JDBC lock provider on {@code SHEDLOCK}.
     *
     * @param dataSource the application's data source
     * @return the lock provider
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("SHEDLOCK")
                .withColumnNames(new ColumnNames("NAME", "LOCK_UNTIL", "LOCKED_AT", "LOCKED_BY"))
                .usingDbTime()
                .build());
    }
}
