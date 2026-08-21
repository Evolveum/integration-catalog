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
 *
 * <p>Each listener does one thing: write the operation down and hand it to
 * {@link PendingOperationService}, which attempts it immediately and keeps it pending if the portal
 * cannot take it. What the operation actually does lives in {@link SupportTicketService}, and is
 * reached from here only through that queue - so the path taken when the portal is up and the path
 * taken by the scheduled retry are the same path.
 *
 * <p>Its own class rather than methods on {@link SupportTicketService} because the queue's handlers
 * are wired to that service ({@link SupportTicketRetryHandlers}); a service that both submitted to
 * the queue and was called by it would be a dependency cycle.
 *
 * <p>Every listener runs after the raising transaction has committed. The catalog's own work is
 * durable by then, so a portal that is down, slow or misconfigured cannot roll any of it back - and
 * nothing is enqueued at all while the portal is unconfigured, which keeps a deployment that does
 * not use it from accumulating operations nobody will ever perform.
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
