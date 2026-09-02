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
 */
@Slf4j
@Service
public class PendingOperationService {

    private final PendingOperationStore store;
    private final ObjectMapper objectMapper;

    /** Which handler answers for which (system, operation) pair, fixed at startup. */
    private final Map<HandlerKey, RetryableOperationHandler<?>> handlers = new HashMap<>();

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
     */
    public void submit(ExternalSystem system, String operation, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Could not write down the {} operation {}, so it was not performed: {}",
                    system, operation, e.getMessage(), e);
            return;
        }

        PendingOperation pending;
        try {
            pending = store.record(system, operation, json);
        } catch (Exception e) {
            log.error("Could not write down the {} operation {}, so it was not performed: {}",
                    system, operation, e.getMessage(), e);
            return;
        }
        attempt(pending);
    }

    /**
     * Attempts one recorded operation and records what came of it.
     */
    OperationOutcome attempt(PendingOperation pending) {
        HandlerKey key = new HandlerKey(pending.getTargetSystem(), pending.getOperation());
        RetryableOperationHandler<?> handler = handlers.get(key);
        if (handler == null) {
            log.error("No handler for {}, so pending operation {} was abandoned", key, pending.getId());
            store.abandon(pending.getId(), "No handler registered for " + key);
            return OperationOutcome.OBSOLETE;
        }

        Object payload;
        try {
            payload = objectMapper.readValue(pending.getPayload(), handler.payloadType());
        } catch (Exception e) {
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
                result = OperationResult.retry("Handler " + key + " returned no result");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = OperationResult.retry("Interrupted");
        } catch (Exception e) {
            log.error("Pending operation {} ({}) failed: {}", pending.getId(), key, e.getMessage(), e);
            result = OperationResult.retry(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            store.attempted(pending.getId(), result.outcome(), result.detail());
        } catch (Exception e) {
            log.error("Could not record the outcome of pending operation {}: {}",
                    pending.getId(), e.getMessage(), e);
        }
        return result.outcome();
    }

    /**
     * Attempts everything one external system is still owed, oldest first.
     */
    public int retryPending(ExternalSystem system) {
        List<PendingOperation> pending = store.pending(system);
        if (pending.isEmpty()) {
            return 0;
        }
        long total = store.countPending(system);
        if (total > pending.size()) {
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
