/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.evolveum.midpoint.integration.catalog.gui.framework.AbstractGuiTest;
import com.microsoft.playwright.APIResponse;
import org.junit.jupiter.api.Test;

/**
 * Error responses leak nothing of the server's inside: no exception class, message or stack trace,
 * while the reasons written for users still reach them. Checks responses, not the UI; no test data
 * is created.
 */
class ErrorResponseTest extends AbstractGuiTest {

    @Test
    void malformedIdIsRefusedWithoutInternals() {
        APIResponse response = page.request().get("/api/applications/not-a-uuid");

        assertEquals(400, response.status());
        assertNoInternals(response.text());
    }

    @Test
    void reasonWrittenForUsersStillArrives() {
        APIResponse response = page.request().get("/api/applications/" + UUID.randomUUID());

        assertEquals(404, response.status());
        String body = response.text();
        assertTrue(body.contains("Application not found"), body);
        assertNoInternals(body);
    }

    private static void assertNoInternals(String body) {
        assertTrue(body.contains("\"message\""), body);
        for (String internal : new String[] {"\"exception\"", "\"trace\"", "java.", "Exception", "\tat "}) {
            assertFalse(body.contains(internal), "Error response shows " + internal + ": " + body);
        }
    }
}
