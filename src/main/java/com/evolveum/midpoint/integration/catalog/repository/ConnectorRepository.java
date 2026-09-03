/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Connector;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;
import com.evolveum.midpoint.integration.catalog.object.LifecycleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConnectorRepository extends JpaRepository<Connector, Integer>,
        JpaSpecificationExecutor<Connector> {

    List<Connector> findByConnectorBundleId(Integer connectorBundleId);

    /** Owners of items currently designated to a maintainer; feeds the maintainer options. */
    List<ItemOwnerView> findDistinctByMaintainerIsNotNull();

    /** Owners of items uploaded on behalf of the given organization. */
    List<ItemOwnerView> findDistinctByAuthorOrgId(String authorOrgId);

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

    /**
     * Re-parents every connector of {@code source} onto {@code target}. A bulk update, because the
     * bundle's {@code connectors} collection uses orphanRemoval and cascades REMOVE: moving the
     * entities between the two collections would delete them instead.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Connector c set c.connectorBundle = :target where c.connectorBundle = :source")
    int moveAllToBundle(@Param("source") ConnectorBundle source, @Param("target") ConnectorBundle target);
}
