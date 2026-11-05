// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.html;

import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

/**
 * Renders the Markdown subset comments use (BUILD_PLAN §4.3: bold, italic, lists, plus links, quotes and code) and
 * sanitizes the result into {@link SafeHtml}. Two independent layers, so a bug in one is not an XSS:
 *
 * <ol>
 *   <li>The parser knows only paragraphs, lists, block quotes and fenced code. Headings stay text (a comment must not
 *       add an {@code h1} to the case page), raw HTML is escaped into visible text, images render as their alt text,
 *       and link targets go through the parser's own URL filter.
 *   <li>The OWASP allow-list sanitizer then keeps only formatting elements and {@code a} with an absolute {@code
 *       http}, {@code https} or {@code mailto} target, adds {@code rel="noopener noreferrer nofollow"} to every link,
 *       and drops everything else: scripts, event handlers, styles, classes, images, SVG and MathML. Nothing it
 *       emits needs an inline style or script, so the page's CSP holds.
 * </ol>
 *
 * <p>Thread-safe; one instance serves every request.
 */
@Component
public final class MarkdownRenderer {

    /** Absolute targets only; a relative or protocol-relative link is dropped, and its text kept. */
    private static final Pattern LINK_TARGET = Pattern.compile("(?i)(?:https?|mailto):\\S+");

    private static final Pattern LIST_START = Pattern.compile("[0-9]{1,9}");

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "em", "ul", "ol", "li", "blockquote", "pre", "code", "a")
            .allowUrlProtocols("https", "http", "mailto")
            .allowAttributes("href")
            .matching(LINK_TARGET)
            .onElements("a")
            .allowAttributes("start")
            .matching(LIST_START)
            .onElements("ol")
            .requireRelsOnLinks("noopener", "noreferrer", "nofollow")
            .toFactory();

    private final Parser parser = Parser.builder()
            .enabledBlockTypes(Set.of(ListBlock.class, BlockQuote.class, FencedCodeBlock.class))
            .build();

    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .softbreak("<br>\n")
            .nodeRendererFactory(ImageAsText::new)
            .build();

    /**
     * Renders untrusted Markdown.
     *
     * @param markdown the text as the person typed it
     * @return the sanitized markup
     */
    public SafeHtml render(String markdown) {
        return new SafeHtml(sanitize(renderer.render(parser.parse(markdown))));
    }

    /**
     * The sanitizer on its own, so tests can attack it with markup the parser would have escaped.
     *
     * @param html untrusted markup
     * @return the markup the policy allows
     */
    static String sanitize(String html) {
        return POLICY.sanitize(html);
    }

    /** Writes an image as its alt text: comments carry no images (BUILD_PLAN §9). */
    private static final class ImageAsText implements NodeRenderer {

        private final HtmlNodeRendererContext context;

        ImageAsText(HtmlNodeRendererContext context) {
            this.context = context;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Image.class);
        }

        @Override
        public void render(Node node) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                context.render(child);
            }
        }
    }
}
