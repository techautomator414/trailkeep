// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.html;

import org.jspecify.annotations.Nullable;

/**
 * Markup that has passed a sanitizer. Only {@link MarkdownRenderer} and {@link StoredReportSanitizer} can make one,
 * and only the {@code tk:safe} attribute can write one into a page, so a template cannot output unsanitized markup by
 * mistake. The markup is not
 * readable outside this package, and {@link #toString()} gives only its length, because it is built from text that
 * may hold PHI and must never reach a log line.
 */
public final class SafeHtml {

    private final String html;

    /**
     * Wraps sanitizer output. Package-private on purpose: nothing outside this package can assert that a string is
     * safe.
     *
     * @param html the sanitizer's output
     */
    SafeHtml(String html) {
        this.html = html;
    }

    /**
     * The sanitized markup, for {@code tk:safe}.
     *
     * @return the markup
     */
    String html() {
        return html;
    }

    /**
     * Whether nothing visible remains, for example after a comment of only stripped markup.
     *
     * @return {@code true} if the markup is blank
     */
    public boolean isBlank() {
        return html.isBlank();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof SafeHtml that && html.equals(that.html));
    }

    @Override
    public int hashCode() {
        return html.hashCode();
    }

    @Override
    public String toString() {
        return "SafeHtml[" + html.length() + " chars]";
    }
}
