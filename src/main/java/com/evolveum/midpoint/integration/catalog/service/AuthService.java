/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.object.Maintainer;
import com.evolveum.midpoint.integration.catalog.object.MaintainerType;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserDirectory;
import org.apache.commons.lang3.Strings;
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
     * The authenticated user's profile. An organization identifier without a name means the
     * organization is not seeded in the catalog and nothing may be published on its behalf —
     * the frontend warns about that combination.
     */
    public CurrentUserDto getCurrentUser(String username, OidcUser oidcUser) {
        CatalogRole role = CatalogRole.READ_ONLY;
        String organizationName = null;
        String organizationDisplayName = null;
        if (oidcUser != null) {
            role = claims.effectiveRole(oidcUser);
            organizationName = claims.organizationName(oidcUser);
            organizationDisplayName = organizationService.displayName(organizationName);
        }
        return new CurrentUserDto(
                username,
                oidcUser != null ? oidcUser.getFullName() : null,
                oidcUser != null ? oidcUser.getEmail() : null,
                role.getIdentifier(),
                organizationName,
                organizationDisplayName
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
    public boolean canEdit(String username, Maintainer maintainer) {
        if (username == null || username.isBlank()) {
            return false;
        }

        OidcUser caller = currentOidcUser();
        if (caller == null) {
            return false;
        }
        CatalogRole callerRole = claims.effectiveRole(caller);

        if (!callerRole.canEdit()) {
            return false;
        }

        if (CatalogRole.SUPERUSER == callerRole) {
            return true;
        }

        if (maintainer.getCategory() == MaintainerType.COMMUNITY) {
            return true;
        }

        if (Strings.CI.equals(maintainer.getUsername(), username)) {
            return true;
        }

        Organization maintainerOrg = maintainer.getOrganization();

        String callerOrganizationName = contributingOrganizationName(caller, callerRole);
        // An organization acts as a team: whatever it maintains, all of its contributors may edit.
        if (callerOrganizationName != null && maintainerOrg != null
                && Strings.CI.equals(callerOrganizationName, maintainerOrg.getName())) {
            return true;
        }
        return false;
    }

    /**
     * The organization the caller acts on behalf of, or {@code null} when they act as themselves.
     * Membership alone confers nothing — only an organization contributor shares in its items.
     *
     * @param caller the authenticated user
     * @param callerRole their effective catalog role
     */
    private String contributingOrganizationName(OidcUser caller, CatalogRole callerRole) {
        // An unregistered organization needs no check: no item can carry an identifier the
        // organizations table does not have, the ownership columns being foreign keys into it.
        return CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(callerRole)
                ? claims.organizationName(caller)
                : null;
    }

    /**
     * Whether {@code username} is the current Superuser.
     */
    public boolean isSuperuser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        OidcUser caller = currentOidcUser();
        return caller != null && CatalogRole.SUPERUSER == claims.effectiveRole(caller);
    }

    //TODO don't need it we resolve based on maintainer
//    /**
//     * Usernames sharing the caller's organization; just the caller when they have none. Derived
//     * from the items published on its behalf, so it lists contributors, not every account.
//     */
//    public List<String> getOrganizationMembers(String username) {
//        OidcUser caller = currentOidcUser();
//        String organizationName = caller != null
//                ? contributingOrganizationName(caller, claims.effectiveRole(caller))
//                : null;
//        if (organizationName == null || organizationName.isBlank()) {
//            return List.of(username);
//        }
//        List<String> members = new ArrayList<>(
//                catalogOwnerDirectory.findAuthorsOfOrganization(organizationName));
//        if (!members.contains(username)) {
//            members.add(username);
//        }
//        return members;
//    }

    private static OidcUser currentOidcUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser
                ? oidcUser
                : null;
    }
}
