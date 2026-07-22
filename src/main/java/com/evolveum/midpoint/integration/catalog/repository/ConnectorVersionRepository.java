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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConnectorVersionRepository extends JpaRepository<ConnectorVersion, ConnectorVersionId>,
        JpaSpecificationExecutor<ConnectorVersion> {

    List<ConnectorVersion> findByConnectorId(Integer connectorId);

    List<ConnectorVersion> findByConnectorIdAndLifecycleState(Integer connectorId, LifecycleType lifecycleState);

    List<ConnectorVersion> findByLifecycleState(LifecycleType lifecycleState);

    /**
     * Whether a connector with the same identity (bundle name + class name) already carries the given
     * version on ANOTHER connector row. The version is matched against both the version row's own
     * revision and the bundle version it points to, since edits keep the latter current. The class
     * name may live on the version row or only on the connector, so both are consulted; a null
     * className (low-code connectors) skips that part of the identity. Used to warn the reviewer
     * about a duplicate version — never to block it.
     */
    @Query("""
            select count(cv) > 0 from ConnectorVersion cv
            left join cv.connectorBundleVersion cbv
            where cv.connector.connectorBundle.bundleName = :bundleName
              and (:className is null
                   or cv.fullyQualifiedClassName = :className
                   or cv.connector.fullyQualifiedClassName = :className)
              and (cv.revision = :version or cbv.bundleVersion = :version)
              and (:excludeConnectorId is null or cv.connector.id <> :excludeConnectorId)
            """)
    boolean existsDuplicateVersion(@Param("bundleName") String bundleName,
                                   @Param("className") String className,
                                   @Param("version") String version,
                                   @Param("excludeConnectorId") Integer excludeConnectorId);
}
