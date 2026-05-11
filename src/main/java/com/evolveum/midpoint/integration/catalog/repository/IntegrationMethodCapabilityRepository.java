/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.IntegrationMethodCapability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface IntegrationMethodCapabilityRepository extends JpaRepository<IntegrationMethodCapability, Integer>,
        JpaSpecificationExecutor<IntegrationMethodCapability> {

    List<IntegrationMethodCapability> findByIntegrationMethodId(UUID integrationMethodId);
}
