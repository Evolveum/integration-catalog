/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shared-secret authentication of the build callbacks and the startup warning that
 * makes an unconfigured jenkins.callbackToken - which silently disables the whole integration
 * - visible in the application log.
 */
class JenkinsCallbackFilterTest {

    private static final String OID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    private static final String VERIFY = "/api/upload/verify/" + OID;
    private static final String CONTINUE = "/api/upload/continue/" + OID;
    private static final String CONTINUE_FAIL = "/api/upload/continue/fail/" + OID;

    private Logger filterLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void captureLog() {
        filterLogger = (Logger) LoggerFactory.getLogger(JenkinsCallbackFilter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        filterLogger.addAppender(logAppender);
    }

    @AfterEach
    void releaseLog() {
        filterLogger.detachAppender(logAppender);
        SecurityContextHolder.clearContext();
    }

    private static JenkinsProperties properties(String callbackToken) {
        return new JenkinsProperties("http://jenkins.example", "apiToken", "jenkins", "build", callbackToken);
    }

    private List<ILoggingEvent> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    private static MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (token != null) {
            request.addHeader(JenkinsCallbackFilter.CALLBACK_TOKEN_HEADER, token);
        }
        return request;
    }

    /** Runs the filter and reports the authentication it left behind, if any. */
    private static Authentication filter(JenkinsCallbackFilter callbackFilter, MockHttpServletRequest request)
            throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        callbackFilter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "the filter must never end the chain itself");
        return SecurityContextHolder.getContext().getAuthentication();
    }

    // --- startup warning ---------------------------------------------------------------

    @Test
    void warnsAtStartupWhenCallbackTokenIsMissing() {
        new JenkinsCallbackFilter(properties(null));

        assertEquals(1, warnings().size());
        assertTrue(warnings().getFirst().getFormattedMessage().contains("jenkins.callbackToken is not configured"));
    }

    @Test
    void warnsAtStartupWhenCallbackTokenIsBlank() {
        new JenkinsCallbackFilter(properties("   "));

        assertEquals(1, warnings().size());
    }

    @Test
    void staysQuietWhenCallbackTokenIsConfigured() {
        new JenkinsCallbackFilter(properties("secret"));

        assertTrue(warnings().isEmpty());
    }

    /** The warning has to name the paths as mapped, or it sends the reader to the wrong place. */
    @Test
    void startupWarningNamesTheCallbackPaths() {
        new JenkinsCallbackFilter(properties(null));

        String message = warnings().getFirst().getFormattedMessage();
        assertTrue(message.contains("/api/upload/verify/*"), message);
        assertTrue(message.contains("/api/upload/continue/**"), message);
    }

    // --- authenticating a machine caller -----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { VERIFY, CONTINUE, CONTINUE_FAIL })
    void authenticatesEveryCallbackCarryingTheToken(String uri) throws ServletException, IOException {
        Authentication authentication = filter(new JenkinsCallbackFilter(properties("secret")), request(uri, "secret"));

        assertNotNull(authentication, uri + " is not covered by the callback patterns");
        assertEquals(JenkinsCallbackFilter.CALLBACK_PRINCIPAL, authentication.getName());
        assertTrue(authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(("ROLE_" + JenkinsCallbackFilter.CALLBACK_ROLE)::equals));
    }

    // --- leaving everything else to the session ----------------------------------------

    @Test
    void leavesCallbackWithoutTokenUnauthenticated() throws ServletException, IOException {
        assertNull(filter(new JenkinsCallbackFilter(properties("secret")), request(CONTINUE, null)));
    }

    @Test
    void leavesCallbackWithWrongTokenUnauthenticated() throws ServletException, IOException {
        assertNull(filter(new JenkinsCallbackFilter(properties("secret")), request(CONTINUE, "wrong")));
    }

    @Test
    void warnsAboutAnInvalidTokenHeader() throws ServletException, IOException {
        filter(new JenkinsCallbackFilter(properties("secret")), request(CONTINUE, "wrong"));

        assertTrue(warnings().stream().anyMatch(event ->
                event.getFormattedMessage().contains(JenkinsCallbackFilter.CALLBACK_TOKEN_HEADER)));
    }

    @Test
    void authenticatesNobodyWhenNoTokenIsConfigured() throws ServletException, IOException {
        assertNull(filter(new JenkinsCallbackFilter(properties(null)), request(CONTINUE, "anything")));
    }

    /** The secret authenticates the callbacks only - it is not a master key to the API. */
    @Test
    void ignoresTheTokenOutsideTheCallbackPaths() throws ServletException, IOException {
        assertNull(filter(new JenkinsCallbackFilter(properties("secret")),
                request("/api/applications/" + OID + "/logo", "secret")));
    }

    @Test
    void leavesNonCallbackRequestsToTheSecurityChain() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/applications");

        assertNull(filter(new JenkinsCallbackFilter(properties(null)), request));
    }

    // --- CSRF exemption ------------------------------------------------------------------

    @Test
    void exemptsFromCsrfOnlyTheCallbacksProvingTheToken() {
        JenkinsCallbackFilter callbackFilter = new JenkinsCallbackFilter(properties("secret"));

        assertTrue(callbackFilter.authenticatedCallbackMatcher().matches(request(VERIFY, "secret")));
        assertFalse(callbackFilter.authenticatedCallbackMatcher().matches(request(VERIFY, null)),
                "a browser call must keep CSRF protection");
        assertFalse(callbackFilter.authenticatedCallbackMatcher().matches(request(VERIFY, "wrong")));
    }
}
