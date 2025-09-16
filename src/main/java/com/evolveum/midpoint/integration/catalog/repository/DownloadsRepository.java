/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Downloads;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;
import com.evolveum.midpoint.integration.catalog.utils.Inet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface DownloadsRepository extends JpaRepository<Downloads, UUID>,
        JpaSpecificationExecutor<Downloads> {

    // quick check for existing entry - cutoff=10s
    @Query("""
           SELECT CASE WHEN COUNT(d) > 0 THEN TRUE ELSE FALSE END
           FROM Downloads d
           WHERE d.implementationVersion = :version
             AND d.ipAddress = :ip
             AND d.userAgent = :ua
             AND d.downloadedAt > :cutoff
           """)
    boolean existsRecentDuplicate(@Param("version") ImplementationVersion version,
                                  @Param("ip") Inet ip,
                                  @Param("ua") String userAgent,
                                  @Param("cutoff") OffsetDateTime cutoff);

    long countByImplementationVersion_Id(Integer implementationVersionId);
}
