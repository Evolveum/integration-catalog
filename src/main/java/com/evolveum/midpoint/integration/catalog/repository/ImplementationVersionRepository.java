/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;
import com.evolveum.midpoint.integration.catalog.object.Request;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * Created by Tomas.
 */
public interface ImplementationVersionRepository extends JpaRepository<ImplementationVersion, UUID>,
        JpaSpecificationExecutor<ImplementationVersion> {
}
