// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test that needs a Docker daemon (Testcontainers). Without Docker the test is <em>skipped with a reason</em>
 * on a developer machine, but <em>fails</em> when the {@code CI} environment variable is {@code true}, so a CI
 * runner that lost Docker can never turn the database gates green by skipping them.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(DockerAvailableCondition.class)
public @interface RequiresDocker {}
