/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Implementation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface ImplementationRepository extends JpaRepository<Implementation, UUID>,
        JpaSpecificationExecutor<Implementation> {

    /**
     * Find all implementations for a specific application
     * @param applicationId the application UUID
     * @return list of implementations
     */
    List<Implementation> findByApplicationId(UUID applicationId);
}
