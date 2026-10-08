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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOperationRetryJob {

    private final PendingOperationService pendingOperationService;

    /**
     * Retries everything still owed, system by system.
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
