/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Download;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;
import com.evolveum.midpoint.integration.catalog.repository.adapter.InetAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Created by Tomas.
 */
public interface DownloadRepository extends JpaRepository<Download, UUID>,
        JpaSpecificationExecutor<Download> {

        boolean existsByImplementationVersionAndIpAddressAndUserAgentAndDownloadedAt(ImplementationVersion implementationVersion,
                                      InetAddress ipAddress,
                                      String userAgent,
                                      OffsetDateTime downloadedAt);

    long countByImplementationVersionId(UUID implementationVersionId);
}
