/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

/**
 * Work package types of a stock OpenProject, so {@code openproject.type} can be configured by name
 * instead of by a magic number nobody can read.
 *
 * <p>The ids are the ones a freshly seeded OpenProject assigns. An instance seeded differently, or
 * one carrying types added by hand, may number them otherwise - such a deployment sets the numeric
 * {@code openproject.type-id} instead, which overrides whatever id the chosen constant carries. See
 * {@link OpenProjectProperties}.
 *
 * <p>Two things are properties of the portal rather than of the type, so they are not encoded here
 * and both are worth checking before settling on a value:
 *
 * <ul>
 *   <li>A type has to be <em>enabled in the target project</em> or the create call fails, even
 *       though the type exists instance-wide.</li>
 *   <li>Which statuses a work package of this type can reach is decided by the type's workflow. In
 *       a stock instance {@link #TASK} reaches only New, In progress, On hold, Rejected and Closed
 *       - notably not {@link OpenProjectStatus#TESTED} - while {@link #FEATURE}, {@link #EPIC},
 *       {@link #USER_STORY} and {@link #BUG} reach the full testing chain.</li>
 * </ul>
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
