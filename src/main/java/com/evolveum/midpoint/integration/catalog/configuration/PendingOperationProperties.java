/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the catalog retries operations an external system could not take when they arose.
 *
 * <p>Not per system on purpose: the queue is shared and so is the schedule. A system with its own
 * cadence would get its own job rather than its own copy of these.
 */
@ConfigurationProperties(prefix = "pending-operations")
public record PendingOperationProperties(
        String cron,
        int batchSize,
        int maxAttempts
) {

    /** Defaults, so a deployment that configures none of this still retries sensibly. */
    public PendingOperationProperties {
        cron = (cron == null || cron.isBlank()) ? "0 0 * * * *" : cron.trim();
        batchSize = batchSize > 0 ? batchSize : 100;
        maxAttempts = Math.max(maxAttempts, 0);
    }

    /** Whether an operation attempted this often has run out of attempts; never true by default. */
    public boolean isExhausted(int attempts) {
        return maxAttempts > 0 && attempts >= maxAttempts;
    }
}
