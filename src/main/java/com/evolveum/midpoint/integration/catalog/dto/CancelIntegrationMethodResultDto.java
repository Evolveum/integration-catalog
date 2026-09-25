/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.dto;

/**
 * Outcome of cancelling a revision under review.
 *
 * @param applicationDeleted whether the application went with it, because the cancelled revision
 *                           was all it had and it was never published
 */
public record CancelIntegrationMethodResultDto(boolean applicationDeleted) {
}
