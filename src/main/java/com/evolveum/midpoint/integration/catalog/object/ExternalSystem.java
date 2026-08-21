/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * An external system the catalog performs operations against, and which can therefore be
 * temporarily unreachable.
 *
 * <p>Stamped on every {@link PendingOperation} so the scheduled retry can be narrowed to one
 * system - a portal that is down should not hold up, or be held up by, anything else. It is also
 * what makes the mechanism extensible: supporting another system is a constant here plus a
 * {@code RetryableOperationHandler} for each of its operations, with no change to the table, the
 * repository or the job.
 *
 * <p>Stored by {@link #name()}, so constants may be added but not renamed - a renamed constant
 * orphans the rows already written under the old spelling.
 */
public enum ExternalSystem {

    /**
     * The support portal that holds the review conversation for a submitted integration method.
     * See {@code OpenProjectClient}.
     */
    OPENPROJECT
}
