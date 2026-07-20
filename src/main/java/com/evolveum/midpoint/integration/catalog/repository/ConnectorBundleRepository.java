/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.LifecycleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ConnectorBundleRepository extends JpaRepository<ConnectorBundle, Integer>,
        JpaSpecificationExecutor<ConnectorBundle> {

    Optional<ConnectorBundle> findByBundleName(String bundleName);

    boolean existsByBundleName(String bundleName);

    boolean existsByBundleNameAndRevision(String bundleName, String revision);

    List<ConnectorBundle> findByLifecycleState(LifecycleType lifecycleState);
}
