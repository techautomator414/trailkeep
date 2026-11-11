// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.util.regex.Pattern;
import org.owasp.html.Encoding;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Legacy text on its way into Trailkeep (design §6.4, R9): one normalisation every value passes, then the format rules,
 * which differ by target. A comment holds Markdown that is rendered and sanitized on display; a description is plain
 * text shown with {@code th:text}. Nothing is ever stored as markup.
 */
final class LegacyText {

    /** How the legacy system stored a text value ({@code text: {format: …}}). */
    enum Format {
        /** Plain text: shown as typed, so Markdown-escaped when it becomes a comment. */
        PLAIN,
        /** Markdown: stored as is; a comment's sanitizer makes HTML in it inert on display. */
        MARKDOWN,
        /** HTML: reduced to its text with an empty sanitizer policy, then treated as plain text. */
        HTML
    }

    /** A value after normalisation, and whether normalisation changed it ({@code TEXT_NORMALISED}, a WARNING). */
    record Normalised(String value, boolean changed) {}

    /** No element survives: only the text between the tags. */
    private static final PolicyFactory TEXT_ONLY = new HtmlPolicyBuilder().toFactory();

    /** The characters Markdown gives a meaning anywhere in a line. */
    private static final Pattern INLINE = Pattern.compile("([\\\\*_`#\\[\\]<>|])");

    /** A line that Markdown would read as a list item: {@code 1.}, {@code -} or {@code +} after optional spaces. */
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^(\\s*)(\\d+)\\.|(?m)^(\\s*)([-+])");

    private LegacyText() {}

    /**
     * The normalisation of §6.4: drops {@code U+0000}; replaces lone surrogates with {@code U+FFFD}; drops C0 controls
     * except tab and line breaks; turns {@code \r\n} and a lone {@code \r} into {@code \n}.
     *
     * @param text the value as read
     * @return the normalised value
     */
    static Normalised normalise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (Character.isHighSurrogate(c) && Character.isLowSurrogate(next)) {
                out.append(c).append(next);
                i++;
            } else if (Character.isSurrogate(c)) {
                out.append('�');
            } else if (c == '\r') {
                out.append('\n');
                i += next == '\n' ? 1 : 0;
            } else if (kept(c)) {
                out.append(c);
            }
        }
        String value = out.toString();
        return new Normalised(value, !value.equals(text));
    }

    /** Not a C0 control, or a tab or line feed, which are kept. */
    private static boolean kept(char c) {
        return c >= 0x20 || c == '\t' || c == '\n';
    }

    /**
     * The text as an imported comment holds it (design §6.4 table, R9).
     *
     * @param text normalised text
     * @param format its legacy format
     * @return Markdown that shows plain and HTML text as typed, and Markdown as written
     */
    static String forComment(String text, Format format) {
        return switch (format) {
            case PLAIN -> escapeMarkdown(text);
            case MARKDOWN -> text;
            case HTML -> escapeMarkdown(textOf(text));
        };
    }

    /**
     * The text as a description holds it: never escaped, because the description is shown with {@code th:text}.
     *
     * @param text normalised text
     * @param format its legacy format
     * @return plain text
     */
    static String forDescription(String text, Format format) {
        return format == Format.HTML ? textOf(text) : text;
    }

    /** Markdown-escapes plain text: a backslash before each inline marker and before a leading list marker. */
    static String escapeMarkdown(String text) {
        String inline = INLINE.matcher(text).replaceAll("\\\\$1");
        return LIST_MARKER
                .matcher(inline)
                .replaceAll(match -> match.group(2) != null
                        ? match.group(1) + match.group(2) + "\\\\."
                        : match.group(3) + "\\\\" + match.group(4));
    }

    /** The text of an HTML fragment: the sanitizer drops every element, then its entities are decoded. */
    private static String textOf(String html) {
        return Encoding.decodeHtml(TEXT_ONLY.sanitize(html), false);
    }
}
