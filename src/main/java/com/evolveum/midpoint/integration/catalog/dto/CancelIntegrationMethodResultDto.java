/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * Outcome of cancelling a revision under review.
 */
public record CancelIntegrationMethodResultDto(boolean applicationDeleted) {
}
