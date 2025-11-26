/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface ConnectorBundleRepository extends JpaRepository<ConnectorBundle, Integer>,
        JpaSpecificationExecutor<ConnectorBundle> {

    Optional<ConnectorBundle> findByBundleName(String bundleName);
    Optional<ConnectorBundle> findByBundleVersions_ImplementationVersions_Id(UUID implementationVersionId);
}
