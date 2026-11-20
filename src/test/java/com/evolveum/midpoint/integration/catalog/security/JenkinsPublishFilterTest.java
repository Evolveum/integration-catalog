/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The publish secret signs the pipeline in on the granted endpoints and nowhere else.
 */
class JenkinsPublishFilterTest {

    private static final String ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    private static final String METHOD = "/api/integration-method/" + ID + "/1";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static JenkinsPublishFilter filter(String publishToken) {
        return new JenkinsPublishFilter(
                new JenkinsProperties("http://jenkins.example", "apiToken", "jenkins", "build", "callback", publishToken));
    }

    private static MockHttpServletRequest request(String method, String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (token != null) {
            request.addHeader(JenkinsPublishFilter.PUBLISH_TOKEN_HEADER, token);
        }
        return request;
    }

    private static Authentication run(JenkinsPublishFilter publishFilter, MockHttpServletRequest request)
            throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        publishFilter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "the filter must never end the chain itself");
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/applications",
            "GET, /api/applications/" + ID,
            "GET, " + METHOD + "/connectors",
            "POST, /api/upload/integration",
            "PUT, " + METHOD,
            "POST, " + METHOD + "/connectors",
            "PUT, " + METHOD + "/connectors/7",
            "POST, /api/upload/trigger-build/" + ID
    })
    void signsThePipelineInOnEveryGrantedEndpoint(String method, String uri) throws ServletException, IOException {
        Authentication authentication = run(filter("secret"), request(method, uri, "secret"));

        assertNotNull(authentication, method + " " + uri + " is not granted");
        assertInstanceOf(JenkinsPublisher.class, authentication.getPrincipal());
        assertEquals(JenkinsPublisher.NAME, authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + JenkinsPublishFilter.PUBLISH_ROLE)));
    }

    /** A leaked secret must open nothing beyond the grant: same paths with other verbs, and neighbours. */
    @ParameterizedTest
    @CsvSource({
            "DELETE, " + METHOD,
            "POST, " + METHOD + "/approve",
            "POST, " + METHOD + "/cancel",
            "PUT, " + METHOD + "/connectors/7/compatibility",
            "PUT, /api/applications/" + ID,
            "PUT, /api/applications/" + ID + "/featured",
            "POST, /api/applications/" + ID + "/logo",
            "GET, /api/applications/search",
            "GET, /api/auth/me",
            "GET, /api/api-keys",
            "GET, /api/review-queue",
            "POST, /api/upload/continue/" + ID,
            "POST, /api/upload/verify/" + ID,
            "POST, /api/requests"
    })
    void ignoresTheSecretEverywhereElse(String method, String uri) throws ServletException, IOException {
        assertNull(run(filter("secret"), request(method, uri, "secret")), method + " " + uri);
    }

    @Test
    void leavesAWrongOrMissingSecretAnonymous() throws ServletException, IOException {
        assertNull(run(filter("secret"), request("POST", "/api/upload/integration", "wrong")));
        assertNull(run(filter("secret"), request("POST", "/api/upload/integration", null)));
    }

    @Test
    void authenticatesNobodyWhileNoSecretIsConfigured() throws ServletException, IOException {
        assertNull(run(filter(null), request("POST", "/api/upload/integration", "anything")));
        assertNull(run(filter(""), request("POST", "/api/upload/integration", "")));
    }

    /** The callback secret is a different key and opens none of the publish endpoints. */
    @Test
    void doesNotAcceptTheCallbackSecret() throws ServletException, IOException {
        MockHttpServletRequest request = request("POST", "/api/upload/integration", null);
        request.addHeader(JenkinsCallbackFilter.CALLBACK_TOKEN_HEADER, "callback");

        assertNull(run(filter("secret"), request));
    }

    @Test
    void carriesTheNamedAuthor() throws ServletException, IOException {
        MockHttpServletRequest request = request("POST", "/api/upload/integration", "secret");
        request.addHeader(JenkinsPublishFilter.AUTHOR_USERNAME_HEADER, " alice ");
        request.addHeader(JenkinsPublishFilter.AUTHOR_EMAIL_HEADER, "alice@example.com");

        JenkinsPublisher publisher = (JenkinsPublisher) run(filter("secret"), request).getPrincipal();

        assertEquals("alice", publisher.authorUsername());
        assertEquals("alice@example.com", publisher.authorEmail());
    }

    @Test
    void exemptsFromCsrfOnlyGrantedCallsProvingTheSecret() {
        JenkinsPublishFilter publishFilter = filter("secret");

        assertTrue(publishFilter.authenticatedPublishMatcher().matches(request("PUT", METHOD, "secret")));
        assertFalse(publishFilter.authenticatedPublishMatcher().matches(request("PUT", METHOD, null)),
                "a browser call must keep CSRF protection");
        assertFalse(publishFilter.authenticatedPublishMatcher().matches(request("DELETE", METHOD, "secret")));
    }
}
