/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

/**
 * What a {@link RetryableOperationHandler} made of one attempt, and why.
 *
 * <p>The reason is the whole point of the record. Without it a failure is only ever a log line, and
 * the row in {@code pending_operation} says that something did not work without saying what - which
 * is the one question anyone reading that table is asking. Carried here, it is stored in
 * {@code last_error} and stands next to the operation it belongs to for as long as the row does.
 *
 * @param outcome whether the operation is still owed to the external system
 * @param detail  why, in terms a reader of the table can act on, or null when there is nothing to
 *                say - which should be the case only for {@link OperationOutcome#COMPLETED}
 */
public record OperationResult(OperationOutcome outcome, String detail) {

    /** The external system has done it. Settled, and nothing left to explain. */
    public static OperationResult completed() {
        return new OperationResult(OperationOutcome.COMPLETED, null);
    }

    /**
     * It did not happen and might on a later attempt. The operation stays pending and the scheduled
     * retry picks it up.
     *
     * @param reason what stopped it - the portal being unreachable, a request refused, a timeout
     */
    public static OperationResult retry(String reason) {
        return new OperationResult(OperationOutcome.RETRY, reason);
    }

    /**
     * It will never happen, and that is fine: what it was about is gone or has been overtaken.
     * Terminal.
     *
     * @param reason what became of the subject, so a closed row is not mistaken for a lost one
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
