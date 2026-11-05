// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A minimal browser for OIDC ITs: a cookie jar and hand-followed redirects, so each hop can be asserted. Cookies are
 * per host, not per port, as in a real browser, so one {@code SESSION} cookie reaches every application instance on
 * localhost.
 */
public final class TestBrowser {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern LOGIN_FORM = Pattern.compile("<form id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");

    private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(new LocalhostIsSecureCookieStore(), CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build();

    /**
     * Opens {@code startUrl}, follows the redirects to the Keycloak login form, submits it and follows the redirects
     * back.
     *
     * @param startUrl an application URL that requires login
     * @param username the dev-realm user
     * @return the final response
     * @throws IOException on I/O failure
     * @throws InterruptedException if interrupted
     */
    public HttpResponse<String> login(String startUrl, String username) throws IOException, InterruptedException {
        HttpResponse<String> page = follow(get(startUrl));
        Matcher form = LOGIN_FORM.matcher(page.body());
        if (!form.find()) {
            throw new IllegalStateException("No Keycloak login form at " + page.uri());
        }
        String action = form.group(1).replace("&amp;", "&");
        return follow(post(action, Map.of("username", username, "password", KeycloakDevRealm.PASSWORD)));
    }

    /**
     * Sends a GET without following redirects.
     *
     * @param url the URL
     * @return the response
     * @throws IOException on I/O failure
     * @throws InterruptedException if interrupted
     */
    public HttpResponse<String> get(String url) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The value of a cookie this browser holds for localhost.
     *
     * @param name the cookie name
     * @return the value, or {@code null}
     */
    public @Nullable String cookie(String name) {
        return ((CookieManager) client.cookieHandler().orElseThrow())
                .getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals(name))
                        .map(HttpCookie::getValue)
                        .findFirst()
                        .orElse(null);
    }

    /**
     * Submits a form without following redirects, with this browser's cookies.
     *
     * @param url the form action
     * @param form the fields
     * @return the response
     * @throws IOException on I/O failure
     * @throws InterruptedException if interrupted
     */
    public HttpResponse<String> post(String url, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> follow(HttpResponse<String> first) throws IOException, InterruptedException {
        HttpResponse<String> response = first;
        for (int hop = 0; hop < 10 && response.statusCode() / 100 == 3; hop++) {
            String location = response.headers().firstValue("Location").orElseThrow();
            response = get(response.uri().resolve(location).toString());
        }
        return response;
    }

    /**
     * Browsers treat {@code http://localhost} as a secure context and send {@code Secure} cookies to it; Keycloak and
     * the application mark theirs {@code Secure}. {@link CookieManager} would drop them over plain HTTP, so this store
     * clears the flag. Everything here talks to localhost only.
     */
    private static final class LocalhostIsSecureCookieStore implements CookieStore {

        private final CookieStore delegate = new CookieManager().getCookieStore();

        @Override
        public void add(URI uri, HttpCookie cookie) {
            cookie.setSecure(false);
            delegate.add(uri, cookie);
        }

        @Override
        public List<HttpCookie> get(URI uri) {
            return delegate.get(uri);
        }

        @Override
        public List<HttpCookie> getCookies() {
            return delegate.getCookies();
        }

        @Override
        public List<URI> getURIs() {
            return delegate.getURIs();
        }

        @Override
        public boolean remove(URI uri, HttpCookie cookie) {
            return delegate.remove(uri, cookie);
        }

        @Override
        public boolean removeAll() {
            return delegate.removeAll();
        }
    }
}
