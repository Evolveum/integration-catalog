/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * What kind of maintainer a row refers to. Stored in the {@code MaintainerType} Postgres enum.
 *
 * <p>The category says which of the row's fields is filled: {@code USER} carries a username,
 * {@code ORG} an organization, and {@code EVOLVEUM} and {@code COMMUNITY} neither - they are the
 * two catalog-wide rows seeded with the schema, one of each.
 */
public enum MaintainerType {
    USER("User"),
    ORG("Organization"),
    EVOLVEUM("Evolveum"),
    COMMUNITY("Community");

    private final String displayName;

    MaintainerType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}