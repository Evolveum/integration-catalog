/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import java.util.UUID;

/**
 * Raised when a connector is added to a revision that is <em>already</em> under review, so its work
 * package exists and the reviewer has possibly started reading it.
 *
 * <p>Deliberately not an {@link IntegrationMethodSubmittedEvent}: nothing was submitted anew, the
 * scope of the submission in front of the reviewer simply grew. Opening a second work package would
 * split one review across two conversations, so this one is appended to the existing work package as
 * a comment instead.
 *
 * <p>Adding a connector to a <em>published</em> revision raises no such event. That forks a fresh
 * draft, which is a submission of its own and gets its own work package through
 * {@link SubmissionFlow#UPGRADE}.
 *
 * @param methodId    identifies the integration method
 * @param revision    identifies the revision under review that the connector was added to
 * @param connectorId the connector that was added, so the comment can describe just that one rather
 *                    than repeating every connector on the revision
 */
public record ConnectorAddedToReviewEvent(UUID methodId, String revision, Integer connectorId) {
}
