// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

/** Where an SLA's clock starts (Part A #8); stored by name in {@code SLA_POLICY.SLA_ANCHOR}. */
public enum SlaAnchor {
    /** End of the identified-on day in {@code app.timezone}. */
    IDENTIFIED_ON,
    /** The moment the case was opened. */
    OPENED_AT
}
