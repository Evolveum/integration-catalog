/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

/**
 * Work package types of a stock OpenProject, so {@code openproject.type} is configured by name
 * rather than by id. An instance numbering them differently sets {@code openproject.type-id}.
 */
public enum OpenProjectType {

    TASK(1),
    MILESTONE(2),
    SUMMARY_TASK(3),
    FEATURE(4),
    EPIC(5),
    USER_STORY(6),
    BUG(7);

    private final int id;

    OpenProjectType(int id) {
        this.id = id;
    }

    /** Id this type has in a stock instance, as used in {@code /api/v3/types/{id}}. */
    public int id() {
        return id;
    }
}
