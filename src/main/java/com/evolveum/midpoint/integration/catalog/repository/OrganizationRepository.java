/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.repository;

import com.evolveum.midpoint.integration.catalog.object.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Integer> {

    /**
     * Looks an organization up by the name it is referred to by elsewhere, e.g. the value
     * {@code integration_method.maintainer} holds when a submission is maintained by an
     * organization rather than a person. Case-insensitive, because that name is a free-text
     * column rather than a foreign key.
     */
    Optional<Organization> findByNameIgnoreCase(String name);
}
