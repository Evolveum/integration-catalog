/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.configuration.TraceHeaders;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.error.ErrorAttributeOptions.Include;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The body of every error response Spring Boot's error page writes ({@code /error}): timestamp,
 * status, error, path and a message fit for a user. The exception class, stack trace and binding
 * errors are left out whatever {@code server.error.*} says. A 5xx only gives the trace id; a 4xx
 * gives the text the catalog wrote for it ({@link ClientErrors}), or the status name.
 */
@Component
public class ClientErrorAttributes extends DefaultErrorAttributes {

    private static final String MESSAGE = "message";

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest,
                options.excluding(Include.EXCEPTION, Include.STACK_TRACE, Include.BINDING_ERRORS));
        if (attributes.containsKey(MESSAGE)) {
            int status = attributes.get("status") instanceof Integer code ? code : 500;
            attributes.put(MESSAGE, message(webRequest, status));
        }
        return attributes;
    }

    private String message(WebRequest webRequest, int status) {
        if (status >= 500) {
            HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
            return ClientErrors.serverError(TraceHeaders.traceIdOf(request));
        }
        HttpStatus resolved = HttpStatus.resolve(status);
        String statusName = resolved != null ? resolved.getReasonPhrase() : "Error";
        Throwable error = unwrap(getError(webRequest));
        if (error == null) {
            // sendError without an exception, e.g. Spring Security's 401/403, whose texts are fixed
            Object message = webRequest.getAttribute(RequestDispatcher.ERROR_MESSAGE, RequestAttributes.SCOPE_REQUEST);
            return message instanceof String text && !text.isBlank() ? text : statusName;
        }
        if (error instanceof ErrorResponse errorResponse && !(error instanceof ResponseStatusException)) {
            // Spring MVC's own client errors carry a detail written for clients ("Invalid request content.")
            String detail = errorResponse.getBody().getDetail();
            return detail != null ? detail : statusName;
        }
        return ClientErrors.messageOf(error, statusName);
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof ServletException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }
}
