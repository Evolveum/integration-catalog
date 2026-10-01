/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.object.LifecycleType;
import com.evolveum.midpoint.integration.catalog.object.Maintainer;
import com.evolveum.midpoint.integration.catalog.object.SupportTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationMethodRepository extends JpaRepository<IntegrationMethod, IntegrationMethodId>,
        JpaSpecificationExecutor<IntegrationMethod> {

    List<IntegrationMethod> findByApplicationId(UUID applicationId);

    List<IntegrationMethod> findByApplicationIdAndLifecycleState(UUID applicationId, LifecycleType lifecycleState);

    Optional<IntegrationMethod> findFirstByIdOrderByCreatedAtDesc(UUID id);
    List<IntegrationMethod> findByLifecycleState(LifecycleType lifecycleState);

    List<IntegrationMethod> findByMaintainerIn(Collection<Maintainer> maintainers);

    /** The tier belongs to the method, not to one revision, so every revision gets it. */
    @Modifying
    @Query("UPDATE IntegrationMethod m SET m.supportTier = :tier WHERE m.id = :id")
    int updateSupportTier(@Param("id") UUID id, @Param("tier") SupportTier tier);
}
