/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Request;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface RequestRepository extends JpaRepository<Request, Long>,
        JpaSpecificationExecutor<Request> {

    List<Request> findByApplicationId(UUID applicationId);

    long countById(Long id);
    // long countById(Long requestId);
}
