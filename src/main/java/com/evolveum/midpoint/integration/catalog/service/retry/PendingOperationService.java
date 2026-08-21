/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;
import com.evolveum.midpoint.integration.catalog.object.PendingOperation;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries out operations against external systems in a way that survives those systems being
 * unavailable: every operation is written down before it is attempted, and stays written down until
 * it has been carried out.
 *
 * <p>The flow a caller sees is one method - {@link #submit} - and the usual outcome is that the
 * operation happens immediately, exactly as a direct call would have. What changes is what happens
 * when it does not: instead of a log line and a lost operation, there is a pending row, which
 * {@link PendingOperationRetryJob} keeps offering back until it succeeds.
 *
 * <p>Nothing here knows what any operation is. Which system, which operation and what it needs are
 * the caller's; performing it is a {@link RetryableOperationHandler}'s. This class only owns the
 * order of events - record, attempt, record the result - and the registry that pairs a row with the
 * handler that answers to it, which is what lets a second external system be added without touching
 * any of it.
 */
@Slf4j
@Service
public class PendingOperationService {

    private final PendingOperationStore store;
    private final ObjectMapper objectMapper;

    /** Which handler answers for which (system, operation) pair, fixed at startup. */
    private final Map<HandlerKey, RetryableOperationHandler<?>> handlers = new HashMap<>();

    /**
     * @param handlers every registered handler, which is how a newly added external system becomes
     *                 known here: declaring the bean is the whole registration
     * @throws IllegalStateException if two handlers claim the same operation of the same system,
     *                               which would otherwise silently leave one of them unreachable
     */
    public PendingOperationService(PendingOperationStore store, ObjectMapper objectMapper,
                                   List<RetryableOperationHandler<?>> handlers) {
        this.store = store;
        this.objectMapper = objectMapper;
        for (RetryableOperationHandler<?> handler : handlers) {
            HandlerKey key = new HandlerKey(handler.system(), handler.operation());
            RetryableOperationHandler<?> clash = this.handlers.put(key, handler);
            if (clash != null) {
                throw new IllegalStateException("Two handlers registered for " + key + ": "
                        + clash.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        log.info("Retryable operations registered: {}", this.handlers.keySet());
    }

    /**
     * Records an operation as owed to an external system and attempts it straight away.
     *
     * <p>Recording first is what the caller is really buying. The row is committed before the
     * external system is touched, so the operation is already durable when the call is made and
     * every way that call can go wrong - refused, timed out, or the application killed in the
     * middle of it - leaves the same pending row behind. On success the row is settled and the
     * caller is none the wiser.
     *
     * <p>Never throws. A caller reaching this point has already done the thing the operation
     * accompanies, and must not be failed for something an external system owes.
     *
     * @param payload identifies what the operation is about; kept as JSON and handed back to the
     *                handler on every attempt, so it should name what to act on rather than freeze
     *                the request - see {@link RetryableOperationHandler}
     */
    public void submit(ExternalSystem system, String operation, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // The payload types are the catalog's own records; this is a programming error rather
            // than anything an outage or a retry could fix.
            log.error("Could not write down the {} operation {}, so it was not performed: {}",
                    system, operation, e.getMessage(), e);
            return;
        }

        PendingOperation pending;
        try {
            pending = store.record(system, operation, json);
        } catch (Exception e) {
            // The database is the one dependency there is no fallback for. Nothing is attempted,
            // because an operation performed without a row is one nobody could ever check on.
            log.error("Could not write down the {} operation {}, so it was not performed: {}",
                    system, operation, e.getMessage(), e);
            return;
        }
        attempt(pending);
    }

    /**
     * Attempts one recorded operation and records what came of it.
     *
     * <p>Used both for the immediate attempt on {@link #submit} and by the scheduled retry, which
     * is deliberate: an operation carried out a day late goes through exactly the code that would
     * have carried it out at once, so the late path is never the untested one.
     *
     * @return the outcome, for a caller counting them; the row has already been updated to match
     */
    OperationOutcome attempt(PendingOperation pending) {
        HandlerKey key = new HandlerKey(pending.getTargetSystem(), pending.getOperation());
        RetryableOperationHandler<?> handler = handlers.get(key);
        if (handler == null) {
            // An operation written by a build that knew a handler this one does not. Retrying it
            // forever would only bury whatever else is pending behind it.
            log.error("No handler for {}, so pending operation {} was abandoned", key, pending.getId());
            store.abandon(pending.getId(), "No handler registered for " + key);
            return OperationOutcome.OBSOLETE;
        }

        Object payload;
        try {
            payload = objectMapper.readValue(pending.getPayload(), handler.payloadType());
        } catch (Exception e) {
            // Written by a build whose payload had a different shape. It will not become readable.
            log.error("Could not read the payload of pending operation {} ({}), so it was abandoned: {}",
                    pending.getId(), key, e.getMessage());
            store.abandon(pending.getId(), "Unreadable payload: " + e.getMessage());
            return OperationOutcome.OBSOLETE;
        }

        OperationResult result;
        try {
            @SuppressWarnings("unchecked") // payload was read as exactly handler.payloadType()
            RetryableOperationHandler<Object> typed = (RetryableOperationHandler<Object>) handler;
            result = typed.execute(payload);
            if (result == null) {
                // A handler that answers nothing has said nothing about whether the operation
                // happened; the safe reading is that it did not.
                result = OperationResult.retry("Handler " + key + " returned no result");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = OperationResult.retry("Interrupted");
        } catch (Exception e) {
            // Read as retryable on purpose: an unexpected failure says nothing about whether the
            // operation can succeed later, and the safe reading leaves it visible instead of lost.
            log.error("Pending operation {} ({}) failed: {}", pending.getId(), key, e.getMessage(), e);
            result = OperationResult.retry(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            // The reason goes onto the row, not only into the log: the table is where anyone asking
            // why an operation is still owed will look, and a bare "not done" answers nothing.
            store.attempted(pending.getId(), result.outcome(), result.detail());
        } catch (Exception e) {
            // The operation may well have been performed; only the bookkeeping failed. Left pending
            // by the failure, it is attempted again, which is why handlers have to be repeatable.
            log.error("Could not record the outcome of pending operation {}: {}",
                    pending.getId(), e.getMessage(), e);
        }
        return result.outcome();
    }

    /**
     * Attempts everything one external system is still owed, oldest first.
     *
     * <p>One system at a time, because that is the unit that goes down: a portal being unreachable
     * should neither delay nor be delayed by operations owed to anything else.
     *
     * @return how many operations were carried out, of however many were attempted
     */
    public int retryPending(ExternalSystem system) {
        List<PendingOperation> pending = store.pending(system);
        if (pending.isEmpty()) {
            return 0;
        }
        long total = store.countPending(system);
        if (total > pending.size()) {
            // Said out loud rather than passed over, so a backlog worked off in batches does not
            // read as a run that handled everything there was.
            log.info("{} operations are owed to {}; taking the oldest {} in this run",
                    total, system, pending.size());
        }

        int completed = 0;
        for (PendingOperation operation : pending) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("Retry of {} was interrupted; {} operations were not attempted in this run",
                        system, pending.size() - completed);
                break;
            }
            if (attempt(operation) == OperationOutcome.COMPLETED) {
                completed++;
            }
        }
        log.info("Retried {} operations owed to {}, of which {} succeeded", pending.size(), system, completed);
        return completed;
    }

    /** What selects a handler: which external system, and which of its operations. */
    private record HandlerKey(ExternalSystem system, String operation) {

        @Override
        public String toString() {
            return system + "/" + operation;
        }
    }
}
