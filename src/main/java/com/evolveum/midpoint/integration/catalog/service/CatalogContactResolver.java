/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.CatalogRole;
import com.evolveum.midpoint.integration.catalog.object.CatalogUser;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.repository.CatalogUserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns an author or maintainer name stamped on a revision into a contact address. Those columns are
 * free text and may name an organization or someone the catalog no longer knows, so a miss is normal
 * and resolves to empty rather than to an error.
 */
@Component
@RequiredArgsConstructor
public class CatalogContactResolver {

    private final CatalogUserRepository catalogUserRepository;

    /**
     * The address to reach the named party at.
     *
     * @return their address, or empty for an organization, an unknown name, or nobody's address
     */
    public Optional<String> emailOf(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return catalogUserRepository.findByUsername(name)
                .map(CatalogUser::getEmail)
                .filter(email -> !email.isBlank())
                .map(String::trim);
    }

    /**
     * The organization the named person publishes on behalf of. Only an
     * {@link CatalogRole#ORGANIZATION_CONTRIBUTOR} has one; an individual publishes as themselves.
     */
    public Optional<String> organizationOf(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return catalogUserRepository.findByUsername(name)
                .filter(user -> CatalogRole.ORGANIZATION_CONTRIBUTOR.matches(user.getRole()))
                .map(CatalogUser::getOrganization)
                .map(Organization::getName)
                .filter(organizationName -> !organizationName.isBlank());
    }
}
