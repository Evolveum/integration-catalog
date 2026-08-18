/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import java.util.Optional;

/**
 * The roles {@code catalog_users.role} can hold.
 *
 * <p>The column stays a string rather than becoming an enum type: the value travels to the client in
 * the login response and appears in the API documentation, so it is part of an external contract and
 * its spelling has to keep matching {@link #storedValue()} exactly. What this enum removes is the
 * repetition of that spelling across the access checks, where a typo would not fail but would
 * silently deny - or grant - the wrong thing.
 *
 * <p>Values a released database may already hold are therefore never renamed here. A role that is
 * not one of these resolves to nothing through {@link #of(String)}, which the callers treat as "no
 * privileges", the same way an unknown user is treated.
 */
public enum CatalogRole {

    /** Full access, and the only role allowed to approve a submission. */
    SUPERUSER("Superuser"),

    /**
     * Publishes on behalf of their organization, and shares access with the fellow organization
     * contributors of that organization - an organization acts as a team.
     */
    ORGANIZATION_CONTRIBUTOR("OrganizationContributor"),

    /**
     * Publishes as themselves. Belonging to an organization changes nothing about that: their items
     * stay personal and their organization's items are not theirs.
     */
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
