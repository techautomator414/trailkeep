// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The comment renderer against the XSS corpus in {@code src/test/resources/xss/corpus.txt} (WP-13 acceptance), and
 * the Markdown subset it is meant to keep. Each payload goes through the whole pipeline as Markdown and, separately,
 * straight into the sanitizer as raw HTML. The output is then read as the browser would read it: every tag must be
 * one of the allowed formatting elements, links may carry only an absolute {@code http}, {@code https} or {@code
 * mailto} target and the fixed {@code rel}, no other attribute may appear, no {@code <} or {@code >} may survive
 * outside a tag, and sanitizing the output again must change nothing.
 */
class MarkdownRendererTest {

    private static final Set<String> ELEMENTS =
            Set.of("p", "br", "strong", "em", "ul", "ol", "li", "blockquote", "pre", "code", "a");

    /** A tag as the sanitizer writes them: lower-case names, every attribute double-quoted. */
    private static final Pattern TAG = Pattern.compile("<(/?)([a-z0-9]+)((?: [a-z]+=\"[^\"<>]*\")*) ?(/?)>");

    private static final Pattern ATTRIBUTE = Pattern.compile(" ([a-z]+)=\"([^\"]*)\"");

    private static final Pattern LINK_TARGET = Pattern.compile("(?i)(?:https?|mailto):[^\\s\\p{Cntrl}]+");

    private static final Pattern CHARACTER_REFERENCE = Pattern.compile("&(#[0-9]+|#x[0-9a-fA-F]+|amp|lt|gt|quot);");

    /**
     * The empty comment the sanitizer puts between two opening braces, so client-side template syntax cannot form.
     * Inert: it is the only comment the sanitizer ever writes, and it holds nothing.
     */
    private static final String TEMPLATE_BREAK = "<!-- -->";

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    /** The payloads, each named by a printable, shortened copy so test reports stay valid XML. */
    static Stream<Named<String>> corpus() {
        return payloads().stream().map(payload -> Named.of(printable(payload), payload));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corpus")
    void everyPayloadRendersAsMarkdownWithNothingExecutable(String payload) {
        assertInert(renderer.render(payload).html(), payload);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corpus")
    void everyPayloadSanitizesAsRawHtmlWithNothingExecutable(String payload) {
        assertInert(MarkdownRenderer.sanitize(payload), payload);
    }

    @Test
    void theCorpusIsSubstantial() {
        assertThat(payloads()).hasSizeGreaterThan(200).doesNotHaveDuplicates();
    }

    /** What the Markdown subset keeps; the exact markup, so a library upgrade that changes it is noticed. */
    @Nested
    class Formatting {

        @Test
        void boldItalicAndCodeAreKept() {
            assertThat(html("**bold** and *italic* and `code`"))
                    .isEqualTo("<p><strong>bold</strong> and <em>italic</em> and <code>code</code></p>\n");
        }

        @Test
        void listsAreKeptWithTheirStartNumber() {
            assertThat(html("- one\n- two\n\n3. three\n4. four")).isEqualTo("""
                            <ul><li>one</li><li>two</li></ul>
                            <ol start="3"><li>three</li><li>four</li></ol>
                            """);
        }

        @Test
        void quotesAndFencedCodeAreKeptWithoutClasses() {
            assertThat(html("> quote\n\n```js\ncode <b>\n```")).isEqualTo("""
                            <blockquote>
                            <p>quote</p>
                            </blockquote>
                            <pre><code>code &lt;b&gt;
                            </code></pre>
                            """);
        }

        @Test
        void lineBreaksAreKept() {
            assertThat(html("first\nsecond")).isEqualTo("<p>first<br />\nsecond</p>\n");
        }

        @Test
        void absoluteLinksGetTheFixedRelAndLoseTheirTitle() {
            assertThat(html("[site](https://example.test/a?b=1&c=2 \"t\") and <mailto:a@example.test>"))
                    .isEqualTo("<p><a href=\"https://example.test/a?b&#61;1&amp;c&#61;2\""
                            + " rel=\"noopener noreferrer nofollow\">site</a> and"
                            + " <a href=\"mailto:a&#64;example.test\" rel=\"noopener noreferrer nofollow\">"
                            + "mailto:a&#64;example.test</a></p>\n");
        }

        @Test
        void unsafeAndRelativeLinksKeepOnlyTheirText() {
            assertThat(html("[a](javascript:alert(1)) [b](/cases) [c](//elsewhere.test)"))
                    .isEqualTo("<p>a b c</p>\n");
        }

        @Test
        void headingsStayTextSoCommentsAddNoHeadingToThePage() {
            assertThat(html("# Heading\nSetext\n==="))
                    .isEqualTo("<p># Heading<br />\nSetext<br />\n&#61;&#61;&#61;</p>\n");
        }

        @Test
        void imagesRenderAsTheirAltText() {
            assertThat(html("![alt *text*](https://example.test/p.png)")).isEqualTo("<p>alt <em>text</em></p>\n");
        }

        @Test
        void rawHtmlIsShownAsTextNotDropped() {
            assertThat(html("<b>bold?</b>")).isEqualTo("<p>&lt;b&gt;bold?&lt;/b&gt;</p>\n");
        }

        @Test
        void onlyStrippedMarkupIsBlank() {
            assertThat(renderer.render("").isBlank()).isTrue();
            assertThat(renderer.render("text").isBlank()).isFalse();
        }

        private String html(String markdown) {
            return renderer.render(markdown).html();
        }
    }

    /** Inputs that make naive parsers or sanitizers go quadratic finish quickly at the comment cap. */
    @Nested
    class Pathological {

        @Test
        void deeplyNestedOrRepetitiveInputAtTheCapRendersQuickly() {
            List<String> inputs = List.of(
                    "> ".repeat(10_000),
                    "- ".repeat(10_000),
                    "*".repeat(20_000),
                    "_a".repeat(10_000),
                    "[".repeat(20_000),
                    "[a](".repeat(5_000),
                    "`".repeat(20_000),
                    "<b>".repeat(6_000),
                    "<a href=\"https://example.test\" ".repeat(600),
                    "&#".repeat(10_000),
                    "\\".repeat(20_000));
            for (String input : inputs) {
                SafeHtml html = assertTimeoutPreemptively(PATIENCE, () -> renderer.render(input));
                assertInert(html.html(), input.substring(0, 12));
                String sanitized = assertTimeoutPreemptively(PATIENCE, () -> MarkdownRenderer.sanitize(input));
                assertInert(sanitized, input.substring(0, 12));
            }
        }
    }

    @Test
    void safeHtmlNeverShowsItsMarkupInToString() {
        SafeHtml html = renderer.render("Synthetic PHI-shaped text");

        assertThat(html.toString())
                .isEqualTo("SafeHtml[" + html.html().length() + " chars]")
                .doesNotContain("PHI");
        assertThat(html).isEqualTo(renderer.render("Synthetic PHI-shaped text")).hasSameHashCodeAs(html);
        assertThat(html).isNotEqualTo(renderer.render("other"));
    }

    private static void assertInert(String sanitized, String payload) {
        String html = sanitized.replace(TEMPLATE_BREAK, "");
        Matcher tag = TAG.matcher(html);
        StringBuilder text = new StringBuilder();
        int from = 0;
        while (tag.find()) {
            text.append(html, from, tag.start());
            from = tag.end();
            String name = tag.group(2);
            Map<String, String> attributes = attributes(tag.group(3));
            assertThat(ELEMENTS)
                    .as("element <%s> from %s in %s", name, payload, html)
                    .contains(name);
            if (!tag.group(1).isEmpty()) {
                assertThat(attributes).as("closing tag in %s", html).isEmpty();
            } else {
                assertAttributes(name, attributes, html);
            }
        }
        text.append(html, from, html.length());
        assertThat(text.toString())
                .as("text outside tags from %s: %s", payload, html)
                .doesNotContain("<")
                .doesNotContain(">");
        assertThat(MarkdownRenderer.sanitize(sanitized)).as("sanitizing again").isEqualTo(sanitized);
    }

    private static void assertAttributes(String element, Map<String, String> attributes, String html) {
        switch (element) {
            case "a" -> {
                assertThat(attributes).as("link attributes in %s", html).containsOnlyKeys("href", "rel");
                assertThat(Set.of(Objects.requireNonNull(attributes.get("rel")).split(" ")))
                        .as("rel in %s", html)
                        .containsExactlyInAnyOrder("noopener", "noreferrer", "nofollow");
                String target = decode(Objects.requireNonNull(attributes.get("href")));
                assertThat(target).as("href in %s", html).matches(LINK_TARGET);
                assertThat(target.toLowerCase(Locale.ROOT)).doesNotContain("script:", "data:");
            }
            case "ol" -> {
                assertThat(attributes.keySet())
                        .as("list attributes in %s", html)
                        .isSubsetOf(Set.of("start"));
                attributes.values().forEach(start -> assertThat(start).matches("[0-9]{1,9}"));
            }
            default ->
                assertThat(attributes)
                        .as("attributes of <%s> in %s", element, html)
                        .isEmpty();
        }
    }

    private static Map<String, String> attributes(String declared) {
        Map<String, String> attributes = new HashMap<>();
        Matcher attribute = ATTRIBUTE.matcher(declared);
        while (attribute.find()) {
            assertThat(attributes.put(attribute.group(1), attribute.group(2)))
                    .as("repeated attribute in %s", declared)
                    .isNull();
        }
        return attributes;
    }

    /** Resolves the character references the sanitizer writes inside attribute values. */
    private static String decode(String value) {
        return CHARACTER_REFERENCE.matcher(value).replaceAll(reference -> {
            String name = reference.group(1);
            String character = switch (name) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                default ->
                    Character.toString(
                            name.startsWith("#x")
                                    ? Integer.parseInt(name.substring(2), 16)
                                    : Integer.parseInt(name.substring(1)));
            };
            return Matcher.quoteReplacement(character);
        });
    }

    static List<String> payloads() {
        try (InputStream in = Objects.requireNonNull(
                MarkdownRendererTest.class.getResourceAsStream("/xss/corpus.txt"), "xss/corpus.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(MarkdownRendererTest::unescape)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the XSS corpus", e);
        }
    }

    private static String printable(String payload) {
        StringBuilder out = new StringBuilder();
        payload.codePoints().limit(80).forEach(c -> {
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || Character.getType(c) == Character.FORMAT) {
                out.append(String.format(Locale.ROOT, "\\u%04x", c));
            } else {
                out.appendCodePoint(c);
            }
        });
        return payload.length() > 80 ? out + "..." : out.toString();
    }

    /** The corpus escapes: {@code \n \t \r \0 \\} and {@code \}{@code uXXXX}. */
    private static String unescape(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != '\\' || i + 1 == line.length()) {
                out.append(c);
                continue;
            }
            char next = line.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '0' -> out.append('\0');
                case 'u' -> {
                    out.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
