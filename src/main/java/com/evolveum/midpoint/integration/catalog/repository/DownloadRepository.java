/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersion;
import com.evolveum.midpoint.integration.catalog.object.Download;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;

/**
 * Created by Tomas.
 */
public interface DownloadRepository extends JpaRepository<Download, Integer>,
        JpaSpecificationExecutor<Download> {

    boolean existsByConnectorBundleVersionAndIpAddressAndUserAgentAndDownloadedAt(
            ConnectorBundleVersion connectorBundleVersion,
            String ipAddress,
            String userAgent,
            LocalDateTime downloadedAt);

    long countByConnectorBundleVersion(ConnectorBundleVersion connectorBundleVersion);
}
