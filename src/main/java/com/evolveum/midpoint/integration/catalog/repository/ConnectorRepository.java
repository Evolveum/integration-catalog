/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Connector;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;
import com.evolveum.midpoint.integration.catalog.object.LifecycleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ConnectorRepository extends JpaRepository<Connector, Integer>,
        JpaSpecificationExecutor<Connector> {

    List<Connector> findByConnectorBundleId(Integer connectorBundleId);

    /**
     * Retrieves distinct connectors associated with connector versions
     * having the given lifecycle state.
     *
     * @param lifecycleState the lifecycle state to filter connector versions by
     * @return distinct connectors whose versions match the given lifecycle state
     */
    List<Connector> findDistinctByConnectorVersionsLifecycleState(LifecycleType lifecycleState);

    /**
     * Connectors copy-on-write cloned from the given one. Used when that original is retired, so its
     * clones can be repointed at the connector taking its place instead of being left dangling.
     */
    List<Connector> findByClonedFrom(Integer clonedFrom);
}
