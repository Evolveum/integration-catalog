/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;

/**
 * The authenticated user's profile as the frontend sees it — every field comes from
 * the Keycloak token claims (username, full name, email, role, organization, groups);
 * the catalog stores no user data of its own. The organization is identified by
 * {@code organizationId} (the Keycloak organization's immutable alias — stable across
 * renames) while {@code organizationName} is its current display name.
 */
public record CurrentUserDto(
        String username,
        String fullName,
        String email,
        String role,
        String organizationId,
        String organizationName,
        List<String> groups
) {
}
