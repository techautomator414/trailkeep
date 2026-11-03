// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(InstantSourceConfig.class)
class InstantSourceConfigTest {

    private static final Instant FIXED = Instant.parse("2026-01-02T03:04:05Z");

    @TestBean
    private InstantSource instantSource;

    @Autowired
    private InstantSource injected;

    static InstantSource instantSource() {
        return InstantSource.fixed(FIXED);
    }

    @Test
    void applicationTimeComesFromTheUtcSystemClock() {
        InstantSource source = new InstantSourceConfig().instantSource();

        assertThat(source).isInstanceOf(Clock.class);
        assertThat(((Clock) source).getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void aTestCanOverrideTheInstantSourceBean() {
        assertThat(injected.instant()).isEqualTo(FIXED);
    }
}
