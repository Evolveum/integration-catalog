/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

import com.evolveum.midpoint.integration.catalog.configuration.PendingOperationProperties;
import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.object.PendingOperation;
import com.evolveum.midpoint.integration.catalog.object.PendingOperationStatus;
import com.evolveum.midpoint.integration.catalog.repository.PendingOperationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The database side of the retry queue: writing an operation down, and recording what an attempt
 * made of it.
 *
 * <p>Separate from {@link PendingOperationService}, which orchestrates, for one reason - every
 * method here commits on its own ({@link Propagation#REQUIRES_NEW}) and a self-invocation inside
 * one bean would not go through the proxy that makes that happen. The independence is the point of
 * the whole mechanism: the row has to be durable <em>before</em> the external system is called, and
 * has to survive whatever that call does, including the caller's transaction rolling back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOperationStore {

    /** Enough of a failure to diagnose it, without letting a stack-trace-sized message into a row. */
    private static final int MAX_ERROR_LENGTH = 2000;

    private final PendingOperationRepository repository;
    private final PendingOperationProperties properties;

    /**
     * Writes an operation down as {@link PendingOperationStatus#PENDING} and commits it, before
     * anybody tries to perform it.
     *
     * <p>This is what makes the mechanism hold: from here on the operation exists independently of
     * the process that raised it, so a portal that is unreachable, a call that hangs until the
     * client times out, and the application being killed mid-call all end the same way - a pending
     * row the scheduled retry will find.
     *
     * @return the committed row, with its generated id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PendingOperation record(ExternalSystem system, String operation, String payload) {
        PendingOperation pending = new PendingOperation();
        pending.setTargetSystem(system);
        pending.setOperation(operation);
        pending.setPayload(payload);
        pending.setStatus(PendingOperationStatus.PENDING);
        pending.setCreatedAt(LocalDateTime.now());
        return repository.save(pending);
    }

    /**
     * Records what one attempt made of an operation: what it counted as, when it ran, and why it
     * did not succeed if it did not.
     *
     * <p>{@link OperationOutcome#RETRY} leaves the row pending, which is what puts it in front of
     * the next scheduled run - unless it has now been attempted as often as
     * {@code pending-operations.max-attempts} allows, which by default is never.
     *
     * @param error what went wrong, for a failed attempt; ignored on success, where a leftover
     *              message from an earlier attempt would only mislead
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attempted(Long id, OperationOutcome outcome, String error) {
        PendingOperation pending = repository.findById(id).orElse(null);
        if (pending == null) {
            // Nothing to record against; the operation itself either happened or did not, and this
            // is only the bookkeeping of it.
            log.warn("Pending operation {} disappeared while it was being attempted", id);
            return;
        }
        pending.setAttempts(pending.getAttempts() + 1);
        pending.setLastAttemptAt(LocalDateTime.now());
        switch (outcome) {
            case COMPLETED -> {
                pending.setStatus(PendingOperationStatus.DONE);
                pending.setLastError(null);
            }
            case OBSOLETE -> {
                pending.setStatus(PendingOperationStatus.OBSOLETE);
                pending.setLastError(truncate(error));
            }
            case RETRY -> {
                pending.setLastError(truncate(error));
                if (properties.isExhausted(pending.getAttempts())) {
                    pending.setStatus(PendingOperationStatus.ABANDONED);
                    log.error("Giving up on {} operation {} (id {}) after {} attempts; last error: {}",
                            pending.getTargetSystem(), pending.getOperation(), id,
                            pending.getAttempts(), pending.getLastError());
                } else {
                    pending.setStatus(PendingOperationStatus.PENDING);
                }
            }
        }
        repository.save(pending);
    }

    /**
     * Gives up on an operation without attempting it, for the failures that are about the row
     * rather than about the external system - a payload that cannot be read, an operation no
     * handler answers to. Retrying changes nothing in either case, and the row is left saying so.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(Long id, String error) {
        repository.findById(id).ifPresent(pending -> {
            pending.setStatus(PendingOperationStatus.ABANDONED);
            pending.setLastAttemptAt(LocalDateTime.now());
            pending.setLastError(truncate(error));
            repository.save(pending);
        });
    }

    /** The operations one system is still owed, oldest first, at most {@code batchSize} of them. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<PendingOperation> pending(ExternalSystem system) {
        return repository.findByTargetSystemAndStatusOrderByIdAsc(
                system, PendingOperationStatus.PENDING, Limit.of(properties.batchSize()));
    }

    /** How many operations one system is still owed, including any beyond the current batch. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long countPending(ExternalSystem system) {
        return repository.countByTargetSystemAndStatus(system, PendingOperationStatus.PENDING);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
