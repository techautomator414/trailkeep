// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * The one CSV writer every export uses ({@link io.trailkeep.shared.csv.CsvFormat}): quoted cells, CRLF line ends and
 * formula defusing, so no module hand-rolls its own.
 */
@NullMarked
package io.trailkeep.shared.csv;

import org.jspecify.annotations.NullMarked;
