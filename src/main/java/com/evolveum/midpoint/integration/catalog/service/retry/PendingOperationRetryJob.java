/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service.retry;

import com.evolveum.midpoint.integration.catalog.object.ExternalSystem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Comes back to the operations external systems could not take when they arose, and offers them
 * again.
 *
 * <p>This is what turns "the portal was down" into a delay rather than a loss. Everything about
 * what to retry and how is elsewhere - the job's whole job is to run on a schedule and to work
 * through the systems one at a time, so that one unreachable system does not stall the rest.
 *
 * <p>Runs hourly by default; {@code pending-operations.cron} moves it, and {@code -} switches it
 * off for a deployment that would rather drive the retry itself. Operations are still recorded and
 * attempted immediately when the schedule is off - what is lost is only the second chance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOperationRetryJob {

    private final PendingOperationService pendingOperationService;

    /**
     * Retries everything still owed, system by system.
     *
     * <p>A system that fails outright is logged and stepped over rather than allowed to end the
     * run: the next system's operations have nothing to do with it, and this one will be offered
     * its operations again at the next run regardless.
     */
    @Scheduled(cron = "${pending-operations.cron:0 0 * * * *}")
    public void retryPendingOperations() {
        for (ExternalSystem system : ExternalSystem.values()) {
            try {
                pendingOperationService.retryPending(system);
            } catch (Exception e) {
                log.error("Retry of operations owed to {} ended early: {}", system, e.getMessage(), e);
            }
        }
    }
}
