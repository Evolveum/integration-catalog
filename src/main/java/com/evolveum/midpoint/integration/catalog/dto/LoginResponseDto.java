/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

public record LoginResponseDto(
        String username,         // catalog_users.username
        String role,             // catalog_users.role
        Integer organizationId,  // organizations.id
        String organizationName  // organizations.name
) {}
