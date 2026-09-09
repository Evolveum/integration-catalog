/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserDirectory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Identity questions, answered from the token claims of the current session plus the organizations
 * table. A token describes only its bearer, so anything about other users is read from what was
 * stamped on the item when it was written.
 */
@Service
public class AuthService {

    private final OrganizationService organizationService;
    private final CatalogOwnerDirectory catalogOwnerDirectory;
    private final KeycloakUserDirectory keycloakUserDirectory;
    private final CatalogClaims claims;

    public AuthService(OrganizationService organizationService,
                       CatalogOwnerDirectory catalogOwnerDirectory,
                       KeycloakUserDirectory keycloakUserDirectory,
                       CatalogClaims claims) {
        this.organizationService = organizationService;
        this.catalogOwnerDirectory = catalogOwnerDirectory;
        this.keycloakUserDirectory = keycloakUserDirectory;
        this.claims = claims;
    }

    /**
     * The authenticated user's profile. The profile carries the claim's alias, not the catalog's
     * own organization id, because it is what the frontend shows; an alias without a name means
     * the organization is not seeded in the catalog and nothing may be published on its behalf —
     * the frontend warns about that combination.
     */
    public CurrentUserDto getCurrentUser(String username, OidcUser oidcUser) {
        String role = CatalogRole.READ_ONLY;
        String organizationAlias = null;
        String organizationName = null;
        if (oidcUser != null) {
            role = claims.effectiveRole(oidcUser);
            organizationAlias = claims.organizationAlias(oidcUser);
            organizationName = organizationService.displayName(
                    organizationService.idOfAlias(organizationAlias));
        }
        return new CurrentUserDto(
                username,
                oidcUser != null ? oidcUser.getFullName() : null,
                oidcUser != null ? oidcUser.getEmail() : null,
                role,
                organizationAlias,
                organizationName
        );
    }

    /**
     * Maintainer options for a superuser: every user of the realm, plus every organization. The
     * realm is asked first because only it knows a user who has not published anything yet; what
     * the catalog knows is merged in behind it, so the list survives an unreachable Keycloak.
     */
    public List<String> getAllMaintainers() {
        SortedSet<String> people = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        people.addAll(keycloakUserDirectory.listUsernames());
        people.addAll(catalogOwnerDirectory.findAllMaintainers());

        List<String> result = new ArrayList<>(people);
        result.addAll(organizationService.allNames());
        return result;
    }

    /**
     * Whether {@code username} may see/modify an item authored by {@code author} on behalf
     * of {@code authorOrganizationId} and maintained by {@code maintainer} /
     * {@code maintainerOrganizationId}.
     */
    public boolean canEdit(String username, String author, Integer authorOrganizationId,
                           String maintainer, Integer maintainerOrganizationId) {
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
        Integer callerOrganizationId = contributingOrganizationId(caller, callerRole);
        // An organization acts as a team: whatever it maintains, all of its contributors may edit.
        if (callerOrganizationId != null && callerOrganizationId.equals(maintainerOrganizationId)) {
            return true;
        }
        if (author != null && author.equalsIgnoreCase(username)) {
            return true;
        }
        // Uploads made on behalf of the caller's organization belong to the whole organization.
        return callerOrganizationId != null && callerOrganizationId.equals(authorOrganizationId);
    }

    /**
     * The organization the caller acts on behalf of, or {@code null} when they act as themselves.
     * Membership alone confers nothing — only an organization contributor shares in its items.
     *
     * @param caller the authenticated user
     * @param callerRole their effective catalog role
     */
    private Integer contributingOrganizationId(OidcUser caller, String callerRole) {
        // An organization the catalog has not been told about resolves to null, which is what an
        // unregistered organization should confer: nothing.
        return CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(callerRole)
                ? organizationService.idOfAlias(claims.organizationAlias(caller))
                : null;
    }

    /** Whether {@code username} is the current Superuser. */
    public boolean isSuperuser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        OidcUser caller = currentOidcUser();
        return caller != null && CatalogRole.SUPERUSER.equals(claims.effectiveRole(caller));
    }

    /**
     * Usernames sharing the caller's organization; just the caller when they have none. Derived
     * from the items published on its behalf, so it lists contributors, not every account.
     */
    public List<String> getOrganizationMembers(String username) {
        OidcUser caller = currentOidcUser();
        Integer organizationId = caller != null
                ? contributingOrganizationId(caller, claims.effectiveRole(caller))
                : null;
        if (organizationId == null) {
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
