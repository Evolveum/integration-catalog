/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * What kind of maintainer a row refers to. Stored in the {@code MaintainerType} Postgres enum.
 *
 * <p>{@code USER} is a person (the {@code identifier} is their login), {@code ORG} an organization
 * (the {@code identifier} is its id -> organizations.id), while {@code EVOLVEUM} and {@code COMMUNITY} are the two
 * catalog-wide defaults seeded in the table.
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