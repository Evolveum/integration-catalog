/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.object.PendingOperation;
import com.evolveum.midpoint.integration.catalog.object.PendingOperationStatus;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Operations the catalog owes an external system. Only the retry job and
 * {@code PendingOperationService} read this; nothing in the catalog's own flows does.
 */
public interface PendingOperationRepository extends JpaRepository<PendingOperation, Long> {

    /**
     * The operations still owed to one external system, oldest first, capped at a batch size.
     *
     * <p>Oldest first so a backlog is worked off in the order it built up - a create attempted
     * before the edit that follows it, rather than the other way round. Capped so that a run after
     * a long outage cannot occupy the scheduler thread indefinitely; whatever is left over is
     * simply still pending at the next run.
     */
    List<PendingOperation> findByTargetSystemAndStatusOrderByIdAsc(
            ExternalSystem targetSystem, PendingOperationStatus status, Limit limit);

    /** How many operations are still owed to one system, for the summary the retry job logs. */
    long countByTargetSystemAndStatus(ExternalSystem targetSystem, PendingOperationStatus status);
}
