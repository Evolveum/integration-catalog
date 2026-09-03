/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import com.evolveum.midpoint.integration.catalog.security.KeycloakUserDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the identity logic: /api/auth/me claim parsing (organization claim in both
 * shapes a provider can emit) and the canEdit ownership matrix. The caller is taken from the
 * security context and everything about them comes from their token — only the organization
 * names and the catalog's known owners are read from the database, and those are mocked.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private OrganizationService organizationService;

    @Mock
    private CatalogOwnerDirectory catalogOwnerDirectory;

    @Mock
    private KeycloakUserDirectory keycloakUserDirectory;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(organizationService, catalogOwnerDirectory,
                keycloakUserDirectory, new CatalogClaims("roles", "organization"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static OidcUser oidcUser(Map<String, Object> claims) {
        OidcIdToken.Builder idToken = OidcIdToken.withTokenValue("token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("subject");
        claims.forEach(idToken::claim);
        return new DefaultOidcUser(List.of(), idToken.build());
    }

    /** Puts a logged-in caller with the given role and organization into the security context. */
    private static void callerIs(String role, String organizationId) {
        Map<String, Object> claims = organizationId == null
                ? Map.of("roles", List.of(role))
                : Map.of("roles", List.of(role), "organization", List.of(organizationId));
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(oidcUser(claims), null));
    }

    // ---- getCurrentUser ----

    @Test
    void currentUserWithOrganizationClaimAsIdentifierList() {
        when(organizationService.displayName("acme")).thenReturn("Acme co.");
        OidcUser oidcUser = oidcUser(Map.of(
                "roles", List.of("OrganizationContributor"),
                "organization", List.of("acme"),
                "name", "Olivia Parker",
                "email", "olivia@acme.example"));

        CurrentUserDto user = authService.getCurrentUser("olivia", oidcUser);

        assertEquals("olivia", user.username());
        assertEquals("OrganizationContributor", user.role());
        assertEquals("acme", user.organizationId());
        assertEquals("Acme co.", user.organizationName());
        assertEquals("Olivia Parker", user.fullName());
        assertEquals("olivia@acme.example", user.email());
    }

    @Test
    void currentUserWithOrganizationClaimAsIdentifierKeyedMap() {
        when(organizationService.displayName("acme")).thenReturn("Acme co.");
        OidcUser oidcUser = oidcUser(Map.of(
                "roles", List.of("OrganizationContributor"),
                "organization", Map.of("acme", Map.of())));

        CurrentUserDto user = authService.getCurrentUser("olivia", oidcUser);

        assertEquals("acme", user.organizationId());
        assertEquals("Acme co.", user.organizationName());
    }

    @Test
    void currentUserOrganizationNameFallsBackToIdentifierWhenNotSeeded() {
        when(organizationService.displayName("acme")).thenReturn(null);

        CurrentUserDto user = authService.getCurrentUser("olivia",
                oidcUser(Map.of("organization", List.of("acme"))));

        assertEquals("acme", user.organizationId());
        assertEquals("acme", user.organizationName());
    }

    @Test
    void currentUserWithoutClaimsIsOrganizationLessReadOnly() {
        CurrentUserDto user = authService.getCurrentUser("ben", oidcUser(Map.of()));

        assertEquals("ReadOnly", user.role());
        assertNull(user.organizationId());
        assertNull(user.organizationName());
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
    void anonymousOrNamelessCallerCannotEdit() {
        // No session at all.
        assertFalse(authService.canEdit("ben", "ben", null, "ben", null));

        callerIs("Superuser", null);
        assertFalse(authService.canEdit(null, "author", null, "maintainer", null));
        assertFalse(authService.canEdit("  ", "author", null, "maintainer", null));
    }

    @Test
    void superuserCanEditAnything() {
        callerIs("Superuser", "evolveum");

        assertTrue(authService.canEdit("boss", "someone", "acme", "someone-else", "acme"));
        assertTrue(authService.canEdit("boss", null, null, null, null));
    }

    @Test
    void maintainerMatchesCallerUsernameCaseInsensitively() {
        callerIs("ReadOnly", null);

        assertTrue(authService.canEdit("ben", null, null, "BEN", null));
        assertFalse(authService.canEdit("ben", null, null, "someone-else", null));
    }

    @Test
    void organizationActsAsTeamForWhateverItMaintains() {
        callerIs("IndividualContributor", "acme");

        // Maintained by the caller's own organization -> every member may edit,
        // whatever their role inside it.
        assertTrue(authService.canEdit("dana", null, null, null, "acme"));
        // Maintained by another organization -> off limits.
        assertFalse(authService.canEdit("dana", null, null, null, "evolveum"));
    }

    @Test
    void authorKeepsAccessAndOrgContributorsShareItemsAuthoredForTheOrganization() {
        callerIs("OrganizationContributor", "acme");

        // The uploader keeps access to their own item.
        assertTrue(authService.canEdit("olivia", "olivia", null, null, null));
        // Authored on behalf of the caller's organization -> team access.
        assertTrue(authService.canEdit("olivia", "amber", "acme", null, null));
        // Authored by an org-mate as an individual -> stays personal.
        assertFalse(authService.canEdit("olivia", "dana", null, null, null));
        // Authored for another organization -> no access.
        assertFalse(authService.canEdit("olivia", "eve", "evolveum", null, null));
    }

    @Test
    void individualContributorDoesNotInheritItemsAuthoredForTheirOrganization() {
        callerIs("IndividualContributor", "acme");

        assertFalse(authService.canEdit("dana", "amber", "acme", null, null));
    }

    // ---- claim- and catalog-backed helpers ----

    @Test
    void isSuperuserChecksTheCallersEffectiveRole() {
        callerIs("Superuser", null);
        assertTrue(authService.isSuperuser("boss"));
        assertFalse(authService.isSuperuser(null));

        callerIs("ReadOnly", null);
        assertFalse(authService.isSuperuser("ben"));
    }

    @Test
    void organizationMembersOfOrganizationLessUserIsJustThemselves() {
        callerIs("IndividualContributor", null);

        assertEquals(List.of("ben"), authService.getOrganizationMembers("ben"));
    }

    @Test
    void organizationMembersComeFromItemsPublishedForThatOrganization() {
        callerIs("OrganizationContributor", "acme");
        when(catalogOwnerDirectory.findAuthorsOfOrganization("acme"))
                .thenReturn(List.of("amber", "olivia"));

        assertEquals(List.of("amber", "olivia"), authService.getOrganizationMembers("olivia"));
    }

    @Test
    void organizationMembersAlwaysContainTheCallerThemselves() {
        callerIs("OrganizationContributor", "acme");
        when(catalogOwnerDirectory.findAuthorsOfOrganization("acme"))
                .thenReturn(List.of("amber"));

        assertEquals(List.of("amber", "olivia"), authService.getOrganizationMembers("olivia"));
    }

    @Test
    void allMaintainersMergeTheRealmWithTheCatalogsOwnMaintainers() {
        when(keycloakUserDirectory.listUsernames()).thenReturn(List.of("olivia", "newcomer"));
        when(catalogOwnerDirectory.findAllMaintainers()).thenReturn(List.of("ben", "olivia"));
        when(organizationService.allNames()).thenReturn(List.of("Acme co.", "Evolveum"));

        // People sorted and de-duplicated across both sources, organizations kept after them.
        assertEquals(List.of("ben", "newcomer", "olivia", "Acme co.", "Evolveum"),
                authService.getAllMaintainers());
    }

    @Test
    void allMaintainersSurviveAnUnreachableRealm() {
        when(keycloakUserDirectory.listUsernames()).thenReturn(List.of());
        when(catalogOwnerDirectory.findAllMaintainers()).thenReturn(List.of("ben", "olivia"));
        when(organizationService.allNames()).thenReturn(List.of("Acme co."));

        assertEquals(List.of("ben", "olivia", "Acme co."), authService.getAllMaintainers());
    }

    @Test
    void allMaintainersDoNotRepeatAUserWhoseCaseDiffersBetweenSources() {
        when(keycloakUserDirectory.listUsernames()).thenReturn(List.of("Olivia"));
        when(catalogOwnerDirectory.findAllMaintainers()).thenReturn(List.of("olivia"));
        when(organizationService.allNames()).thenReturn(List.of());

        assertEquals(1, authService.getAllMaintainers().size());
    }
}
