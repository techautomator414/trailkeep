// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Untrusted text to safe HTML (IMPLEMENTATION_PLAN A.2 #22, BUILD_PLAN §9). {@link
 * io.trailkeep.shared.html.MarkdownRenderer} is the only producer of {@link io.trailkeep.shared.html.SafeHtml}: it
 * parses Markdown and passes the result through an allow-list sanitizer. The {@code tk:safe} template attribute,
 * registered here as well, is the only way a template writes markup unescaped, and it accepts nothing but {@code
 * SafeHtml}. Every module may use the renderer and the type; no module can build the type or read its markup.
 */
@NullMarked
package io.trailkeep.shared.html;

import org.jspecify.annotations.NullMarked;
