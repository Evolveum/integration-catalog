/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersionId;
import com.evolveum.midpoint.integration.catalog.object.LifecycleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ConnectorVersionRepository extends JpaRepository<ConnectorVersion, ConnectorVersionId>,
        JpaSpecificationExecutor<ConnectorVersion> {

    List<ConnectorVersion> findByConnectorId(Integer connectorId);

    List<ConnectorVersion> findByConnectorIdAndLifecycleState(Integer connectorId, LifecycleType lifecycleState);
}
