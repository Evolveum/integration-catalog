/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.service.retry.RetryableOperationHandler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the support portal's operations with the retry queue.
 *
 * <p>This is the whole of what integrating a system with the mechanism takes: a bean per operation,
 * saying which system it belongs to, what it is called on the pending row, what its payload is, and
 * which method performs it. A second external system is another class exactly like this one - no
 * column, no query and no change to the scheduled job.
 *
 * <p>The payload of each is the event that raised it, so the handler is handed the identity of what
 * to act on and reads the current state itself. See {@link RetryableOperationHandler} for why that
 * matters to an operation carried out a day after it arose.
 */
@Configuration
public class SupportTicketRetryHandlers {

    /** Opens the work package of a submitted revision, or rewrites the one it already has. */
    @Bean
    public RetryableOperationHandler<IntegrationMethodSubmittedEvent> openWorkPackageHandler(
            SupportTicketService supportTicketService) {
        return RetryableOperationHandler.of(
                ExternalSystem.OPENPROJECT,
                SupportTicketService.OPEN_WORK_PACKAGE,
                IntegrationMethodSubmittedEvent.class,
                supportTicketService::openWorkPackage);
    }

    /** Appends a connector added to a revision under review to that revision's work package. */
    @Bean
    public RetryableOperationHandler<ConnectorAddedToReviewEvent> appendConnectorHandler(
            SupportTicketService supportTicketService) {
        return RetryableOperationHandler.of(
                ExternalSystem.OPENPROJECT,
                SupportTicketService.APPEND_CONNECTOR,
                ConnectorAddedToReviewEvent.class,
                supportTicketService::appendConnector);
    }

    /** Attaches one of the author's uploaded files to the work package of its revision. */
    @Bean
    public RetryableOperationHandler<TutorialFileAddedEvent> attachFileHandler(
            SupportTicketService supportTicketService) {
        return RetryableOperationHandler.of(
                ExternalSystem.OPENPROJECT,
                SupportTicketService.ATTACH_FILE,
                TutorialFileAddedEvent.class,
                supportTicketService::attachTutorialFile);
    }
}
