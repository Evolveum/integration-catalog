/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Identity questions, answered from the OIDC token claims of the current session plus the
 * organizations table — the application talks to no identity-provider administration API.
 *
 * A token only ever describes its own bearer, so questions about other users are
 * answered from what was recorded on the item when it was written: its author, its
 * maintainer, and the organization identifiers stamped alongside them.
 */
@Service
public class AuthService {

    private final OrganizationService organizationService;
    private final CatalogOwnerDirectory catalogOwnerDirectory;
    private final CatalogClaims claims;

    public AuthService(OrganizationService organizationService,
                       CatalogOwnerDirectory catalogOwnerDirectory,
                       CatalogClaims claims) {
        this.organizationService = organizationService;
        this.catalogOwnerDirectory = catalogOwnerDirectory;
        this.claims = claims;
    }

    /**
     * The authenticated user's profile. Everything but the organization's display name
     * comes straight from the token; the claim carries the organization's identifier only,
     * so the name is resolved from the organizations table (falling back to the identifier
     * when the organization has not been seeded there yet).
     */
    public CurrentUserDto getCurrentUser(String username, OidcUser oidcUser) {
        String role = CatalogRole.READ_ONLY;
        String organizationId = null;
        String organizationName = null;
        if (oidcUser != null) {
            role = claims.effectiveRole(oidcUser);
            organizationId = claims.organizationId(oidcUser);
            String resolved = organizationService.displayName(organizationId);
            organizationName = resolved != null ? resolved : organizationId;
        }
        return new CurrentUserDto(
                username,
                oidcUser != null ? oidcUser.getFullName() : null,
                oidcUser != null ? oidcUser.getEmail() : null,
                role,
                organizationId,
                organizationName
        );
    }

    /**
     * Maintainer options for a superuser: every user already designated as a maintainer,
     * plus every organization.
     *
     * Without a user table the first half can only come from the maintainers recorded on
     * catalog items, so someone who has never been given an item to maintain is not offered
     * here — they become selectable once they are made a maintainer of one.
     */
    public List<String> getAllMaintainers() {
        List<String> result = new ArrayList<>(catalogOwnerDirectory.findAllMaintainers());
        result.addAll(organizationService.allNames());
        return result;
    }

    /**
     * Whether {@code username} may see/modify an item authored by {@code author} on behalf
     * of {@code authorOrganizationId} and maintained by {@code maintainer} /
     * {@code maintainerOrganizationId}.
     */
    public boolean canEdit(String username, String author, String authorOrganizationId,
                           String maintainer, String maintainerOrganizationId) {
        if (username == null || username.isBlank()) {
            return false;
        }
        OidcUser caller = currentOidcUser();
        if (caller == null) {
            return false;
        }
        String callerRole = claims.effectiveRole(caller);
        if (CatalogRole.SUPERUSER.equals(callerRole)) {
            return true;
        }
        if (maintainer != null && maintainer.equalsIgnoreCase(username)) {
            return true;
        }
        String callerOrganizationId = claims.organizationId(caller);
        // An organization acts as a team: whatever it maintains, all of its members may edit.
        if (callerOrganizationId != null && maintainerOrganizationId != null
                && callerOrganizationId.equalsIgnoreCase(maintainerOrganizationId)) {
            return true;
        }
        if (author != null && author.equalsIgnoreCase(username)) {
            return true;
        }
        // Uploads made on behalf of the caller's organization belong to the whole organization.
        return CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(callerRole)
                && callerOrganizationId != null && authorOrganizationId != null
                && callerOrganizationId.equalsIgnoreCase(authorOrganizationId);
    }

    /** Whether {@code username} is the current Superuser. Used to gate approval actions. */
    public boolean isSuperuser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        OidcUser caller = currentOidcUser();
        return caller != null && CatalogRole.SUPERUSER.equals(claims.effectiveRole(caller));
    }

    /**
     * Usernames sharing the caller's organization; just the caller when they have none.
     *
     * Derived from the items published on behalf of that organization, so it lists the
     * organization's contributors rather than every account in it.
     */
    public List<String> getOrganizationMembers(String username) {
        OidcUser caller = currentOidcUser();
        String organizationId = caller != null ? claims.organizationId(caller) : null;
        if (organizationId == null || organizationId.isBlank()) {
            return List.of(username);
        }
        List<String> members = new ArrayList<>(
                catalogOwnerDirectory.findAuthorsOfOrganization(organizationId));
        if (!members.contains(username)) {
            members.add(username);
        }
        return members;
    }

    private static OidcUser currentOidcUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser
                ? oidcUser
                : null;
    }
}
