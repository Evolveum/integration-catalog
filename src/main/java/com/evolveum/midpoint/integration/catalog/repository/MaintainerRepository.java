/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Maintainer;
import com.evolveum.midpoint.integration.catalog.object.MaintainerType;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * The canonical maintainers: one row per person, per organization, and one each for the two
 * catalog-wide categories. The unique indexes enforce that, so a maintainer is looked up before
 * it is created.
 */
public interface MaintainerRepository extends JpaRepository<Maintainer, Long> {

    /** Only meaningful for EVOLVEUM and COMMUNITY, the categories that have a single row. */
    Optional<Maintainer> findByCategory(MaintainerType identifier);

    Optional<Maintainer> findByCategoryAndUsernameIgnoreCase(MaintainerType category, String username);

    Optional<Maintainer> findByCategoryAndOrganization(MaintainerType category, Organization organization);
}