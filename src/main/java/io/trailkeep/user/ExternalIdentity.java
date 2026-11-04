// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

/**
 * A person as the identity provider asserts them at login.
 *
 * @param subject the provider's stable subject ({@code sub}); stored as {@code APP_USER.EXTERNAL_ID}
 * @param displayName the name to show
 * @param email the email address used for notifications
 */
public record ExternalIdentity(String subject, String displayName, String email) {}
