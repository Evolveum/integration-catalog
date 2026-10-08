/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * The authenticated user's profile as the frontend sees it. Mostly OIDC claims, so a claim the
 * provider does not emit arrives here as null.
 */
public record CurrentUserDto(
        String username,
        String firstName,
        String lastName,
        String email,
        String role,
        String organizationName,
        String organizationDisplayName
) {
}
