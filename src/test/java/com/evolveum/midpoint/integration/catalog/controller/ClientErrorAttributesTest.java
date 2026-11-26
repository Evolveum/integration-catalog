/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.configuration.TraceHeaders;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.error.ErrorAttributeOptions.Include;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The error page body never carries an exception, its message or its stack trace - even when the
 * options ask for all of them - only text written for users, or the trace id of a 5xx.
 */
class ClientErrorAttributesTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SECRET_DETAIL = "password=hunter2 at jdbc:postgresql://db.internal:5432/catalog";

    private final ClientErrorAttributes errorAttributes = new ClientErrorAttributes();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/applications");

    @Test
    void serverErrorGivesOnlyTheTraceId() {
        servedUnderTrace();

        Map<String, Object> body = errorBody(500, new IllegalStateException(SECRET_DETAIL));

        assertThat(body.get("message")).isEqualTo("Unexpected server error. Reference: " + TRACE_ID);
        assertNoExceptionDetails(body);
    }

    @Test
    void serverErrorReasonIsNotPassedOnEither() {
        Map<String, Object> body = errorBody(502,
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The API key could not be created: " + SECRET_DETAIL));

        assertThat(body.get("message")).isEqualTo("Unexpected server error.");
        assertNoExceptionDetails(body);
    }

    @Test
    void clientErrorKeepsTheReasonTheCatalogWrote() {
        Map<String, Object> body = errorBody(409,
                new ResponseStatusException(HttpStatus.CONFLICT, "Connector version 1.0.0 already exists."));

        assertThat(body.get("message")).isEqualTo("Connector version 1.0.0 already exists.");
    }

    @Test
    void clientErrorKeepsTheMessageOfARejectedArgument() {
        Map<String, Object> body = errorBody(400, new IllegalArgumentException("Name must not be empty."));

        assertThat(body.get("message")).isEqualTo("Name must not be empty.");
    }

    @Test
    void clientErrorHidesATechnicalException() {
        Map<String, Object> body = errorBody(404, new RuntimeException(SECRET_DETAIL));

        assertThat(body.get("message")).isEqualTo("Not Found");
        assertNoExceptionDetails(body);
    }

    @Test
    void springsOwnClientErrorUsesTheDetailWrittenForClients() {
        MissingServletRequestParameterException missing = new MissingServletRequestParameterException("size", "int");

        Map<String, Object> body = errorBody(400, missing);

        assertThat(body.get("message")).isEqualTo(missing.getBody().getDetail());
        assertNoExceptionDetails(body);
    }

    private void servedUnderTrace() {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(TRACE_ID);
        when(context.spanId()).thenReturn("00f067aa0ba902b7");
        Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
        when(tracer.currentTraceContext().context()).thenReturn(context);
        new TraceHeaders(tracer, Propagator.NOOP).addTo(request, new MockHttpServletResponse());
    }

    /** What the error page answers after the exception, with every optional attribute asked for. */
    private Map<String, Object> errorBody(int status, Exception exception) {
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        errorAttributes.resolveException(request, new MockHttpServletResponse(), null, exception);
        return errorAttributes.getErrorAttributes(new ServletWebRequest(request),
                ErrorAttributeOptions.of(Include.values()));
    }

    private static void assertNoExceptionDetails(Map<String, Object> body) {
        assertThat(body).doesNotContainKeys("exception", "trace", "errors");
        assertThat(body.toString()).doesNotContain("hunter2", "jdbc:", "java.lang");
    }
}
