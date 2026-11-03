// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class allowed to hold a {@link CaseRooted} repository as a field, constructor parameter or method
 * parameter. Such a class is a reviewed access path: it obtains a {@link CaseScope} for the current principal (or
 * {@link CaseScope#system()} for a job) and passes it to every repository call. ArchUnit rejects any other class
 * that references a {@code @CaseRooted} repository this way.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CaseAccessPath {}
