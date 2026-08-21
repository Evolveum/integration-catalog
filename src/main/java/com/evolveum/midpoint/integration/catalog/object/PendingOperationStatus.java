/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

/**
 * How far a {@link PendingOperation} has got. Only {@link #PENDING} is picked up by the scheduled
 * retry; the other three are terminal and exist so that a row records why it stopped instead of
 * disappearing.
 *
 * <p>Stored by {@link #name()} - see {@link ExternalSystem} for why constants may be added but not
 * renamed.
 */
public enum PendingOperationStatus {

    /**
     * Written and not yet carried out. Either the first attempt has not run, it failed, or the
     * application stopped between recording the operation and attempting it. The scheduled retry
     * picks these up until they reach one of the states below.
     */
    PENDING,

    /** Carried out against the external system. Kept as a record; never attempted again. */
    DONE,

    /**
     * No longer worth attempting, because what it refers to is gone or has been overtaken - a
     * revision superseded by an in-place edit, a work package deleted in the portal. Distinct from
     * {@link #ABANDONED}: nothing failed, there is simply nothing left to do.
     */
    OBSOLETE,

    /**
     * Given up on after the configured number of attempts. Only reachable when a maximum is
     * configured; by default operations are retried indefinitely, precisely so that a long outage
     * does not turn into a permanently missing task. A row in this state needs a human.
     */
    ABANDONED
}
