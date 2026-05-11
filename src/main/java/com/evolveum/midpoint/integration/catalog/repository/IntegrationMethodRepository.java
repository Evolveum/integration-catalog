/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodId;
import com.evolveum.midpoint.integration.catalog.object.LifecycleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface IntegrationMethodRepository extends JpaRepository<IntegrationMethod, IntegrationMethodId>,
        JpaSpecificationExecutor<IntegrationMethod> {

    List<IntegrationMethod> findByApplicationId(UUID applicationId);

    List<IntegrationMethod> findByApplicationIdAndLifecycleState(UUID applicationId, LifecycleType lifecycleState);
}
