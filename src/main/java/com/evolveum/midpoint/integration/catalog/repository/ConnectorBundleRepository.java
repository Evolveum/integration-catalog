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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConnectorBundleRepository extends JpaRepository<ConnectorBundle, Integer>,
        JpaSpecificationExecutor<ConnectorBundle> {

    Optional<ConnectorBundle> findByBundleNameAndLifecycleState(String bundleName, LifecycleType lifecycleState);

    boolean existsByBundleNameAndRevision(String bundleName, String revision);

    List<ConnectorBundle> findByLifecycleState(LifecycleType lifecycleState);

    /**
     * Deletes an emptied bundle row. A bulk delete on purpose: {@code delete(entity)} would cascade
     * REMOVE into the connectors still held in the entity's in-memory collection, which is exactly what
     * a merge has just moved somewhere else.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ConnectorBundle b where b.id = :id")
    int deleteRow(@Param("id") Integer id);
}
