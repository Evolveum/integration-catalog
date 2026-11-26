/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.configuration.TraceHeaders;
import com.evolveum.midpoint.integration.catalog.exception.ConnectorSigningException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Central exception logging for all controllers, so endpoints don't need their own try/catch
 * just to get a stack trace into the log. A 500 tells the client only the trace id, never the
 * exception.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConnectorSigningException.class)
    public ResponseEntity<ProblemDetail> handleConnectorSigningException(ConnectorSigningException exception,
                                                                         HttpServletRequest request) {
        log.error("Connector signing failed: {}", exception.getMessage(), exception);
        return serverError(request);
    }

    /**
     * Logs every exception a controller lets out with its stack trace and answers 500.
     * Exceptions Spring already maps to a status (ResponseStatusException, bad path variables or
     * request bodies, ...) are rethrown unchanged for the error page, whose body
     * {@link ClientErrorAttributes} writes; of those only the 5xx ones get logged.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception, HttpServletRequest request)
            throws Exception {
        if (exception instanceof ErrorResponse errorResponse) {
            if (errorResponse.getStatusCode().is5xxServerError()) {
                log.error("{} {} failed: {}", request.getMethod(), request.getRequestURI(),
                        exception.getMessage(), exception);
            }
            throw exception;
        }
        // Mapped by Spring's DefaultHandlerExceptionResolver / ResponseStatusExceptionResolver.
        if (exception instanceof TypeMismatchException
                || exception instanceof HttpMessageNotReadableException
                || exception instanceof AsyncRequestNotUsableException
                || AnnotatedElementUtils.hasAnnotation(exception.getClass(), ResponseStatus.class)) {
            throw exception;
        }
        log.error("Unhandled exception in {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage(), exception);
        return serverError(request);
    }

    /** A 500 without the exception's details, which stay in the log under the trace id it names. */
    private static ResponseEntity<ProblemDetail> serverError(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail(ClientErrors.serverError(TraceHeaders.traceIdOf(request)));
        return ResponseEntity.internalServerError().body(problem);
    }
}
