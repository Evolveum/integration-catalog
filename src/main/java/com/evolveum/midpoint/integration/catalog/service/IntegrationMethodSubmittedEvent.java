/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import java.util.UUID;

/**
 * Raised when a revision is put in front of a reviewer, so its support work package is opened
 * after the submitting transaction commits rather than inside it.
 */
public record IntegrationMethodSubmittedEvent(UUID methodId, String revision, SubmissionFlow flow,
                                              String previousRevision) {
}
