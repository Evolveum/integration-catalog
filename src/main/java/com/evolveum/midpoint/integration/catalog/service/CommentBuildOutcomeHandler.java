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
import com.evolveum.midpoint.integration.catalog.service.event.BuildFinishedEvent;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;
import com.evolveum.midpoint.integration.catalog.service.retry.RetryableOperationHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments the outcome of a build onto the work package of the revision it was built for, so a
 * reviewer sees the artifact arrive - or sees why it did not - without opening the catalog.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentBuildOutcomeHandler implements RetryableOperationHandler<BuildFinishedEvent> {

    /** Stored verbatim on the pending row, so it may not be changed once rows carry it. */
    public static final String OPERATION = "COMMENT_BUILD_OUTCOME";

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
    public Class<BuildFinishedEvent> payloadType() {
        return BuildFinishedEvent.class;
    }

    /**
     * @return whether the outcome is now on the work package, and if not, whether asking again
     * could change that
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationResult execute(BuildFinishedEvent event) {
        if (!properties.enabled()) {
            log.debug("Support portal is not configured, so the build outcome of {}/{} keeps waiting",
                    event.methodId(), event.revision());
            return OperationResult.retry("No support portal is configured (openproject.url is empty).");
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null || method.getSupportTicketId() == null) {
            log.debug("Revision {}/{} has no work package to report its build outcome on",
                    event.methodId(), event.revision());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " has no work package; its build state is described by the one that opens it.");
        }

        try {
            openProjectClient.addComment(method.getSupportTicketId(), descriptionBuilder.buildBuildOutcome(event));
            log.info("Reported a {} build on support work package {} of integration method {}/{}",
                    event.succeeded() ? "successful" : "failed", method.getSupportTicketId(),
                    event.methodId(), event.revision());
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while reporting a build outcome on support work package {}",
                    method.getSupportTicketId(), e);
            return OperationResult.retry("Interrupted while reporting a build outcome on work package #"
                    + method.getSupportTicketId() + ".");
        } catch (Exception e) {
            log.error("Failed to report a build outcome on support work package {}: {}",
                    method.getSupportTicketId(), e.getMessage());
            return OperationResult.retry("Could not report a build outcome on work package #"
                    + method.getSupportTicketId() + ": " + SupportTicketFailures.reason(e));
        }
    }
}
