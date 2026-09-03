/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

/**
 * What a {@link RetryableOperationHandler} made of one attempt, and why.
 */
public record OperationResult(OperationOutcome outcome, String detail) {

    /** The external system has done it. Settled, and nothing left to explain. */
    public static OperationResult completed() {
        return new OperationResult(OperationOutcome.COMPLETED, null);
    }

    /**
     * It did not happen and might on a later attempt. The operation stays pending and the scheduled
     * retry picks it up.
     */
    public static OperationResult retry(String reason) {
        return new OperationResult(OperationOutcome.RETRY, reason);
    }

    /**
     * It will never happen, and that is fine: what it was about is gone or has been overtaken.
     * Terminal.
     */
    public static OperationResult obsolete(String reason) {
        return new OperationResult(OperationOutcome.OBSOLETE, reason);
    }

    /** Whether the external system carried the operation out. */
    public boolean isCompleted() {
        return outcome == OperationOutcome.COMPLETED;
    }

    /** Whether it will never happen, so a caller can stop rather than offer it again. */
    public boolean isObsolete() {
        return outcome == OperationOutcome.OBSOLETE;
    }
}
