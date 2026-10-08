/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * The answer to a key creation, and the only response ever carrying the value: nothing stores it,
 * so it cannot be shown again.
 */
public record CreatedApiKeyDto(ApiKeyDto key, String value, boolean expirationAccepted) {
}
