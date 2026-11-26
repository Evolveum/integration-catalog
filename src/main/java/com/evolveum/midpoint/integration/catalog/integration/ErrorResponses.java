/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns an external system's error response into text safe for an exception message. Those messages
 * end up in the centrally collected logs, so the whole body - which can echo back what was sent,
 * user data included - is never quoted; only the system's own short error message is.
 */
final class ErrorResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LENGTH = 200;

    private ErrorResponses() {
    }

    /** ": message" from a JSON error body's {@code message} field, or "" when it has none. */
    static String detail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode message = MAPPER.readTree(body).path("message");
            if (!message.isTextual() || message.asText().isBlank()) {
                return "";
            }
            String text = message.asText().strip();
            return ": " + (text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + "..." : text);
        } catch (Exception e) {
            return "";
        }
    }
}
