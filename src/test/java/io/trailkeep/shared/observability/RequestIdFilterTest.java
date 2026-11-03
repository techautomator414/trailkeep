// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void reusesWellFormedIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestIdFilter.HEADER, "router-7f3a.01_b");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get(MdcKeys.REQUEST_ID));
            }
        });

        assertThat(seenInMdc).hasValue("router-7f3a.01_b");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("router-7f3a.01_b");
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).as("cleared after the request").isNull();
    }

    @Test
    void generatesUuidWhenNoIdArrives() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/"), response, new MockFilterChain());

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertThat(header).isNotNull();
        assertThat(UUID.fromString(header)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "line\nbreak", "quote\"", "x${jndi:ldap://a}"})
    void replacesIdThatIsNotSafeToken(String incoming) {
        String resolved = RequestIdFilter.resolve(incoming);

        assertThat(resolved).isNotEqualTo(incoming);
        assertThat(UUID.fromString(resolved)).isNotNull();
    }

    @Test
    void replacesAnOverlongId() {
        String incoming = "a".repeat(65);

        assertThat(RequestIdFilter.resolve(incoming)).isNotEqualTo(incoming);
        assertThat(RequestIdFilter.resolve("a".repeat(64))).isEqualTo("a".repeat(64));
    }

    @Test
    void clearsTheMdcEvenWhenTheChainThrows() {
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        try {
            filter.doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), failing);
        } catch (Exception expected) {
            assertThat(expected).hasMessage("boom");
        }

        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull();
    }

    @Test
    void runsBeforeEveryOtherFilter() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE);
    }
}
