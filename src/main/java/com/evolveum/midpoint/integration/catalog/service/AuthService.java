/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.dto.MaintainerDto;
import com.evolveum.midpoint.integration.catalog.object.*;
import com.evolveum.midpoint.integration.catalog.repository.IntegrationMethodRepository;
import com.evolveum.midpoint.integration.catalog.repository.MaintainerRepository;
import com.evolveum.midpoint.integration.catalog.repository.RequestRepository;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import com.evolveum.midpoint.integration.catalog.security.CatalogRole;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserDirectory;
import org.apache.commons.lang3.Strings;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

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
    private final IntegrationMethodRepository integrationMethodRepository;
    private final RequestRepository requestRepository;

    public AuthService(OrganizationService organizationService,
                       OwnershipService ownershipService,
                       MaintainerRepository maintainerRepository,
                       KeycloakUserDirectory keycloakUserDirectory,
                       CatalogClaims claims, IntegrationMethodRepository integrationMethodRepository,
                       RequestRepository requestRepository) {
        this.organizationService = organizationService;
        this.ownershipService = ownershipService;
        this.maintainerRepository = maintainerRepository;
        this.keycloakUserDirectory = keycloakUserDirectory;
        this.claims = claims;
        this.integrationMethodRepository = integrationMethodRepository;
        this.requestRepository = requestRepository;
    }

    /**
     * Whether {@code username} may change an application that is not published yet, such as its
     * logo: whoever requested it or may edit one of its methods, or a superuser.
     */
    public boolean canEditUnpublishedApplication(String username, Application application) {
        if (isSuperuser(username)) {
            return true;
        }
        boolean requester = requestRepository.findByApplicationId(application.getId())
                .map(request -> Strings.CI.equals(request.getRequester(), username))
                .orElse(false);
        return requester || integrationMethodRepository.findByApplicationId(application.getId()).stream()
                .anyMatch(method -> canEdit(username, method.getLifecycleState(), method.getAuthor(), method.getMaintainer()));
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
     * Maintainer options for a superuser: Community, then every user of the realm and every
     * organization. Users and organizations carry no id; assigning one reuses its existing row or
     * creates it. The catalog-wide EVOLVEUM row is left out, as it read the same as the evolveum
     * organization.
     */
    public List<MaintainerDto> getAllMaintainers() {
        List<MaintainerDto> all = new ArrayList<>();

        keycloakUserDirectory.listUsernames().stream()
                .map(username -> new MaintainerDto(null, username, null, MaintainerType.USER, username))
                .forEach(all::add);

        organizationService.allNames().stream()
                .map(alias -> new MaintainerDto(
                        null, null, alias, MaintainerType.ORG, organizationService.displayName(alias)))
                .forEach(all::add);

        all.sort(Comparator.comparing(MaintainerDto::label, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        maintainerRepository.findByCategory(MaintainerType.COMMUNITY)
                .ifPresent(community -> all.add(0, ownershipService.toDto(community)));
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

    /**
     * Enforces that {@code username} may modify the integration-method revision (and its
     * connectors). Throws 404 if the revision does not exist, or 403 if the caller does not
     * own it (see {@link AuthService#canEdit}). Server-side counterpart of the client's
     * edit-button gating — this is the check that actually protects the data.
     */
    public void assertCanEditMethod(String username, UUID methodId, String revision) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException(
                        "Integration method not found: " + methodId + "/" + revision));
        if (!canEdit(username, method.getLifecycleState(), method.getAuthor(), method.getMaintainer())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not allowed to modify this integration method.");
        }
        assertNotUnderReview(username, method);
    }

    /**
     * Blocks edits by non-superusers while a revision is REVIEWING: once a review starts the revision
     * is locked for its author until the review is resolved, so nothing changes underneath the
     * reviewer. Superusers are exempt — the reviewer may fix findings directly during the review
     * (or hand the revision back via stop-review for the author to fix). Mirrors the client, which
     * disables the edit controls for this state for everyone but superusers.
     */
    private void assertNotUnderReview(String username, IntegrationMethod method) {
        if (method.getLifecycleState() == LifecycleType.REVIEWING && !isSuperuser(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This integration method is locked while it is under review.");
        }
    }

    /**
     * Enforces that {@code username} may edit a specific connector's content. Unlike
     * {@link #assertCanEditMethod}, this gates on the connector's own owner, so a connector
     * maintained by someone else stays closed to the method's maintainer.
     */
    public void assertCanEditConnector(String username, UUID methodId, String revision, Integer connectorId) {
        IntegrationMethod method = integrationMethodRepository.findById(new IntegrationMethodId(methodId, revision))
                .orElseThrow(() -> new RuntimeException(
                        "Integration method not found: " + methodId + "/" + revision));
        Connector connector = method.getConnectors().stream()
                .map(IntegrationMethodConnector::getConnector)
                .filter(Objects::nonNull)
                .filter(c -> connectorId.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Connector " + connectorId + " is not linked to integration method " + methodId + "/" + revision));
        if (!canEdit(username, method.getLifecycleState(), connector.getAuthor(), connector.getMaintainer())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not allowed to modify this connector.");
        }
        assertNotUnderReview(username, method);
    }
}
