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
 * Identity questions, answered from the OIDC token claims of the current session plus the
 * organizations table.
 *
 * A token only ever describes its own bearer, so questions about other users are
 * answered from what was recorded on the item when it was written: its author, its
 * maintainer, and the organization identifiers stamped alongside them.
 *
 * The one exception is the superuser's maintainer list, which has to be able to name a user the
 * catalog has never seen and therefore reads the realm through {@link KeycloakUserDirectory}.
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
     * Maintainer options for a superuser: every user of the realm, plus every organization.
     *
     * The identity provider is asked first, since only it knows a user who has not yet touched
     * the catalog - without that, a freshly created user could never be made maintainer of
     * anything, having published nothing to be recorded on. What the catalog itself knows is
     * merged in behind it, which both keeps the list working while Keycloak is unreachable and
     * preserves maintainers who no longer have an account.
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
        String callerOrganizationId = contributingOrganizationId(caller, callerRole);
        // An organization acts as a team: whatever it maintains, all of its contributors may edit.
        if (callerOrganizationId != null && maintainerOrganizationId != null
                && callerOrganizationId.equalsIgnoreCase(maintainerOrganizationId)) {
            return true;
        }
        if (author != null && author.equalsIgnoreCase(username)) {
            return true;
        }
        // Uploads made on behalf of the caller's organization belong to the whole organization.
        return callerOrganizationId != null && authorOrganizationId != null
                && callerOrganizationId.equalsIgnoreCase(authorOrganizationId);
    }

    /**
     * The organization the caller acts on behalf of, or {@code null} when they act as
     * themselves. Membership alone confers nothing: an individual contributor who happens to
     * belong to an organization publishes and reads as an individual, so only an organization
     * contributor shares in the organization's items.
     *
     * @param caller the authenticated user
     * @param callerRole their effective catalog role
     */
    private String contributingOrganizationId(OidcUser caller, String callerRole) {
        return CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(callerRole)
                ? claims.organizationId(caller)
                : null;
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
     * Usernames sharing the caller's organization; just the caller when they have none, or
     * when they contribute as an individual.
     *
     * Derived from the items published on behalf of that organization, so it lists the
     * organization's contributors rather than every account in it.
     */
    public List<String> getOrganizationMembers(String username) {
        OidcUser caller = currentOidcUser();
        String organizationId = caller != null
                ? contributingOrganizationId(caller, claims.effectiveRole(caller))
                : null;
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
