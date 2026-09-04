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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * Machine-to-machine authentication for the build callbacks
 * (POST /api/upload/verify/{oid}, /api/upload/continue/{oid} and /api/upload/continue/fail/{oid}),
 * which the Jenkins pipeline calls without a user session. The pipeline sends the shared secret
 * configured as jenkins.callbackToken in the X-Callback-Token header.
 *
 */
@Slf4j
public class JenkinsCallbackFilter extends OncePerRequestFilter {

    public static final String[] CALLBACK_PATH_PATTERNS =
            { "/api/upload/continue/**", "/api/upload/verify/*" };
    public static final String CALLBACK_TOKEN_HEADER = "X-Callback-Token";

    public static final String CALLBACK_ROLE = "Jenkins";

    public static final String CALLBACK_PRINCIPAL = "jenkins";

    private static final List<SimpleGrantedAuthority> CALLBACK_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_" + CALLBACK_ROLE));

    private final RequestMatcher callbackMatcher = new OrRequestMatcher(
            Arrays.stream(CALLBACK_PATH_PATTERNS)
                    .map(pattern -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(pattern))
                    .toList());
    private final JenkinsProperties jenkinsProperties;

    public JenkinsCallbackFilter(JenkinsProperties jenkinsProperties) {
        this.jenkinsProperties = jenkinsProperties;
        if (isBlank(jenkinsProperties.callbackToken())) {
            log.warn("jenkins.callbackToken is not configured - the Jenkins integration is disabled: no callback"
                            + " to {} can authenticate as the build pipeline, so builds cannot report their result."
                            + " Set the property to the shared secret the Jenkins pipeline sends in the {} header.",
                    String.join(" and ", CALLBACK_PATH_PATTERNS), CALLBACK_TOKEN_HEADER);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (callbackMatcher.matches(request)) {
            if (hasValidToken(request)) {
                authenticateAsCallback();
            } else if (request.getHeader(CALLBACK_TOKEN_HEADER) != null) {
                // Worth reporting on its own: a caller that sends the header meant to be the
                // pipeline, so this is a stale or mistyped secret rather than a browser call.
                log.warn("Ignoring an invalid {} header on {} - the request falls back to session"
                                + " authentication and will be rejected unless it carries one",
                        CALLBACK_TOKEN_HEADER, request.getRequestURI());
            }
        }
        filterChain.doFilter(request, response);
    }

    public RequestMatcher authenticatedCallbackMatcher() {
        return request -> callbackMatcher.matches(request) && hasValidToken(request);
    }

    private void authenticateAsCallback() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                CALLBACK_PRINCIPAL, null, CALLBACK_AUTHORITIES));
        SecurityContextHolder.setContext(context);
    }

    private boolean hasValidToken(HttpServletRequest request) {
        String expected = jenkinsProperties.callbackToken();
        String provided = request.getHeader(CALLBACK_TOKEN_HEADER);
        if (isBlank(expected) || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
