// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.pages;

import com.microsoft.playwright.Page;

/** The identity provider's login form (the Keycloak dev realm). Not ours, so it is never axe-scanned. */
public final class SignInPage {

    private final Page page;

    /**
     * Wraps the page.
     *
     * @param page the browser page
     */
    public SignInPage(Page page) {
        this.page = page;
    }

    /**
     * Signs in and waits until the browser is back on the application.
     *
     * @param username the dev-realm user
     * @param password the password
     * @param appOrigin the application origin, e.g. {@code http://localhost:1234}
     */
    public void signIn(String username, String password, String appOrigin) {
        page.fill("#username", username);
        page.fill("#password", password);
        page.click("#kc-login");
        page.waitForURL(url -> url.startsWith(appOrigin));
    }
}
