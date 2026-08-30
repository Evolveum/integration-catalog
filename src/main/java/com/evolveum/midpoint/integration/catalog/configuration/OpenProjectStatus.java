/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import java.util.Locale;

/**
 * Work package statuses of a stock OpenProject, so {@code openproject.initial-status} is configured
 * by name rather than by id. An instance numbering them differently sets
 * {@code openproject.initial-status-id}.
 */
public enum OpenProjectStatus {

    NEW(1, "New"),
    IN_SPECIFICATION(2, "In specification"),
    SPECIFIED(3, "Specified"),
    CONFIRMED(4, "Confirmed"),
    TO_BE_SCHEDULED(5, "To be scheduled"),
    SCHEDULED(6, "Scheduled"),
    IN_PROGRESS(7, "In progress"),
    DEVELOPED(8, "Developed"),
    IN_TESTING(9, "In testing"),
    TESTED(10, "Tested"),
    TEST_FAILED(11, "Test failed"),
    CLOSED(12, "Closed"),
    ON_HOLD(13, "On hold"),
    REJECTED(14, "Rejected");

    private final int id;
    private final String title;

    OpenProjectStatus(int id, String title) {
        this.id = id;
        this.title = title;
    }

    /** Id this status has in a stock instance, as used in {@code /api/v3/statuses/{id}}. */
    public int id() {
        return id;
    }

    /** Title the portal displays and reports for this status, e.g. {@code "In progress"}. */
    public String title() {
        return title;
    }

    /**
     * Reduces a status to a form that compares equal whether it was written as a portal title, as a
     * constant name, or with stray case and spacing - {@code "In progress"}, {@code "IN_PROGRESS"}
     * and {@code " in progress "} all collapse to {@code inprogress}.
     */
    static String normalize(String status) {
        return status == null ? "" : status.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
