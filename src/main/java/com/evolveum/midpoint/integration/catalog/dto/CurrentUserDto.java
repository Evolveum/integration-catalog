/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import java.util.List;

/**
 * The authenticated user's profile as the frontend sees it: identity claims from
 * Keycloak (username, full name, email, groups) merged with the provisioned local
 * data (role, organization).
 */
public record CurrentUserDto(
        String username,
        String fullName,
        String email,
        String role,
        Integer organizationId,
        String organizationName,
        List<String> groups
) {
}
