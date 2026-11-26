/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.LifecycleType;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Deletes integration method revisions nobody resubmitted within the retention after their
 * rejection, so they don't have to be removed from the database by hand.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RejectedRevisionCleanupJob {

    private final IntegrationMethodRepository integrationMethodRepository;
    private final ConnectorUploadService connectorUploadService;

    @Value("${rejected-cleanup.retention-days:90}")
    private int retentionDays;

    /**
     * Each revision is deleted in its own transaction, so one failure doesn't keep the rest.
     *
     * @return how many revisions were deleted
     */
    @Scheduled(cron = "${rejected-cleanup.cron:0 0 3 * * *}")
    public int purgeExpiredRejections() {
        LocalDateTime rejectedBefore = LocalDateTime.now().minusDays(retentionDays);
        List<IntegrationMethod> expired = integrationMethodRepository
                .findByLifecycleStateAndUpdatedBefore(LifecycleType.REJECTED, rejectedBefore);
        int purged = 0;
        for (IntegrationMethod method : expired) {
            try {
                if (connectorUploadService.purgeRejectedIntegrationMethod(
                        method.getId(), method.getRevision(), rejectedBefore)) {
                    purged++;
                }
            } catch (Exception e) {
                log.error("Could not delete rejected integration method {}/{}: {}",
                        method.getId(), method.getRevision(), e.getMessage(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Deleted {} of {} integration method revision(s) rejected more than {} days ago",
                    purged, expired.size(), retentionDays);
        }
        return purged;
    }
}
