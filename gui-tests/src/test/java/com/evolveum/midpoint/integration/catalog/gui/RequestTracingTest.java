/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.evolveum.midpoint.integration.catalog.gui.framework.AbstractGuiTest;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.Test;

/**
 * Request tracing: every response carries the W3C {@code traceparent} header, and a caller's own
 * trace id is continued rather than replaced, so its requests can be found in the catalog's logs.
 * Checks headers, not the UI; no test data is created.
 */
class RequestTracingTest extends AbstractGuiTest {

    private static final String TRACE_HEADER = "traceparent";
    /** version - trace id - parent span id - flags, see https://www.w3.org/TR/trace-context/ */
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final String PUBLIC_API = "/api/integration-method-types";

    @Test
    void responseToThePagesOwnApiCallCarriesANewTraceId() {
        Response response = page.waitForResponse(r -> r.url().contains("/api/"), () -> page.navigate("/"));

        String traceparent = response.allHeaders().get(TRACE_HEADER);
        assertNotNull(traceparent, "No " + TRACE_HEADER + " header on " + response.url());
        assertTrue(TRACEPARENT.matcher(traceparent).matches(), "Not a W3C traceparent: " + traceparent);
    }

    @Test
    void callersTraceIdIsContinued() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        APIResponse response = page.request().get(PUBLIC_API, RequestOptions.create()
                .setHeader(TRACE_HEADER, "00-" + traceId + "-00f067aa0ba902b7-01"));

        assertEquals(200, response.status(), PUBLIC_API);
        String traceparent = response.headers().get(TRACE_HEADER);
        assertNotNull(traceparent, "No " + TRACE_HEADER + " header on " + PUBLIC_API);
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        assertTrue(matcher.matches(), "Not a W3C traceparent: " + traceparent);
        assertEquals(traceId, matcher.group(1), "The caller's trace id was not continued");
    }
}
