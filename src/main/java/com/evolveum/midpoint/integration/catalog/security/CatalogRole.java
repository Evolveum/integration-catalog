/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

/**
 * The application roles, as carried by the identity provider's roles claim. The names
 * must match what the provider emits exactly.
 */
public enum CatalogRole {
    SUPERUSER("Superuser", true),
    ORGANIZATION_CONTRIBUTOR("OrganizationContributor", true),
    INDIVIDUAL_CONTRIBUTOR("IndividualContributor", true),
    READ_ONLY("ReadOnly", false);

    private final String identifier;
    private final boolean canEdit;

    CatalogRole(String identifier, boolean canEdit) {
        this.identifier = identifier;
        this.canEdit = canEdit;
    }

    public String getIdentifier() {
        return identifier;
    }
    public boolean canEdit() {
        return canEdit;
    }

//    public static String categoryOf(String role) {
//        if (role == null) {
//            return null;
//        }
//        //TODO this isn't true, user can have INDIVIDUAL_CONTRIBUTOR, but is from Partner
//        return switch (role) {
//            case SUPERUSER -> "Evolveum";
//            case ORGANIZATION_CONTRIBUTOR -> "Partner";
//            case INDIVIDUAL_CONTRIBUTOR -> "Community";
//            default -> null;
//        };
//    }
}
