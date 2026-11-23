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
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Logs one line per REST request: HTTP method, path, resulting status and duration.
 * Covers every {@code /api/**} endpoint uniformly, so individual controller methods
 * don't have to repeat request/response boilerplate logging.
 * <p>
 * Each request gets a random trace id, put into the MDC (printed by the log pattern on every line
 * the request writes) and returned in the {@value #TRACE_ID_HEADER} header. Logs are collected
 * centrally, so the trace id is what ties lines to one request - never the username or email.
 * The query string is left out, as it can carry user-entered values.
 * <p>
 * Ordered first, ahead of the Spring Security chain, so requests it turns away (401, 403) are
 * logged too.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        // Generated here rather than taken from the client, so nobody can write into the log through it.
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

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
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only log REST API traffic, not SPA static-resource forwarding.
        return !request.getRequestURI().startsWith("/api/");
    }
}
