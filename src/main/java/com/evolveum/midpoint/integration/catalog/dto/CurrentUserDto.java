/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * The authenticated user's profile as the frontend sees it — every field comes from
 * the token claims (username, full name, email, role, organization).
 */
public record CurrentUserDto(
        String username,
        String fullName,
        String email,
        String role,
        String organizationId,
        String organizationName
) {
}
