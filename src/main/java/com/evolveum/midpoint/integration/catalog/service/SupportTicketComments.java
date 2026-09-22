/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.service.retry.PendingOperationStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Says something on a work package without letting the saying of it fail the work that earned it.
 * Every comment the catalog adds explains something already done, so a comment that cannot be posted
 * now must not undo it - but it must not be lost either.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportTicketComments {

    private final OpenProjectClient openProjectClient;
    private final PendingOperationStore pendingOperationStore;
    private final ObjectMapper objectMapper;

    /** Posts the comment when there is one to post; an empty optional means nothing to say. */
    public void post(int workPackageId, Optional<String> comment) {
        if (comment.isEmpty()) {
            return;
        }
        try {
            openProjectClient.addComment(workPackageId, comment.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while commenting on work package {} in the support portal", workPackageId, e);
            owe(workPackageId, comment.get());
        } catch (Exception e) {
            log.error("Could not comment on work package {} in the support portal, so it is owed the comment",
                    workPackageId, e);
            owe(workPackageId, comment.get());
        }
    }

    /**
     * Writes the comment down for {@link PostCommentHandler} to say later. Best effort in its own
     * right: a comment that cannot even be written down is logged and let go, since the alternative
     * is failing the operation that already succeeded.
     */
    private void owe(int workPackageId, String markdown) {
        try {
            String payload = objectMapper.writeValueAsString(
                    new PostCommentHandler.Comment(workPackageId, markdown));
            pendingOperationStore.record(ExternalSystem.OPENPROJECT, PostCommentHandler.OPERATION, payload);
        } catch (Exception e) {
            log.error("Could not write down the comment owed to work package {}, so it is lost",
                    workPackageId, e);
        }
    }
}
