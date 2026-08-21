/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import java.util.Locale;

/**
 * Work package statuses of a stock OpenProject, so {@code openproject.initial-status} can be
 * configured by name instead of by a magic number nobody can read.
 *
 * <p>The ids are the ones a freshly seeded OpenProject assigns. An instance numbering them
 * otherwise sets the numeric {@code openproject.initial-status-id} instead, which overrides the id
 * the chosen constant carries. See {@link OpenProjectProperties}.
 *
 * <p>A status existing here does not mean a work package can be moved into it: that depends on the
 * workflow of the configured {@link OpenProjectType}. {@link #TESTED} in particular is out of reach
 * for a stock {@link OpenProjectType#TASK}.
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
     *
     * <p>Used for matching a configured status against what the portal reports, which is why it is
     * lenient: the two come from different places and only have to mean the same thing. Statuses
     * that merely read alike stay distinct, {@code Tested} and {@code Test failed} among them.
     */
    static String normalize(String status) {
        return status == null ? "" : status.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
