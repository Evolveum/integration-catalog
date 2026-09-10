/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

import com.evolveum.midpoint.integration.catalog.object.ApiKey;

import java.time.Instant;

/**
 * One API key as the settings page lists it; carries no key material.
 */
public record ApiKeyDto(
        String id,
        String name,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt
) {

    public static ApiKeyDto of(ApiKey key) {
        return new ApiKeyDto(
                key.getId().toString(),
                key.getName(),
                key.getCreatedAt(),
                null,
                key.getExpiresAt(),
                key.getRevokedAt());
    }
}
