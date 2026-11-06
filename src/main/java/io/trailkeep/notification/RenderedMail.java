// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

/**
 * A mail as its templates rendered it.
 *
 * @param subject the subject line
 * @param text the plain-text body
 * @param html the HTML body
 */
record RenderedMail(String subject, String text, String html) {

    /** Never the bodies (Part B "Logging"). */
    @Override
    public String toString() {
        return "RenderedMail[" + subject.length() + " characters of subject]";
    }
}
