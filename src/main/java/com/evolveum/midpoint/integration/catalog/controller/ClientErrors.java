/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.exception.ObjectAlreadyExist;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a client may be told about a failure: only text the catalog writes for its users, never the
 * message of a technical exception, which can name tables, classes, hosts or entered values. The
 * full exception goes to the log, under the trace id the client is given instead.
 */
public final class ClientErrors {

    private static final String SERVER_ERROR = "Unexpected server error.";

    private ClientErrors() {
    }

    /**
     * The message of an exception the catalog throws for its users (a status exception's reason, a
     * rejected argument or state, a missing or duplicate object), otherwise the fallback.
     */
    public static String messageOf(Throwable e, String fallback) {
        String message = null;
        if (e instanceof ResponseStatusException statusException) {
            message = statusException.getReason();
        } else if (e instanceof IllegalArgumentException || e instanceof IllegalStateException
                || e instanceof EntityNotFoundException || e instanceof ObjectAlreadyExist) {
            message = e.getMessage();
        }
        return message == null || message.isBlank() ? fallback : message;
    }

    /** A 404 for an exception caught around a service call. */
    public static ResponseStatusException notFound(Exception e) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, messageOf(e, "Not found."));
    }

    /** The message of every 5xx, with the trace id that finds the request in the logs. */
    public static String serverError(String traceId) {
        return traceId == null ? SERVER_ERROR : SERVER_ERROR + " Reference: " + traceId;
    }
}
