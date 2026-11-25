/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Returns the request's trace header on every response, and logs one line per REST request: HTTP
 * method, path, resulting status and duration. Covers every {@code /api/**} endpoint uniformly, so
 * individual controller methods don't have to repeat request/response boilerplate logging.
 * <p>
 * The trace id itself comes from Micrometer Tracing, which continues the caller's header or starts a
 * new trace, and puts the id into the MDC for the log pattern. Logs are collected centrally, so the
 * trace id is what ties lines to one request - never the username or email. The query string is
 * left out, as it can carry user-entered values.
 * <p>
 * Ordered right after Spring's observation filter (which opens the trace at
 * {@code HIGHEST_PRECEDENCE + 1}) and ahead of the Spring Security chain, so requests it turns away
 * (401, 403) are logged and traced too.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RequiredArgsConstructor
public class RequestLoggingFilter extends OncePerRequestFilter {

    private final TraceHeaders traceHeaders;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Before the chain, while the response is not committed yet.
        traceHeaders.addTo(request, response);
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            // Only log REST API traffic, not SPA static-resource forwarding.
            filterChain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();
        String method = request.getMethod();
        log.debug("--> {} {}", method, uri);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            int status = response.getStatus();
            if (status >= 500) {
                log.error("<-- {} {} {} ({} ms)", method, uri, status, duration);
            } else if (status >= 400) {
                log.warn("<-- {} {} {} ({} ms)", method, uri, status, duration);
            } else {
                log.info("<-- {} {} {} ({} ms)", method, uri, status, duration);
            }
        }
    }
}
