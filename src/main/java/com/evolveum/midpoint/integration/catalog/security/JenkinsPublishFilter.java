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
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Signs the connector Jenkins pipeline in by the shared secret it sends in the X-Jenkins-Token header
 * ({@link JenkinsAccessToken}, the same one the build callbacks use),
 * on the listed endpoints only. Anywhere else the header is ignored and the request stays anonymous,
 * so a leaked secret opens nothing beyond this list. What the pipeline may then change is decided in
 * the services: Evolveum-maintained items only.
 */
@Slf4j
public class JenkinsPublishFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = JenkinsAccessToken.HEADER;
    public static final String AUTHOR_USERNAME_HEADER = "X-Author-Username";
    public static final String AUTHOR_EMAIL_HEADER = "X-Author-Email";

    public static final String PUBLISH_ROLE = "JenkinsPublisher";

    private static final List<SimpleGrantedAuthority> PUBLISH_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_" + PUBLISH_ROLE));

    /** Keeps an id path from matching named siblings such as /api/applications/featured. */
    private static final String UUID = "{id:[0-9a-fA-F-]+}";

    /** The whole grant. Adding an endpoint here is all it takes to open it to the pipeline. */
    private static final RequestMatcher ENDPOINTS = new OrRequestMatcher(
            endpoint(HttpMethod.GET, "/api/applications"),
            endpoint(HttpMethod.GET, "/api/applications/" + UUID),
            endpoint(HttpMethod.GET, "/api/integration-method/*/*/connectors"),
            endpoint(HttpMethod.POST, "/api/upload/integration"),
            endpoint(HttpMethod.PUT, "/api/integration-method/*/*"),
            endpoint(HttpMethod.POST, "/api/integration-method/*/*/connectors"),
            endpoint(HttpMethod.PUT, "/api/integration-method/*/*/connectors/*"),
            endpoint(HttpMethod.POST, "/api/upload/trigger-build/" + UUID));

    private final JenkinsProperties jenkinsProperties;

    public JenkinsPublishFilter(JenkinsProperties jenkinsProperties) {
        this.jenkinsProperties = jenkinsProperties;
    }

    private static RequestMatcher endpoint(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (ENDPOINTS.matches(request)) {
            if (hasValidToken(request)) {
                authenticateAsPublisher(request);
            } else if (request.getHeader(TOKEN_HEADER) != null) {
                // Only the log learns the token was wrong; the caller just stays anonymous.
                log.error("The {} received for the request {} {} is incorrect.",
                        TOKEN_HEADER, request.getMethod(), request.getRequestURI());
            }
        }
        filterChain.doFilter(request, response);
    }

    /** A listed endpoint called with the valid secret; used for the CSRF exemption and the role rule. */
    public RequestMatcher authenticatedPublishMatcher() {
        return request -> ENDPOINTS.matches(request) && hasValidToken(request);
    }

    private void authenticateAsPublisher(HttpServletRequest request) {
        JenkinsPublisher publisher = new JenkinsPublisher(
                trimmed(request.getHeader(AUTHOR_USERNAME_HEADER)),
                trimmed(request.getHeader(AUTHOR_EMAIL_HEADER)));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                publisher, null, PUBLISH_AUTHORITIES));
        SecurityContextHolder.setContext(context);
    }

    private boolean hasValidToken(HttpServletRequest request) {
        return JenkinsAccessToken.matches(jenkinsProperties.accessToken(), request.getHeader(TOKEN_HEADER));
    }

    private static String trimmed(String value) {
        return JenkinsAccessToken.isBlank(value) ? null : value.trim();
    }
}
