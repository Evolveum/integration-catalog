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
 *
 * @param cron        when the retry runs, as a Spring cron expression (six fields, seconds first).
 *                    The default is hourly, which is well inside the "at least once a day" the
 *                    mechanism has to guarantee and shortens the window in which a submission has
 *                    no task after a brief outage. Set {@code -} to switch the schedule off, which
 *                    leaves operations recorded and attempted immediately but never retried.
 * @param batchSize   how many operations one run of one system takes on. A cap rather than a
 *                    target: it keeps a run after a long outage from occupying the scheduler
 *                    thread indefinitely, and whatever is left over is still pending at the next
 *                    run.
 * @param maxAttempts how often one operation is attempted before it is abandoned, counting the
 *                    immediate attempt made when it arose. Zero, the default, means indefinitely -
 *                    an outage lasting longer than any fixed number of attempts must not turn into
 *                    a permanently missing task, and an operation that can never succeed is
 *                    normally recognised as such by its handler and marked obsolete instead. Set a
 *                    positive number only where a stuck row is worse than a lost operation.
 */
@ConfigurationProperties(prefix = "pending-operations")
public record PendingOperationProperties(
        String cron,
        int batchSize,
        int maxAttempts
) {

    /** Cron expression that switches the scheduled retry off; Spring's own "never" value. */
    public static final String DISABLED_CRON = "-";

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
