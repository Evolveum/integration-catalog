/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Maintainer;
import com.evolveum.midpoint.integration.catalog.object.MaintainerType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * The canonical maintainers. A person or an organization is looked up by its identifier.
 */
public interface MaintainerRepository extends JpaRepository<Maintainer, Long> {
    Optional<Maintainer> findByCategory(MaintainerType identifier);
}