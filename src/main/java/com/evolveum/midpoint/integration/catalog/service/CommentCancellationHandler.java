/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.service.event.IntegrationMethodCancelledEvent;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;
import com.evolveum.midpoint.integration.catalog.service.retry.RetryableOperationHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Tells the reviewer on the work package that the author withdrew the revision, so it is not
 * reviewed for nothing. The work package is left open for the reviewer to close.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentCancellationHandler implements RetryableOperationHandler<IntegrationMethodCancelledEvent> {

    /** Stored verbatim on the pending row, so it may not be changed once rows carry it. */
    public static final String OPERATION = "COMMENT_CANCELLATION";

    private final OpenProjectClient openProjectClient;
    private final OpenProjectProperties properties;

    @Override
    public ExternalSystem system() {
        return ExternalSystem.OPENPROJECT;
    }

    @Override
    public String operation() {
        return OPERATION;
    }

    @Override
    public Class<IntegrationMethodCancelledEvent> payloadType() {
        return IntegrationMethodCancelledEvent.class;
    }

    @Override
    public OperationResult execute(IntegrationMethodCancelledEvent event) {
        if (!properties.isEnabled()) {
            return OperationResult.retry("No support portal is configured (openproject.url is empty).");
        }
        try {
            openProjectClient.addComment(event.workPackageId(), "This submission (revision " + event.revision()
                    + ") was cancelled by " + event.cancelledBy() + " and removed from the catalog."
                    + " There is nothing left to review, so this work package can be closed.");
            log.info("Reported the cancellation of {}/{} on support work package {}",
                    event.methodId(), event.revision(), event.workPackageId());
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while reporting a cancellation on support work package {}",
                    event.workPackageId(), e);
            return OperationResult.retry("Interrupted while reporting a cancellation on work package #"
                    + event.workPackageId() + ".");
        } catch (Exception e) {
            log.error("Failed to report a cancellation on support work package {}: {}",
                    event.workPackageId(), e.getMessage());
            return OperationResult.retry("Could not report a cancellation on work package #"
                    + event.workPackageId() + ": " + SupportTicketFailures.reason(e));
        }
    }
}
