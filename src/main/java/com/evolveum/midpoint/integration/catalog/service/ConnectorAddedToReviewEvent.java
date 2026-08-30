/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import java.util.UUID;

/**
 * Raised when a connector is added to a revision that is already under review, so the addition is
 * commented onto the existing work package instead of opening a second one.
 */
public record ConnectorAddedToReviewEvent(UUID methodId, String revision, Integer connectorId) {
}
