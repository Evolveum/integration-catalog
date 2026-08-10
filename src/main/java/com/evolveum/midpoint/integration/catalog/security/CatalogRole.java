/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import java.util.List;

/**
 * The application roles, as carried by the identity provider's roles claim. The names
 * must match what the provider emits exactly.
 * <p>
 * Ordered by precedence: when a user carries several catalog roles, the strongest one
 * becomes their effective role.
 */
public final class CatalogRole {

    public static final String SUPERUSER = "Superuser";
    public static final String ORGANIZATION_CONTRIBUTOR = "OrganizationContributor";
    public static final String INDIVIDUAL_CONTRIBUTOR = "IndividualContributor";
    public static final String READ_ONLY = "ReadOnly";

    /** Strongest first. */
    public static final List<String> BY_PRECEDENCE =
            List.of(SUPERUSER, ORGANIZATION_CONTRIBUTOR, INDIVIDUAL_CONTRIBUTOR, READ_ONLY);

    /**
     * The maintainer category the catalog shows for an item, derived from the role of the
     * user who uploaded it: Superuser → Evolveum, OrganizationContributor → Partner,
     * IndividualContributor → Community. Null for anything else (e.g. ReadOnly), which is
     * how an item without a category is rendered.
     */
    public static String categoryOf(String role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case SUPERUSER -> "Evolveum";
            case ORGANIZATION_CONTRIBUTOR -> "Partner";
            case INDIVIDUAL_CONTRIBUTOR -> "Community";
            default -> null;
        };
    }

    private CatalogRole() {
    }
}
