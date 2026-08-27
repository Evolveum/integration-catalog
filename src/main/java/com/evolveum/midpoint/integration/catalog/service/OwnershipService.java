/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.object.OwnedItem;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Records on a catalog item who owns it, at the moment it is written.
 */
@Service
public class OwnershipService {

    private final OrganizationService organizationService;
    private final CatalogClaims claims;

    public OwnershipService(OrganizationService organizationService, CatalogClaims claims) {
        this.organizationService = organizationService;
        this.claims = claims;
    }

    /**
     * Stamps a newly written item: {@code username} authored it, and {@code requestedMaintainer}
     * (a username or an organization name, as chosen in the publish form) maintains it.
     */
    public void stampNew(OwnedItem item, String username, String requestedMaintainer) {
        OidcUser caller = currentOidcUser();
        String callerRole = caller != null ? claims.effectiveRole(caller) : null;
        String callerOrganizationId = caller != null ? claims.organizationId(caller) : null;

        item.setAuthor(username);
        // Only an organization contributor publishes on behalf of their organization; an
        // individual contributor who happens to belong to one still publishes as themselves.
        item.setAuthorOrgId(CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(callerRole)
                ? callerOrganizationId
                : null);
        item.setAuthorCategory(CatalogRole.categoryOf(callerRole));
        assignMaintainer(item, requestedMaintainer);
    }

    /**
     * Sets the maintainer of an item that already exists, as chosen in the edit form. An
     * organization name becomes an organization reference; a username stays a username, and
     * carries the caller's organization when they maintain on its behalf.
     */
    public void assignMaintainer(OwnedItem item, String requestedMaintainer) {
        String organizationId = organizationService.idOfName(requestedMaintainer);
        if (organizationId != null) {
            item.setMaintainer(null);
            item.setMaintainerOrgId(organizationId);
            return;
        }
        item.setMaintainer(requestedMaintainer);
        item.setMaintainerOrgId(organizationOfMaintainer(requestedMaintainer));
    }

    /** Carries an item's whole ownership over to a new revision, draft or clone of it. */
    public void copyOwnership(OwnedItem from, OwnedItem to) {
        to.setAuthor(from.getAuthor());
        to.setAuthorOrgId(from.getAuthorOrgId());
        to.setAuthorCategory(from.getAuthorCategory());
        copyMaintainer(from, to);
    }

    /** Carries only the maintainer over, for a flow that keeps the target's own author. */
    public void copyMaintainer(OwnedItem from, OwnedItem to) {
        to.setMaintainer(from.getMaintainer());
        to.setMaintainerOrgId(from.getMaintainerOrgId());
    }

    /**
     * The organization to record for a maintainer who is a person. Only the caller's own
     * organization can be known — a superuser may name any user as maintainer, and there is
     * no directory to look that user's organization up in, so the item then simply carries
     * no maintaining organization.
     */
    private String organizationOfMaintainer(String maintainer) {
        OidcUser caller = currentOidcUser();
        if (caller == null || maintainer == null || maintainer.isBlank()) {
            return null;
        }
        String callerName = caller.getPreferredUsername() != null
                ? caller.getPreferredUsername()
                : caller.getSubject();
        if (callerName == null || !maintainer.trim().equalsIgnoreCase(callerName.trim())) {
            return null;
        }
        return CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(claims.effectiveRole(caller))
                ? claims.organizationId(caller)
                : null;
    }

    private static OidcUser currentOidcUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser
                ? oidcUser
                : null;
    }
}
