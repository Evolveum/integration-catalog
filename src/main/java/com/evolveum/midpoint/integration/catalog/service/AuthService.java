/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.dto.MaintainerDto;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.MaintainerRepository;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.apache.commons.lang3.Strings;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Identity questions, answered from the token claims of the current session plus the organizations
 * table. A token describes only its bearer, so anything about other users is read from what was
 * stamped on the item when it was written.
 */
@Service
public class AuthService {

    private final OrganizationService organizationService;
    private final OwnershipService ownershipService;
    private final MaintainerRepository maintainerRepository;
    private final KeycloakUserDirectory keycloakUserDirectory;
    private final CatalogClaims claims;

    public AuthService(OrganizationService organizationService,
                       OwnershipService ownershipService,
                       MaintainerRepository maintainerRepository,
                       KeycloakUserDirectory keycloakUserDirectory,
                       CatalogClaims claims) {
        this.organizationService = organizationService;
        this.ownershipService = ownershipService;
        this.maintainerRepository = maintainerRepository;
        this.keycloakUserDirectory = keycloakUserDirectory;
        this.claims = claims;
    }

    /**
     * The authenticated user's profile. An organization alias without a display name means the
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
                oidcUser != null ? oidcUser.getGivenName() : null,
                oidcUser != null ? oidcUser.getFamilyName() : null,
                oidcUser != null ? oidcUser.getEmail() : null,
                role.getIdentifier(),
                organizationName,
                organizationDisplayName
        );
    }

    /**
     * Maintainer options for a superuser: every maintainer the catalog already has, plus every
     * user of the realm and every organization that has not been one yet. The latter two carry no
     * id - the row is created when an item is first published under them.
     */
    public List<MaintainerDto> getAllMaintainers() {
        List<MaintainerDto> all = new ArrayList<>();
        Set<String> takenUsernames = new LinkedHashSet<>();
        Set<String> takenOrganizations = new LinkedHashSet<>();

        for (Maintainer maintainer : maintainerRepository.findAll()) {
            all.add(ownershipService.toDto(maintainer));

            switch (maintainer.getCategory()){
                case USER -> takenUsernames.add(maintainer.getUsername().toLowerCase());
                case ORG -> takenOrganizations.add(maintainer.getOrganization().getName().toLowerCase());
            }
        }

        keycloakUserDirectory.listUsernames().stream()
                .filter(username -> !takenUsernames.contains(username.toLowerCase()))
                .map(username -> new MaintainerDto(null, username, null, MaintainerType.USER, username))
                .forEach(all::add);

        organizationService.allNames().stream()
                .filter(alias -> !takenOrganizations.contains(alias.toLowerCase()))
                .map(alias -> new MaintainerDto(
                        null, null, alias, MaintainerType.ORG, organizationService.displayName(alias)))
                .forEach(all::add);

        // The two catalog-wide rows first, then everything else by what it is shown as, so the
        // list reads the same however the rows happen to be ordered in the table.
        all.sort(Comparator
                .comparingInt((MaintainerDto m) -> switch (m.category()) {
                    case EVOLVEUM -> 0;
                    case COMMUNITY -> 1;
                    default -> 2;
                })
                .thenComparing(MaintainerDto::label, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return all;
    }

    /**
     * Whether {@code username} may modify an item maintained by {@code maintainer}. Only the
     * maintainer decides: a Superuser may edit anything, a COMMUNITY item is open to every
     * contributor, and otherwise the caller must be the maintainer or contribute for the
     * organization that is. Who authored the item grants nothing - it is recorded for audit.
     */
    public boolean canEdit(String username, LifecycleType lifecycleType, Author author, Maintainer maintainer) {
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
            if (LifecycleType.IN_REVIEW == lifecycleType
                    || LifecycleType.REVIEWING == lifecycleType
                    || LifecycleType.REJECTED == lifecycleType) {
                return Strings.CS.equals(username, author.getUsername());
            } else {
                return true;
            }
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
        // An unregistered organization needs no check: a maintainer row can only point at an
        // organization the table has, organization_id being a foreign key into it.
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

    private static OidcUser currentOidcUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser
                ? oidcUser
                : null;
    }
}
