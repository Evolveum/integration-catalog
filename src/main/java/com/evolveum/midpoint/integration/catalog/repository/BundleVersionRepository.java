/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.BundleVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BundleVersionRepository extends JpaRepository<BundleVersion, Integer>,
        JpaSpecificationExecutor<BundleVersion> {

    boolean existsByConnectorBundleIdAndConnectorVersion(Integer connectorBundleId, String connectorVersion);

    boolean existsByConnectorVersion(String connectorVersion);
}
