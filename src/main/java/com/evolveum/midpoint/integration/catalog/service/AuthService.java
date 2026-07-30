/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.security.CatalogOidcUserService;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserService;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserService.KeycloakUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Identity questions, answered exclusively from Keycloak: the logged-in user's own
 * profile comes from the session's token claims, and anything about <em>other</em>
 * users (ownership checks, maintainer lists, organization members) is looked up
 * through the {@link KeycloakUserService} admin-API directory. The catalog database
 * holds no user, role or organization data.
 */
@Service
public class AuthService {

    private final KeycloakUserService keycloakUserService;

    public AuthService(KeycloakUserService keycloakUserService) {
        this.keycloakUserService = keycloakUserService;
    }

    /** The authenticated user's profile, read entirely from the Keycloak token claims. */
    public CurrentUserDto getCurrentUser(String username, OidcUser oidcUser) {
        String role = CatalogRole.READ_ONLY;
        String organization = null;
        List<String> groups = List.of();
        if (oidcUser != null) {
            List<String> claimedRoles = stringList(oidcUser.getClaim(CatalogOidcUserService.ROLES_CLAIM));
            role = CatalogRole.BY_PRECEDENCE.stream()
                    .filter(claimedRoles::contains)
                    .findFirst()
                    .orElse(CatalogRole.READ_ONLY);
            organization = stringList(oidcUser.getClaim(CatalogOidcUserService.ORGANIZATION_CLAIM)).stream()
                    .findFirst()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .orElse(null);
            groups = stringList(oidcUser.getClaim(CatalogOidcUserService.GROUPS_CLAIM));
        }
        return new CurrentUserDto(
                username,
                oidcUser != null ? oidcUser.getFullName() : null,
                oidcUser != null ? oidcUser.getEmail() : null,
                role,
                organization,
                groups
        );
    }

    /** Maintainer options for a superuser: every Keycloak user plus every organization. */
    public List<String> getAllMaintainers() {
        List<KeycloakUser> users = keycloakUserService.listUsers();
        List<String> result = new ArrayList<>();
        users.forEach(u -> result.add(u.username()));
        Set<String> organizations = new LinkedHashSet<>();
        users.stream()
                .map(KeycloakUser::organization)
                .filter(org -> org != null && !org.isBlank())
                .forEach(organizations::add);
        result.addAll(organizations);
        return result;
    }

    /**
     * Whether {@code username} may see/modify an item designated by {@code maintainer} and
     * uploaded by {@code author}.
     * <p>
     * This is the authoritative access check, mirrored (for UX only) on the client:
     * <ul>
     *   <li>Superuser may access anything;</li>
     *   <li>the designated maintainer may access it — matched either by username
     *       (maintainer == username) or by organization (maintainer == the caller's
     *       organization name, i.e. the item is maintained by the caller's org);</li>
     *   <li>an Organization contributor may access any item maintained by a fellow
     *       Organization contributor of their own organization (an organization acts as a
     *       team; a maintainer without an organization stays personal, and so does an
     *       Individual contributor even when they belong to an organization);</li>
     *   <li>the uploader may access items they authored (author == username);</li>
     *   <li>an Organization contributor may access any item authored by a fellow
     *       Organization contributor of their own organization.</li>
     * </ul>
     * The {@code maintainer} is the primary ownership signal: it is explicitly set when
     * publishing (e.g. a superuser may attribute an item to another user or org), whereas
     * {@code author} merely records who uploaded it. An unknown or anonymous user is never
     * granted access (except a superuser).
     */
    public boolean canEdit(String username, String author, String maintainer) {
        if (username == null || username.isBlank()) {
            return false;
        }
        KeycloakUser caller = keycloakUserService.findUser(username).orElse(null);
        if (caller == null) {
            return false;
        }
        if (CatalogRole.SUPERUSER.equals(caller.role())) {
            return true;
        }
        // Maintainer designates ownership: match by the caller's username or by their org name.
        if (maintainer != null && !maintainer.isBlank()) {
            if (maintainer.equalsIgnoreCase(username)) {
                return true;
            }
            if (caller.organization() != null && maintainer.equalsIgnoreCase(caller.organization())) {
                return true;
            }
        }
        // An organization acts as a team: an item maintained by an org contributor is editable
        // by every member of that organization. A maintainer without an org stays personal, as
        // does an IndividualContributor who belongs to an org — they act as themselves.
        if (CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(caller.role())
                && caller.organization() != null && maintainer != null && !maintainer.isBlank()
                && isOrgMate(caller, maintainer)) {
            return true;
        }
        // The uploader keeps access, as do organization contributors over their org's uploads.
        if (author != null && author.equalsIgnoreCase(username)) {
            return true;
        }
        return CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(caller.role())
                && caller.organization() != null && author != null
                && isOrgMate(caller, author);
    }

    /** Whether {@code otherUsername} is an OrganizationContributor of the caller's org. */
    private boolean isOrgMate(KeycloakUser caller, String otherUsername) {
        KeycloakUser other = keycloakUserService.findUser(otherUsername).orElse(null);
        return other != null && CatalogRole.ORGANIZATION_CONTRIBUTOR.equals(other.role())
                && other.organization() != null
                && caller.organization().equalsIgnoreCase(other.organization());
    }

    /** Whether {@code username} resolves to a Superuser. Used to gate approval actions. */
    public boolean isSuperuser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return keycloakUserService.findUser(username)
                .map(u -> CatalogRole.SUPERUSER.equals(u.role()))
                .orElse(false);
    }

    /** Usernames sharing the caller's organization attribute; just the caller when org-less. */
    public List<String> getOrganizationMembers(String username) {
        String organization = keycloakUserService.findUser(username)
                .map(KeycloakUser::organization)
                .orElse(null);
        if (organization == null || organization.isBlank()) {
            return List.of(username);
        }
        return keycloakUserService.findUsersByOrganization(organization).stream()
                .map(KeycloakUser::username)
                .toList();
    }

    private static List<String> stringList(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return claim != null ? List.of(String.valueOf(claim)) : List.of();
    }
}
