/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.OwnedItem;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns an author or maintainer named on a catalog item into a contact address and an organization.
 *
 * <p>Both come off the item itself, stamped there when the row was written (see
 * {@link OwnershipService}), not from a directory: users live in the identity provider and a token
 * describes only its own bearer, so there is nothing to ask for the address of someone else. The
 * consequence is that only the author has an address - the person who submitted is the one whose
 * token the catalog saw. A miss is therefore normal and resolves to empty rather than to an error.
 */
@Component
@RequiredArgsConstructor
public class CatalogContactResolver {

    private final OrganizationService organizationService;

    /**
     * The address to reach the named party at.
     *
     * @param item the item they are named on
     * @param name the author or maintainer name as it appears on that item
     * @return their address, or empty for anyone but the author, and for an author who was
     *         stamped before the column existed or whose token carried no address
     */
    public Optional<String> emailOf(OwnedItem item, String name) {
        if (item == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        if (!name.trim().equalsIgnoreCase(trimmed(item.getAuthor()))) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getAuthorEmail())
                .map(String::trim)
                .filter(email -> !email.isEmpty());
    }

    /**
     * The organization the named party acts for: the one they published on behalf of when they are
     * the item's author, the maintaining one when they are its maintainer. Empty for an individual
     * contributor, who acts as themselves even while belonging to an organization.
     */
    public Optional<String> organizationOf(OwnedItem item, String name) {
        if (item == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        String organizationId = null;
        if (trimmed.equalsIgnoreCase(trimmed(item.getAuthor()))) {
            organizationId = item.getAuthorOrgId();
        } else if (trimmed.equalsIgnoreCase(trimmed(item.getMaintainer()))) {
            organizationId = item.getMaintainerOrgId();
        }
        return Optional.ofNullable(organizationService.displayName(organizationId))
                .filter(organizationName -> !organizationName.isBlank());
    }

    private static String trimmed(String value) {
        return value != null ? value.trim() : null;
    }
}
