/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.service.event.ConnectorAddedToReviewEvent;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;
import com.evolveum.midpoint.integration.catalog.service.retry.RetryableOperationHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments a connector added to a revision already under review onto that revision's work package,
 * rather than opening a second one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppendConnectorHandler implements RetryableOperationHandler<ConnectorAddedToReviewEvent> {

    /** Stored verbatim on the pending row, so it may not be changed once rows carry it. */
    public static final String OPERATION = "APPEND_CONNECTOR";

    private final IntegrationMethodRepository integrationMethodRepository;
    private final OpenProjectClient openProjectClient;
    private final OpenProjectProperties properties;
    private final SupportTicketDescriptionBuilder descriptionBuilder;

    @Override
    public ExternalSystem system() {
        return ExternalSystem.OPENPROJECT;
    }

    @Override
    public String operation() {
        return OPERATION;
    }

    @Override
    public Class<ConnectorAddedToReviewEvent> payloadType() {
        return ConnectorAddedToReviewEvent.class;
    }

    /**
     * @return whether the connector is now on the work package, and if not, whether asking again
     * could change that
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationResult execute(ConnectorAddedToReviewEvent event) {
        if (!properties.enabled()) {
            log.debug("Support portal is not configured, so connector {} keeps waiting to be appended",
                    event.connectorId());
            return OperationResult.retry("No support portal is configured (openproject.url is empty).");
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null || method.getSupportTicketId() == null) {
            log.error("Revision {}/{} has no work package to append connector {} to",
                    event.methodId(), event.revision(), event.connectorId());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " has no work package; the connector is described by the one that opens it.");
        }

        try {
            openProjectClient.addComment(method.getSupportTicketId(),
                    descriptionBuilder.buildConnectorAddendum(method, event.connectorId()));
            log.info("Appended connector {} to work package {} of integration method {}/{} in the support portal",
                    event.connectorId(), method.getSupportTicketId(), event.methodId(), event.revision());
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while appending connector {} to work package {} in the support portal",
                    event.connectorId(), method.getSupportTicketId(), e);
            return OperationResult.retry("Interrupted while appending connector " + event.connectorId()
                    + " to work package #" + method.getSupportTicketId() + ".");
        } catch (Exception e) {
            log.error("Failed to append connector {} to work package {} in the support portal",
                    event.connectorId(), method.getSupportTicketId(), e);
            return OperationResult.retry("Could not append connector " + event.connectorId()
                    + " to work package #" + method.getSupportTicketId() + ": " + SupportTicketFailures.reason(e));
        }
    }
}
