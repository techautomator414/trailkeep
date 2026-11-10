// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No user-facing wording is written in a template (WP-27 part b, BUILD_PLAN §10.1): the words a person reads come from
 * {@code messages.properties}, so they can be translated in one place.
 *
 * <p>In an HTML template, text is allowed only inside an element (or under one) that Thymeleaf replaces, by {@code
 * th:text}, {@code th:utext}, {@code th:replace}, {@code th:insert}, {@code th:remove} or the safe-HTML {@code
 * tk:safe}: there it is the design-time placeholder a browser shows when the file is opened on its own.
 *
 * <p>Anywhere else, a literal {@code aria-label}, {@code title}, {@code placeholder} or {@code alt}, or the {@code
 * value} of a submit or button input, is wording too. A mail's text templates may hold only inline expressions.
 *
 * <p>The allow-list is explicit and empty: nothing in the templates is exempt today. Punctuation, digits and
 * character entities carry no letters, so they are never wording. A template that needs a literal is added here, with
 * the reason, in the PR that adds it.
 */
class TemplateTextTest {

    private static final Path ROOT = Path.of("src/main/resources/templates");
    private static final Set<String> ALLOWED = Set.of();
    private static final Set<String> REPLACING =
            Set.of("th:text", "th:utext", "th:replace", "th:insert", "th:remove", "tk:safe");
    private static final Set<String> LITERAL_ATTRIBUTES = Set.of("aria-label", "title", "placeholder", "alt");
    private static final Set<String> VOID = Set.of("br", "hr", "img", "input", "meta", "link", "col", "source");
    private static final String ATTR = "[\\w:.-]+(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))?";
    private static final Pattern TOKEN =
            Pattern.compile("(?s)<!--.*?-->|<![^>]*>|<(/?)([a-zA-Z][\\w:-]*)((?:\\s+" + ATTR + ")*)\\s*(/?)>|([^<]+)");
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:.-]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'))?");
    private static final Pattern LETTERS = Pattern.compile("\\p{L}");

    @Test
    void htmlTemplatesHoldNoLiteralWording() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : files(".html")) {
            if (!ALLOWED.contains(relative(file))) {
                scan(relative(file), Files.readString(file), found);
            }
        }
        assertThat(found)
                .as("literal wording in templates; move it to messages.properties")
                .isEmpty();
    }

    @Test
    void mailTextTemplatesHoldOnlyInlineExpressions() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : files(".txt")) {
            String text = Files.readString(file).replaceAll("(?s)/\\*\\[-.*?-\\]\\*/", "");
            String rest = withoutInlineExpressions(text);
            if (LETTERS.matcher(rest).find() && !ALLOWED.contains(relative(file))) {
                found.add(
                        relative(file) + ": " + rest.strip().lines().findFirst().orElse(""));
            }
        }
        assertThat(found)
                .as("literal wording in mail templates; move it to messages.properties")
                .isEmpty();
    }

    @Test
    void theScannerSeesWhatItShould() {
        List<String> found = new ArrayList<>();
        scan(
                "sample.html",
                "<!DOCTYPE html><p>Hello</p><p th:text=\"#{a}\">Fine <b>nested</b></p>"
                        + "<a aria-label=\"Go\" href=\"#\" th:if=\"${n > 0}\">&nbsp;</a>"
                        + "<input type=\"submit\" value=\"Send\"><script>var a = 'text';</script><br>"
                        + "<button th:attr=\"aria-label=#{b}\" aria-label=\"Fallback\" th:text=\"#{c}\">x1</button>",
                found);
        assertThat(found)
                .containsExactly(
                        "sample.html: text \"Hello\"", "sample.html: aria-label=\"Go\"", "sample.html: value=\"Send\"");
        assertThat(withoutInlineExpressions("[(#{k(${a},#{${b}})})] and [(${link})]")
                        .strip())
                .isEqualTo("and");
    }

    private static List<Path> files(String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(ROOT)) {
            return walk.filter(path -> path.toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static String relative(Path file) {
        return ROOT.relativize(file).toString();
    }

    private static void scan(String name, String html, List<String> found) {
        Deque<String> names = new ArrayDeque<>();
        Deque<Boolean> replaced = new ArrayDeque<>();
        Matcher token = TOKEN.matcher(html);
        while (token.find()) {
            if (token.group(5) != null) {
                text(name, token.group(5), names, replaced, found);
            } else if (token.group(2) != null && !token.group(1).isEmpty()) {
                close(token.group(2).toLowerCase(Locale.ROOT), names, replaced);
            } else if (token.group(2) != null) {
                String tag = token.group(2).toLowerCase(Locale.ROOT);
                boolean replacing = attributes(name, token.group(3), found);
                if (!VOID.contains(tag) && token.group(4).isEmpty()) {
                    names.push(tag);
                    replaced.push(replacing);
                }
            }
        }
    }

    private static void text(
            String name, String raw, Deque<String> names, Deque<Boolean> replaced, List<String> found) {
        String text = raw.replaceAll("&[#\\w]+;", " ");
        boolean code = "script".equals(names.peek()) || "style".equals(names.peek());
        if (LETTERS.matcher(text).find() && !code && !replaced.contains(true)) {
            found.add(name + ": text \"" + text.strip().replaceAll("\\s+", " ") + "\"");
        }
    }

    private static void close(String tag, Deque<String> names, Deque<Boolean> replaced) {
        if (names.contains(tag)) {
            while (!names.pop().equals(tag)) {
                replaced.pop();
            }
            replaced.pop();
        }
    }

    /** Reports literal wording in the attributes of one tag; answers whether the tag replaces its own content. */
    private static boolean attributes(String name, String attrs, List<String> found) {
        boolean replacing = false;
        boolean button = attrs.matches("(?s).*type\\s*=\\s*\"(submit|button)\".*");
        Matcher attribute = ATTRIBUTE.matcher(attrs);
        while (attribute.find()) {
            String key = attribute.group(1);
            String value = attribute.group(2) != null ? attribute.group(2) : attribute.group(3);
            replacing |= REPLACING.contains(key);
            boolean wording = LITERAL_ATTRIBUTES.contains(key) || ("value".equals(key) && button);
            boolean supplied = attrs.contains("th:" + key) || attrs.contains("th:attr");
            if (wording && value != null && LETTERS.matcher(value).find() && !supplied) {
                found.add(name + ": " + key + "=\"" + value + "\"");
            }
        }
        return replacing;
    }

    /** The text with every {@code [( ... )]} inline expression removed, however deeply its braces nest. */
    private static String withoutInlineExpressions(String text) {
        StringBuilder rest = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("[(", i);
            if (start < 0) {
                rest.append(text, i, text.length());
                break;
            }
            rest.append(text, i, start);
            int end = text.indexOf(")]", start);
            i = end < 0 ? text.length() : end + 2;
        }
        return rest.toString();
    }
}
