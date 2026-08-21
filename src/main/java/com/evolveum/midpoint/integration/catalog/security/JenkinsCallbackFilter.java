/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import com.evolveum.midpoint.integration.catalog.configuration.JenkinsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Machine-to-machine authentication for the Jenkins build callbacks
 * (POST /api/upload/verify, /api/upload/continue/{oid} and /api/upload/continue/fail/{oid}),
 * which cannot carry a user session. The Jenkins pipeline sends the shared secret configured
 * as jenkins.callbackToken in the X-Callback-Token header; anything else — including an unset
 * property — is rejected.
 */
@Slf4j
public class JenkinsCallbackFilter extends OncePerRequestFilter {

    public static final String[] CALLBACK_PATH_PATTERNS =
            { "/api/upload/continue/**", "/api/upload/verify" };
    public static final String CALLBACK_TOKEN_HEADER = "X-Callback-Token";

    private final RequestMatcher callbackMatcher = new OrRequestMatcher(
            Arrays.stream(CALLBACK_PATH_PATTERNS)
                    .map(pattern -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(pattern))
                    .toList());
    private final JenkinsProperties jenkinsProperties;

    public JenkinsCallbackFilter(JenkinsProperties jenkinsProperties) {
        this.jenkinsProperties = jenkinsProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (callbackMatcher.matches(request) && !hasValidToken(request)) {
            log.warn("Rejected Jenkins callback {} without a valid {} header",
                    request.getRequestURI(), CALLBACK_TOKEN_HEADER);
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid or missing callback token");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasValidToken(HttpServletRequest request) {
        String expected = jenkinsProperties.callbackToken();
        String provided = request.getHeader(CALLBACK_TOKEN_HEADER);
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
