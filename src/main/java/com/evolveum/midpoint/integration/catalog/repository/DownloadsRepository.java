/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Downloads;
import com.evolveum.midpoint.integration.catalog.utils.Inet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Created by Tomas.
 */
public interface DownloadsRepository extends JpaRepository<Downloads, UUID>,
        JpaSpecificationExecutor<Downloads> {

    boolean existsRecentDuplicate(
            UUID implementationVersion,
            Inet ipAddress,
            String userAgent,
            OffsetDateTime downloadedAt);

    long countByImplementationVersionId(Integer implementationVersionId);
}
