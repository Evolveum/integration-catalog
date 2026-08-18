/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import java.util.UUID;

/**
 * Raised when a revision is put in front of a reviewer — a first upload, a new draft forked off a
 * published revision, or a rejected revision resubmitted. Consumed after the submitting
 * transaction commits, so that opening the support portal's work package cannot roll the
 * submission back or hold its transaction open across a network call.
 *
 * @param methodId identifies the integration method
 * @param revision identifies the submitted revision of that method
 * @param flow     which action the submitter took, which names the work package - see
 *                 {@link SubmissionFlow} for why it is passed rather than derived
 */
public record IntegrationMethodSubmittedEvent(UUID methodId, String revision, SubmissionFlow flow) {
}
