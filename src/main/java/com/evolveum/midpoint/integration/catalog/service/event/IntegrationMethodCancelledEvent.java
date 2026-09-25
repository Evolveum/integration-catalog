/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.event;

import java.util.UUID;

/**
 * Raised when the author withdraws a revision from review, so the reviewer learns why the work
 * package has nothing left to review. The revision row is deleted along with the cancellation, so
 * the work package id travels on the event rather than being read back.
 */
public record IntegrationMethodCancelledEvent(UUID methodId, String revision, int workPackageId, String cancelledBy) {
}
