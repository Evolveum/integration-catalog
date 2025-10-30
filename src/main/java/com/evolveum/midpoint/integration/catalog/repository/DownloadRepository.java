/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Download;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;
import com.evolveum.midpoint.integration.catalog.utils.InetAddress;
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
