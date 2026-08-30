/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import java.util.Optional;

/**
 * The roles {@code catalog_users.role} can hold, so the access checks stop repeating the spelling of
 * each one, where a typo silently grants or denies rather than failing. The column stays a string:
 * the value is part of the API contract, so {@link #storedValue()} must keep matching it exactly.
 */
public enum CatalogRole {

    /** Full access, and the only role allowed to approve a submission. */
    SUPERUSER("Superuser"),

    /** Publishes on behalf of their organization, sharing access with its other contributors. */
    ORGANIZATION_CONTRIBUTOR("OrganizationContributor"),

    /** Publishes as themselves, even when they belong to an organization. */
    INDIVIDUAL_CONTRIBUTOR("IndividualContributor"),

    /** May browse the catalog and nothing more. */
    READ_ONLY("ReadOnly");

    private final String storedValue;

    CatalogRole(String storedValue) {
        this.storedValue = storedValue;
    }

    /** The value as it is written in {@code catalog_users.role} and sent to the client. */
    public String storedValue() {
        return storedValue;
    }

    /** Whether a stored role is this one. Null-safe, because the column is free text. */
    public boolean matches(String role) {
        return storedValue.equals(role);
    }

    /** The role a stored value names, or empty for null and for anything unrecognised. */
    public static Optional<CatalogRole> of(String role) {
        if (role == null) {
            return Optional.empty();
        }
        for (CatalogRole candidate : values()) {
            if (candidate.matches(role)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
