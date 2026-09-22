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
import com.evolveum.midpoint.integration.catalog.service.event.IntegrationMethodSubmittedEvent;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;
import com.evolveum.midpoint.integration.catalog.service.retry.RetryableOperationHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Opens a work package for a submitted revision, or rewrites the one it already has. Runs after the
 * submitting transaction commits, in its own transaction, so an unreachable portal costs the author
 * nothing but time: the operation stays pending and the scheduled retry opens the work package once
 * the portal is back. Until then the revision reports no ticket, so the approval dialog has nothing
 * to wait for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenWorkPackageHandler implements RetryableOperationHandler<IntegrationMethodSubmittedEvent> {

    /** Stored verbatim on the pending row, so it may not be changed once rows carry it. */
    public static final String OPERATION = "OPEN_WORK_PACKAGE";

    private final IntegrationMethodRepository integrationMethodRepository;
    private final OpenProjectClient openProjectClient;
    private final OpenProjectProperties properties;
    private final SupportTicketDescriptionBuilder descriptionBuilder;
    private final SupportTicketDeltaBuilder deltaBuilder;
    private final SupportTicketComments comments;
    private final SupportTicketAttachments attachments;
    private final SupportTicketWatchers watchers;

    @Override
    public ExternalSystem system() {
        return ExternalSystem.OPENPROJECT;
    }

    @Override
    public String operation() {
        return OPERATION;
    }

    @Override
    public Class<IntegrationMethodSubmittedEvent> payloadType() {
        return IntegrationMethodSubmittedEvent.class;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationResult execute(IntegrationMethodSubmittedEvent event) {
        if (!properties.enabled()) {
            log.debug("Support portal is not configured, so {}/{} keeps waiting for its work package",
                    event.methodId(), event.revision());
            return OperationResult.retry("No support portal is configured (openproject.url is empty).");
        }
        IntegrationMethod method = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.revision()))
                .orElse(null);
        if (method == null) {
            log.error("Revision {}/{} no longer exists, so no work package is opened for it",
                    event.methodId(), event.revision());
            return OperationResult.obsolete("Revision " + event.methodId() + "/" + event.revision()
                    + " no longer exists; whatever replaced it carries the work package.");
        }
        if (method.getSupportTicketId() != null) {
            return rewriteWorkPackage(method.getSupportTicketId(), method, event);
        }

        return createWorkPackage(method, event);
    }

    private @NonNull OperationResult createWorkPackage(IntegrationMethod method, IntegrationMethodSubmittedEvent event) {
        try {
            int workPackageId = openProjectClient.createWorkPackage(
                    event.flow().taskName(method.getDisplayName()), descriptionBuilder.build(method));
            method.setSupportTicketId(workPackageId);
            log.info("Opened work package {} for integration method {}/{} in the support portal",
                    workPackageId, event.methodId(), event.revision());
            attachments.attachAll(workPackageId, method);
            watchers.addAll(workPackageId, method);
            commentOnEditOfPublishedRevision(workPackageId, method, event);
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while opening a work package for integration method {}/{} in the support portal",
                    event.methodId(), event.revision(), e);
            return OperationResult.retry("Interrupted while opening the work package.");
        } catch (Exception e) {
            log.error("Failed to open a work package for integration method {}/{} in the support portal",
                    event.methodId(), event.revision(), e);
            return OperationResult.retry("Could not open the work package: " + SupportTicketFailures.reason(e));
        }
    }

    /**
     * Rewrites the work package of a revision edited while under review, so the reviewer reads the
     * submission as it stands. A portal that cannot be reached leaves the operation pending for the
     * scheduled retry.
     */
    private OperationResult rewriteWorkPackage(int workPackageId, IntegrationMethod method,
                                               IntegrationMethodSubmittedEvent event) {
        try {
            String titleOfWorkPackage = SubmissionFlow.renamed(
                    openProjectClient.getTitleOfWorkPackage(workPackageId).orElse(null), method.getDisplayName());
            if (titleOfWorkPackage == null) {
                // Nothing to keep - the portal has no subject to build on, so name it by this flow.
                titleOfWorkPackage = event.flow().taskName(method.getDisplayName());
            }
            String description = descriptionBuilder.build(method);
            Optional<String> replaced = openProjectClient.updateWorkPackage(workPackageId, titleOfWorkPackage, description);
            if (replaced.isEmpty()) {
                log.warn("Work package {} of integration method {}/{} no longer exists in the support portal, "
                        + "so the edit was not written to it", workPackageId, event.methodId(), event.revision());
                return OperationResult.obsolete("Work package #" + workPackageId
                        + " no longer exists in the support portal, so the edit could not be written to it.");
            }
            log.info("Updated work package {} after an edit of integration method {}/{} in the support portal",
                    workPackageId, event.methodId(), event.revision());
            List<String> fileChanges = attachments.refresh(workPackageId, method);

            // The summary cannot be recomposed later - it is worked out from the description the portal
            // held before this update replaced it - so a comment that does not go through is written
            // down whole and said by the retry, rather than retried from here.
            comments.post(workPackageId, deltaBuilder.compare(replaced.get(), description,
                    "This submission was edited while under review. The description above and the files"
                            + " attached to this work package are up to date; what changed is listed here.",
                    fileChanges));
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while updating work package {} of integration method {}/{} in the support portal",
                    workPackageId, event.methodId(), event.revision(), e);
            return OperationResult.retry("Interrupted while rewriting work package #" + workPackageId + ".");
        } catch (Exception e) {
            log.error("Failed to update work package {} of integration method {}/{} in the support portal",
                    workPackageId, event.methodId(), event.revision(), e);
            return OperationResult.retry("Could not rewrite work package #" + workPackageId
                    + " after the edit: " + SupportTicketFailures.reason(e));
        }
    }

    /**
     * Comments what a draft forked off a published revision changes about it, so the reviewer reviews
     * an edit rather than the whole method again. Only for {@link SubmissionFlow#EDIT}; best effort,
     * and last, so a failed comment costs nothing already done.
     */
    private void commentOnEditOfPublishedRevision(int workPackageId, IntegrationMethod method,
                                                  IntegrationMethodSubmittedEvent event) {
        if (event.flow() != SubmissionFlow.EDIT || event.previousRevision() == null) {
            return;
        }
        IntegrationMethod previous = integrationMethodRepository
                .findById(new IntegrationMethodId(event.methodId(), event.previousRevision()))
                .orElse(null);
        if (previous == null) {
            log.debug("Revision {}/{} edits {}, which no longer exists, so work package {} gets no"
                            + " summary of the edit",
                    event.methodId(), event.revision(), event.previousRevision(), workPackageId);
            return;
        }
        Optional<String> delta;
        try {
            delta = deltaBuilder.compare(
                    descriptionBuilder.build(previous), descriptionBuilder.build(method),
                    "This is an edit of revision " + event.previousRevision() + ", which it replaces once"
                            + " approved. What it changes about that revision is listed here.",
                    checkTutorialChange(previous, method));
        } catch (Exception e) {
            log.error("Could not work out what {}/{} changes about {}",
                    event.methodId(), event.revision(), event.previousRevision(), e);
            return;
        }
        comments.post(workPackageId, delta);
    }

    private static List<String> checkTutorialChange(IntegrationMethod before, IntegrationMethod after) {
        String was = blankToNull(before.getTutorial());
        String now = blankToNull(after.getTutorial());
        if (Objects.equals(was, now)) {
            return List.of();
        }
        String what = was == null ? SupportTicketAttachments.OBJECT_STATE_ADDED
                : now == null ? SupportTicketAttachments.OBJECT_STATE_REMOVED
                : SupportTicketAttachments.OBJECT_STATE_REPLACED;
        return List.of("`" + SupportTicketAttachments.TUTORIAL_ATTACHMENT + "` " + what);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
