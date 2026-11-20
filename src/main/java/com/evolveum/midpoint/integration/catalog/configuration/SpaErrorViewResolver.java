/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Sends browser page errors (an unknown URL, a failure while serving a page) to the Angular error
 * page instead of the whitelabel page. API errors are left alone so clients keep the JSON body.
 * Only the HTML branch of the error controller consults view resolvers, so JSON callers never get here.
 */
@Component
public class SpaErrorViewResolver implements ErrorViewResolver {

    private static final String API_PREFIX = "/api/";

    private static final String ERROR_PAGE = "/error-page/";

    /** The SPA shell the error page itself is served from. */
    private static final String INDEX = "/index.html";

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = uri instanceof String s ? s.substring(request.getContextPath().length()) : null;
        // Failing to serve the error page itself falls back to the default page instead of redirecting in a loop.
        if (path != null && (path.startsWith(API_PREFIX) || path.startsWith(ERROR_PAGE) || path.equals(INDEX))) {
            return null;
        }
        UriComponentsBuilder target = UriComponentsBuilder.fromPath(ERROR_PAGE + status.value());
        if (path != null) {
            target.queryParam("path", UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8));
        }
        RedirectView redirect = new RedirectView(target.build(true).toUriString(), true);
        redirect.setExposeModelAttributes(false);
        return new ModelAndView(redirect);
    }
}
