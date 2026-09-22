/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.OpenProjectProperties;
import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.service.event.TutorialFileAddedEvent;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;
import com.evolveum.midpoint.integration.catalog.service.retry.RetryableOperationHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attaches one of the author's uploaded files, which arrive after the work package is opened and so
 * are missing from the description. The portal notes each in the activity itself, so nothing is
 * commented.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachTutorialFileHandler implements RetryableOperationHandler<TutorialFileAddedEvent> {

    /** Stored verbatim on the pending row, so it may not be changed once rows carry it. */
    public static final String OPERATION = "ATTACH_FILE";

    private final IntegrationMethodRepository integrationMethodRepository;
    private final OpenProjectProperties properties;
    private final SupportTicketAttachments attachments;

    @Override
    public ExternalSystem system() {
        return ExternalSystem.OPENPROJECT;
    }

    @Override
    public String operation() {
        return OPERATION;
    }

    @Override
    public Class<TutorialFileAddedEvent> payloadType() {
        return TutorialFileAddedEvent.class;
    }

    /**
     * @return whether the file is now on the work package, and if not, whether asking again could
     * change that
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationResult execute(TutorialFileAddedEvent event) {
        if (!properties.enabled()) {
            log.debug("Support portal is not configured, so {} keeps waiting to be attached",
                    event.fileName());
            return OperationResult.retry("No support portal is configured (openproject.url is empty).");
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null || method.getSupportTicketId() == null) {
            log.error("Revision {}/{} has no work package to attach {} to",
                    event.methodId(), event.revision(), event.fileName());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " has no work package; the file is attached by the operation that opens it.");
        }
        return attachments.attachStored(method.getSupportTicketId(), method, event.fileName());
    }
}
