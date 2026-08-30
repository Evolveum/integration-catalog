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
     */
    List<PendingOperation> findByTargetSystemAndStatusOrderByIdAsc(
            ExternalSystem targetSystem, PendingOperationStatus status, Limit limit);

    /** How many operations are still owed to one system, for the summary the retry job logs. */
    long countByTargetSystemAndStatus(ExternalSystem targetSystem, PendingOperationStatus status);
}
