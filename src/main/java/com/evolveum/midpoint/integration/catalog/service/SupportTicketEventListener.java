/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.service.retry.PendingOperationService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns what happens in the catalog into operations owed to the support portal.
 */
@Component
@RequiredArgsConstructor
public class SupportTicketEventListener {

    private final PendingOperationService pendingOperationService;
    private final OpenProjectProperties properties;

    /** A revision was put in front of a reviewer, so the portal owes it a work package. */
    @TransactionalEventListener
    public void onIntegrationMethodSubmitted(IntegrationMethodSubmittedEvent event) {
        submit(SupportTicketService.OPEN_WORK_PACKAGE, event);
    }

    /** A connector was added to a revision already under review, so its work package owes a comment. */
    @TransactionalEventListener
    public void onConnectorAddedToReview(ConnectorAddedToReviewEvent event) {
        submit(SupportTicketService.APPEND_CONNECTOR, event);
    }

    /** A file was stored for a revision, so the work package of a revision under review owes an attachment. */
    @TransactionalEventListener
    public void onTutorialFileAdded(TutorialFileAddedEvent event) {
        submit(SupportTicketService.ATTACH_FILE, event);
    }

    /**
     * Records one operation and attempts it.
     *
     * <p>The event is the payload, which is what lets a late attempt be a correct one: it names the
     * revision rather than describing it, so the handler reads the submission as it stands when it
     * finally runs.
     */
    private void submit(String operation, Object event) {
        if (!properties.enabled()) {
            return;
        }
        pendingOperationService.submit(ExternalSystem.OPENPROJECT, operation, event);
    }
}
