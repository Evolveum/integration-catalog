/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.CatalogUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatalogUserRepository extends JpaRepository<CatalogUser, String> {

    Optional<CatalogUser> findByUsername(String username);

    List<CatalogUser> findByOrganizationId(Integer organizationId);
}
