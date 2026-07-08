/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ConnectorBundleVersionRepository extends JpaRepository<ConnectorBundleVersion, ConnectorBundleVersionId>,
        JpaSpecificationExecutor<ConnectorBundleVersion> {

    List<ConnectorBundleVersion> findByConnectorBundleId(Integer connectorBundleId);

    Optional<ConnectorBundleVersion> findByConnectorBundleIdAndBundleVersion(Integer connectorBundleId, String bundleVersion);

    boolean existsByConnectorBundleIdAndBundleVersion(Integer connectorBundleId, String bundleVersion);
}
