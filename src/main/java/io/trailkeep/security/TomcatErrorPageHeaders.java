// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Security headers on the error pages Tomcat writes itself (WP-28 part e; ZAP rule 10038 in the first DAST runs).
 * Tomcat refuses some requests before Spring's filter chain runs, a URL with an encoded backslash for one, and answers
 * them with its {@link ErrorReportValve} page, which carried none of the headers every other response has. This
 * replaces the valve, Spring Boot's included, with one that adds them to such a page: a Content-Security-Policy that
 * allows nothing (the page needs no resource), {@code nosniff}, {@code X-Frame-Options: DENY}, BUILD_PLAN §9's
 * {@code Referrer-Policy: same-origin} and {@code Cache-Control: no-store}. Like Spring Boot's valve, it shows neither
 * the server version nor a report.
 */
@Configuration(proxyBeanMethods = false)
class TomcatErrorPageHeaders {

    /** The policy of a Tomcat error page: it loads nothing, submits nothing and is framed by nobody. */
    static final String POLICY = "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private static final int FIRST_ERROR_STATUS = 400;

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> errorReportValveWithHeaders() {
        // The swap waits until the host starts, after every customizer (Spring Boot's adds its own valve) has run.
        return factory -> factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.addLifecycleListener(event -> {
                    if (Lifecycle.BEFORE_START_EVENT.equals(event.getType())) {
                        useHeaderValve(host);
                    }
                });
            }
        });
    }

    /**
     * Replaces every error report valve of the host with a {@link HeaderErrorReportValve}, and names its class as the
     * host's error report valve, so that the host does not add Tomcat's default one (which shows the server version).
     */
    static void useHeaderValve(StandardHost host) {
        for (Valve valve : host.getPipeline().getValves()) {
            if (valve instanceof ErrorReportValve) {
                host.getPipeline().removeValve(valve);
            }
        }
        HeaderErrorReportValve valve = new HeaderErrorReportValve();
        valve.setShowReport(false);
        valve.setShowServerInfo(false);
        host.getPipeline().addValve(valve);
        host.setErrorReportValveClass(HeaderErrorReportValve.class.getName());
    }

    /** Tomcat's error report valve, adding the security headers to the error pages it writes. */
    static final class HeaderErrorReportValve extends ErrorReportValve {

        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            // The same condition under which ErrorReportValve writes its page: an error status and nothing written.
            if (response.getStatus() >= FIRST_ERROR_STATUS && response.getContentWritten() == 0) {
                setIfAbsent(response, "Content-Security-Policy", POLICY);
                setIfAbsent(response, "X-Content-Type-Options", "nosniff");
                setIfAbsent(response, "X-Frame-Options", "DENY");
                setIfAbsent(response, "Referrer-Policy", "same-origin");
                setIfAbsent(response, "Cache-Control", "no-store");
            }
            super.report(request, response, throwable);
        }

        private static void setIfAbsent(Response response, String name, String value) {
            if (!response.containsHeader(name)) {
                response.setHeader(name, value);
            }
        }
    }
}
