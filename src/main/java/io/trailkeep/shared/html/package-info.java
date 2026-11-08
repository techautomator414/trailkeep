// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Untrusted text to safe HTML (IMPLEMENTATION_PLAN A.2 #22, BUILD_PLAN §9). {@link
 * io.trailkeep.shared.html.SafeHtml} has two producers, each an allow-list sanitizer: {@link
 * io.trailkeep.shared.html.MarkdownRenderer}, which parses Markdown first (comments), and {@link
 * io.trailkeep.shared.html.StoredReportSanitizer}, which allows only report tables and case links (stored report
 * runs). The {@code tk:safe} template attribute, registered here as well, is the only way a template writes markup
 * unescaped, and it accepts nothing but {@code SafeHtml}. Every module may use the sanitizers and the type; no module
 * can build the type or read its markup.
 */
@NullMarked
package io.trailkeep.shared.html;

import org.jspecify.annotations.NullMarked;
