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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs one line per REST request: HTTP method, URI, resulting status and duration.
 * Covers every {@code /api/**} endpoint uniformly, so individual controller methods
 * don't have to repeat request/response boilerplate logging.
 */
@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String fullUri = query == null ? uri : uri + "?" + query;

        log.debug("--> {} {}", method, fullUri);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            int status = response.getStatus();
            if (status >= 500) {
                log.error("<-- {} {} {} ({} ms)", method, fullUri, status, duration);
            } else if (status >= 400) {
                log.warn("<-- {} {} {} ({} ms)", method, fullUri, status, duration);
            } else {
                log.info("<-- {} {} {} ({} ms)", method, fullUri, status, duration);
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only log REST API traffic, not SPA static-resource forwarding.
        return !request.getRequestURI().startsWith("/api/");
    }
}
