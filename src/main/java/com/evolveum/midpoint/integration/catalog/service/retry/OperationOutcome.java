/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

/**
 * What a {@link RetryableOperationHandler} made of one attempt, and therefore whether the operation
 * is owed to the external system any more.
 *
 * <p>A verdict rather than an exception, because the interesting distinction is not "did it throw"
 * but "is it worth trying again". A handler that throws is treated as {@link #RETRY}, which is the
 * safe reading of an unexpected failure - the operation stays owed and a human sees it in the table
 * rather than nowhere.
 */
public enum OperationOutcome {

    /** The external system has done it. The operation is settled and never attempted again. */
    COMPLETED,

    /**
     * It did not happen and might on a later attempt - the system was unreachable, refused the
     * request, or timed out. The operation stays pending and the scheduled retry picks it up.
     */
    RETRY,

    /**
     * It will never happen, and that is fine: what it was about is gone or has been overtaken, so
     * there is nothing left to ask for. Terminal, so a row whose subject no longer exists does not
     * sit in the queue being retried forever.
     */
    OBSOLETE
}
