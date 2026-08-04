/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserService;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserService.KeycloakOrganization;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserService.KeycloakUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the identity logic: /api/auth/me claim parsing (organization claim in
 * both shapes the Keycloak organization membership mapper can emit) and the canEdit
 * ownership matrix, with the Keycloak directory mocked.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final KeycloakOrganization ACME =
            new KeycloakOrganization("org-1", "acme", "Acme co.");
    private static final KeycloakOrganization EVOLVEUM =
            new KeycloakOrganization("org-2", "evolveum", "Evolveum");

    private static final KeycloakUser SUPERUSER =
            new KeycloakUser("boss", "Superuser", "evolveum", "Evolveum");
    private static final KeycloakUser ORG_CONTRIBUTOR =
            new KeycloakUser("olivia", "OrganizationContributor", "acme", "Acme co.");
    private static final KeycloakUser ORG_MATE =
            new KeycloakUser("amber", "OrganizationContributor", "acme", "Acme co.");
    private static final KeycloakUser INDIVIDUAL_IN_ORG =
            new KeycloakUser("dana", "IndividualContributor", "acme", "Acme co.");
    private static final KeycloakUser OTHER_ORG_CONTRIBUTOR =
            new KeycloakUser("eve", "OrganizationContributor", "evolveum", "Evolveum");
    private static final KeycloakUser READ_ONLY =
            new KeycloakUser("ben", "ReadOnly", null, null);

    @Mock
    private KeycloakUserService keycloakUserService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(keycloakUserService);
    }

    private void knownUsers(KeycloakUser... users) {
        for (KeycloakUser user : users) {
            lenient().when(keycloakUserService.findUser(user.username())).thenReturn(Optional.of(user));
        }
    }

    private static OidcUser oidcUser(Map<String, Object> claims) {
        OidcIdToken.Builder idToken = OidcIdToken.withTokenValue("token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("subject");
        claims.forEach(idToken::claim);
        return new DefaultOidcUser(List.of(), idToken.build());
    }

    // ---- getCurrentUser ----

    @Test
    void currentUserWithOrganizationClaimAsAliasList() {
        when(keycloakUserService.findOrganizationByAlias("acme")).thenReturn(Optional.of(ACME));
        OidcUser oidcUser = oidcUser(Map.of(
                "roles", List.of("OrganizationContributor"),
                "organization", List.of("acme"),
                "groups", List.of("Partner"),
                "name", "Olivia Parker",
                "email", "olivia@acme.example"));

        CurrentUserDto user = authService.getCurrentUser("olivia", oidcUser);

        assertEquals("olivia", user.username());
        assertEquals("OrganizationContributor", user.role());
        assertEquals("acme", user.organizationId());
        assertEquals("Acme co.", user.organizationName());
        assertEquals(List.of("Partner"), user.groups());
        assertEquals("Olivia Parker", user.fullName());
        assertEquals("olivia@acme.example", user.email());
    }

    @Test
    void currentUserWithOrganizationClaimAsAliasKeyedMap() {
        when(keycloakUserService.findOrganizationByAlias("acme")).thenReturn(Optional.of(ACME));
        OidcUser oidcUser = oidcUser(Map.of(
                "roles", List.of("OrganizationContributor"),
                "organization", Map.of("acme", Map.of())));

        CurrentUserDto user = authService.getCurrentUser("olivia", oidcUser);

        assertEquals("acme", user.organizationId());
        assertEquals("Acme co.", user.organizationName());
    }

    @Test
    void currentUserOrganizationNameFallsBackToAliasWhileKeycloakUnavailable() {
        when(keycloakUserService.findOrganizationByAlias("acme")).thenReturn(Optional.empty());
        OidcUser oidcUser = oidcUser(Map.of("organization", List.of("acme")));

        CurrentUserDto user = authService.getCurrentUser("olivia", oidcUser);

        assertEquals("acme", user.organizationId());
        assertEquals("acme", user.organizationName());
    }

    @Test
    void currentUserWithoutClaimsIsOrganizationLessReadOnly() {
        CurrentUserDto user = authService.getCurrentUser("ben", oidcUser(Map.of()));

        assertEquals("ReadOnly", user.role());
        assertNull(user.organizationId());
        assertNull(user.organizationName());
        assertEquals(List.of(), user.groups());
    }

    @Test
    void currentUserStrongestRoleWins() {
        CurrentUserDto user = authService.getCurrentUser("boss",
                oidcUser(Map.of("roles", List.of("IndividualContributor", "Superuser"))));

        assertEquals("Superuser", user.role());
    }

    @Test
    void currentUserWithoutSessionIsReadOnly() {
        CurrentUserDto user = authService.getCurrentUser("anonymous", null);

        assertEquals("ReadOnly", user.role());
        assertNull(user.organizationId());
        assertNull(user.organizationName());
    }

    // ---- canEdit ----

    @Test
    void anonymousOrUnknownCallerCannotEdit() {
        when(keycloakUserService.findUser("ghost")).thenReturn(Optional.empty());

        assertFalse(authService.canEdit(null, "author", "maintainer"));
        assertFalse(authService.canEdit("  ", "author", "maintainer"));
        assertFalse(authService.canEdit("ghost", "author", "maintainer"));
    }

    @Test
    void superuserCanEditAnything() {
        knownUsers(SUPERUSER);

        assertTrue(authService.canEdit("boss", "someone", "someone-else"));
        assertTrue(authService.canEdit("boss", null, null));
    }

    @Test
    void maintainerMatchesCallerUsernameCaseInsensitively() {
        knownUsers(READ_ONLY);

        assertTrue(authService.canEdit("ben", null, "BEN"));
        assertFalse(authService.canEdit("ben", null, "someone-else"));
    }

    @Test
    void maintainerMatchesCallerOrganizationName() {
        knownUsers(INDIVIDUAL_IN_ORG);

        assertTrue(authService.canEdit("dana", null, "acme CO."));
    }

    @Test
    void organizationActsAsTeamForOrganizationContributors() {
        knownUsers(ORG_CONTRIBUTOR, ORG_MATE, INDIVIDUAL_IN_ORG, OTHER_ORG_CONTRIBUTOR);

        // Maintained by an org-mate OrganizationContributor -> whole org may edit.
        assertTrue(authService.canEdit("olivia", null, "amber"));
        // A maintainer from another organization stays off limits.
        assertFalse(authService.canEdit("olivia", null, "eve"));
        // An IndividualContributor maintainer stays personal even inside the org.
        assertFalse(authService.canEdit("olivia", null, "dana"));
        // An IndividualContributor caller does not get the team access.
        assertFalse(authService.canEdit("dana", null, "amber"));
    }

    @Test
    void authorKeepsAccessAndOrgContributorsShareAuthoredItems() {
        knownUsers(ORG_CONTRIBUTOR, ORG_MATE, INDIVIDUAL_IN_ORG, READ_ONLY);

        // The uploader keeps access to their own item.
        assertTrue(authService.canEdit("ben", "ben", null));
        // Authored by an org-mate OrganizationContributor -> team access.
        assertTrue(authService.canEdit("olivia", "amber", null));
        // Authored by an org-mate IndividualContributor -> stays personal.
        assertFalse(authService.canEdit("olivia", "dana", null));
        // No relation at all -> no access.
        assertFalse(authService.canEdit("ben", "amber", "olivia"));
    }

    // ---- directory-backed helpers ----

    @Test
    void isSuperuserChecksEffectiveRole() {
        knownUsers(SUPERUSER, READ_ONLY);
        when(keycloakUserService.findUser("ghost")).thenReturn(Optional.empty());

        assertTrue(authService.isSuperuser("boss"));
        assertFalse(authService.isSuperuser("ben"));
        assertFalse(authService.isSuperuser("ghost"));
        assertFalse(authService.isSuperuser(null));
    }

    @Test
    void organizationMembersOfOrganizationLessUserIsJustThemselves() {
        knownUsers(READ_ONLY);

        assertEquals(List.of("ben"), authService.getOrganizationMembers("ben"));
    }

    @Test
    void organizationMembersAreLookedUpByAlias() {
        knownUsers(ORG_CONTRIBUTOR);
        when(keycloakUserService.findUsersByOrganization("acme"))
                .thenReturn(List.of(ORG_CONTRIBUTOR, ORG_MATE, INDIVIDUAL_IN_ORG));

        assertEquals(List.of("olivia", "amber", "dana"), authService.getOrganizationMembers("olivia"));
    }

    @Test
    void allMaintainersAreUsernamesPlusOrganizationNames() {
        when(keycloakUserService.listUsers()).thenReturn(List.of(ORG_CONTRIBUTOR, READ_ONLY));
        when(keycloakUserService.listOrganizations()).thenReturn(List.of(ACME, EVOLVEUM));

        assertEquals(List.of("olivia", "ben", "Acme co.", "Evolveum"), authService.getAllMaintainers());
    }
}
