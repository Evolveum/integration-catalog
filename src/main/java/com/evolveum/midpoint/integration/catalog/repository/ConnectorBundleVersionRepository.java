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
import java.util.UUID;

public interface ConnectorBundleVersionRepository extends JpaRepository<ConnectorBundleVersion, ConnectorBundleVersionId>,
        JpaSpecificationExecutor<ConnectorBundleVersion> {

    List<ConnectorBundleVersion> findByConnectorBundleId(Integer connectorBundleId);

    Optional<ConnectorBundleVersion> findByConnectorBundleIdAndBundleVersion(Integer connectorBundleId, String bundleVersion);

    boolean existsByConnectorBundleIdAndBundleVersion(Integer connectorBundleId, String bundleVersion);

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

    /**
     * Sets the tier on every revision of the bundle version, so a pending edit cannot fold an old
     * tier back in on approve. Native, so {@code updated} is left alone: connector download picks
     * the latest bundle version by it.
     *
     * @param tier a {@code SupportTier} name, null to clear
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE connector_bundle_version SET support_tier = CAST(:tier AS SupportTier) WHERE id = :id",
            nativeQuery = true)
    int updateSupportTier(@Param("id") Integer id, @Param("tier") String tier);
}
