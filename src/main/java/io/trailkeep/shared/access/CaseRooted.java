// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring Data repository over a case-rooted table (cases, comments, attachments, update and closure
 * requests, SLA rows, search rows, audit). Enforced by ArchUnit (ADR-0005):
 *
 * <ul>
 *   <li>the repository extends {@code org.springframework.data.repository.Repository} and no other Spring Data
 *       interface, so there are no inherited {@code findById}/{@code findAll} methods;
 *   <li>every method it exposes, including custom-fragment methods, takes a {@link CaseScope} and applies
 *       {@link CaseScope#toPredicate};
 *   <li>it is package-private and injected only into classes annotated {@link CaseAccessPath}.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CaseRooted {}
