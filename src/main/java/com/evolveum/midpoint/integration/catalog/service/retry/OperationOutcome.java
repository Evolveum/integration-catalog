/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

/**
 * What a {@link RetryableOperationHandler} made of one attempt, and therefore whether the operation
 * is owed to the external system any more.
 */
public enum OperationOutcome {

    /** The external system has done it. The operation is settled and never attempted again. */
    COMPLETED,

    /** It did not happen and might on a later attempt. */
    RETRY,

    /** It will never happen, and that is fine. */
    OBSOLETE
}
