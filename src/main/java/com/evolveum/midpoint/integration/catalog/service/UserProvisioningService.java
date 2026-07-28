/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.CatalogUser;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.repository.CatalogUserRepository;
import com.evolveum.midpoint.integration.catalog.repository.OrganizationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mirrors the identity Keycloak asserts at login into the local catalog_users /
 * organizations tables. The local rows are what the ownership logic
 * ({@link AuthService#canEdit}, organization members, maintainer resolution)
 * queries, so every OIDC login upserts them from the token claims: Keycloak is
 * the source of truth, the database is a synchronized copy.
 */
@Slf4j
@Service
public class UserProvisioningService {

    private final CatalogUserRepository catalogUserRepository;
    private final OrganizationRepository organizationRepository;

    public UserProvisioningService(CatalogUserRepository catalogUserRepository,
                                   OrganizationRepository organizationRepository) {
        this.catalogUserRepository = catalogUserRepository;
        this.organizationRepository = organizationRepository;
    }

    /**
     * Creates or updates the catalog user row for a successful OIDC login.
     *
     * @param username         Keycloak preferred_username
     * @param role             effective catalog role (one of {@link com.evolveum.midpoint.integration.catalog.security.CatalogRole})
     * @param organizationName Keycloak "organization" user attribute; the organization row is
     *                         created on first sight, matched case-insensitively afterwards
     */
    @Transactional
    public void provision(String username, String role, String organizationName) {
        Organization organization = null;
        if (organizationName != null && !organizationName.isBlank()) {
            String name = organizationName.trim();
            organization = organizationRepository.findByNameIgnoreCase(name)
                    .orElseGet(() -> organizationRepository.save(new Organization().setName(name)));
        }

        CatalogUser user = catalogUserRepository.findByUsername(username)
                .orElseGet(() -> new CatalogUser().setUsername(username));
        boolean isNew = user.getRole() == null;
        user.setRole(role);
        user.setOrganization(organization);
        catalogUserRepository.save(user);
        log.info("{} catalog user '{}' from OIDC login: role={}, organization={}",
                isNew ? "Provisioned" : "Updated", username, role,
                organization != null ? organization.getName() : "none");
    }
}
