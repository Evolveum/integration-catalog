/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ConnectorBundle;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersion;
import com.evolveum.midpoint.integration.catalog.object.ConnectorBundleVersionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConnectorBundleVersionRepository extends JpaRepository<ConnectorBundleVersion, ConnectorBundleVersionId>,
        JpaSpecificationExecutor<ConnectorBundleVersion> {

    List<ConnectorBundleVersion> findByConnectorBundleId(Integer connectorBundleId);

    Optional<ConnectorBundleVersion> findByConnectorBundleIdAndBundleVersion(Integer connectorBundleId, String bundleVersion);

    boolean existsByConnectorBundleIdAndBundleVersion(Integer connectorBundleId, String bundleVersion);

    /** Owners of items currently designated to a maintainer; feeds the maintainer options. */
    List<ItemOwnerView> findDistinctByMaintainerIsNotNull();

    /** Owners of items uploaded on behalf of the given organization. */
    List<ItemOwnerView> findDistinctByAuthorOrgId(String authorOrgId);
    /**
     * Re-parents every version of {@code source} onto {@code target}. Written as a bulk update rather
     * than by moving entities between the two {@code bundleVersions} collections: those use
     * orphanRemoval, so a move would schedule the rows for deletion instead.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ConnectorBundleVersion v set v.connectorBundle = :target where v.connectorBundle = :source")
    int moveAllToBundle(@Param("source") ConnectorBundle source, @Param("target") ConnectorBundle target);

    /** Bulk delete, so the row goes without JPA cascading into connectors that have already been moved. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ConnectorBundleVersion v where v.id = :id and v.revision = :revision")
    int deleteRow(@Param("id") Integer id, @Param("revision") String revision);
}
