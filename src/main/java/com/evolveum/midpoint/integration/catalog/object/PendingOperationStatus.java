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
 * <p>Stored by {@link #name()}, so constants may be added but not renamed.
 */
public enum PendingOperationStatus {

    /**
     * Written and not yet carried out.
     */
    PENDING,

    /** Carried out against the external system. Kept as a record; never attempted again. */
    DONE,

    /**
     * No longer worth attempting.
     */
    OBSOLETE,

    /**
     * Given up on after the configured number of attempts.
     */
    ABANDONED
}
