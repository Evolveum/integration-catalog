/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import java.util.List;

/**
 * The application roles as assigned in Keycloak (realm roles) and stored in
 * catalog_users.role. The names must match both places exactly.
 * <p>
 * Ordered by precedence: when a Keycloak user carries several catalog roles,
 * the strongest one becomes their effective catalog_users.role.
 */
public final class CatalogRole {

    public static final String SUPERUSER = "Superuser";
    public static final String ORGANIZATION_CONTRIBUTOR = "OrganizationContributor";
    public static final String INDIVIDUAL_CONTRIBUTOR = "IndividualContributor";
    public static final String READ_ONLY = "ReadOnly";

    /** Strongest first. */
    public static final List<String> BY_PRECEDENCE =
            List.of(SUPERUSER, ORGANIZATION_CONTRIBUTOR, INDIVIDUAL_CONTRIBUTOR, READ_ONLY);

    private CatalogRole() {
    }
}
