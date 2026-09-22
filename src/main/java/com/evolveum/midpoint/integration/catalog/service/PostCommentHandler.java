/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.integration.OpenProjectClient;
import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.service.retry.OperationResult;
import com.evolveum.midpoint.integration.catalog.service.retry.RetryableOperationHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Says on a work package something the catalog already worked out, so a portal that was unreachable
 * at the time costs a delay rather than the sentence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostCommentHandler implements RetryableOperationHandler<PostCommentHandler.Comment> {

    /** Stored verbatim on the pending row, so it may not be changed once rows carry it. */
    public static final String OPERATION = "POST_COMMENT";

    private final OpenProjectClient openProjectClient;

    /** One comment owed to one work package, as it will be said. */
    public record Comment(int workPackageId, String markdown) {
    }

    @Override
    public ExternalSystem system() {
        return ExternalSystem.OPENPROJECT;
    }

    @Override
    public String operation() {
        return OPERATION;
    }

    @Override
    public Class<Comment> payloadType() {
        return Comment.class;
    }

    @Override
    public OperationResult execute(Comment comment) {
        try {
            openProjectClient.addComment(comment.workPackageId(), comment.markdown());
            log.debug("Commented on support work package {}", comment.workPackageId());
            return OperationResult.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while commenting on work package {} in the support portal",
                    comment.workPackageId(), e);
            return OperationResult.retry("Interrupted while commenting on work package #"
                    + comment.workPackageId() + ".");
        } catch (Exception e) {
            log.error("Could not comment on work package {} in the support portal",
                    comment.workPackageId(), e);
            return OperationResult.retry("Could not comment on work package #" + comment.workPackageId()
                    + ": " + SupportTicketFailures.reason(e));
        }
    }
}
