// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * In-app alerts, always looked up with their person. Not case-rooted: a person reads only their own. Lists and counts
 * go through {@link UserAlertQueries}, which applies the person's case scope.
 */
interface UserAlertRepository extends Repository<UserAlert, Long> {

    UserAlert save(UserAlert alert);

    void delete(UserAlert alert);

    Optional<UserAlert> findByIdAndUserId(Long id, long userId);
}
