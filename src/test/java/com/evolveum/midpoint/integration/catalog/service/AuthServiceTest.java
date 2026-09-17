/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.dto.MaintainerDto;
import com.evolveum.midpoint.integration.catalog.repository.MaintainerRepository;
import com.evolveum.midpoint.integration.catalog.object.Maintainer;
import com.evolveum.midpoint.integration.catalog.object.MaintainerType;
import com.evolveum.midpoint.integration.catalog.object.Organization;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the identity logic: /api/auth/me claim parsing (the organization claim in both
 * shapes a provider can emit) and the canEdit ownership matrix. Everything but the organization
 * names and the catalog's known owners comes from the token; those two are mocked.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private OrganizationService organizationService;

    @Mock
    private OwnershipService ownershipService;

    @Mock
    private MaintainerRepository maintainerRepository;

    @Mock
    private KeycloakUserDirectory keycloakUserDirectory;

    /** Aliases of the two seeded organizations, as the organization claim carries them. */
    private static final String ACME = "acme";
    private static final String EVOLVEUM = "evolveum";

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(organizationService, ownershipService, maintainerRepository,
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

    /**
     * Puts a logged-in caller with the given role and organization into the security context.
     * The organization is named by its alias, which is all a token ever carries.
     */
    private static void callerIs(String role, String organizationAlias) {
        Map<String, Object> claims = organizationAlias == null
                ? Map.of("roles", List.of(role))
                : Map.of("roles", List.of(role), "organization", List.of(organizationAlias));
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(oidcUser(claims), null));
    }

    // ---- getCurrentUser ----

    @Test
    void currentUserWithOrganizationClaimAsIdentifierList() {
        when(organizationService.displayName(ACME)).thenReturn("Acme co.");
        OidcUser oidcUser = oidcUser(Map.of(
                "roles", List.of("OrganizationContributor"),
                "organization", List.of("acme"),
                "name", "Olivia Parker",
                "email", "olivia@acme.example"));

        CurrentUserDto user = authService.getCurrentUser("olivia", oidcUser);

        assertEquals("olivia", user.username());
        assertEquals("OrganizationContributor", user.role());
        assertEquals("acme", user.organizationName());
        assertEquals("Acme co.", user.organizationDisplayName());
        assertEquals("Olivia Parker", user.fullName());
        assertEquals("olivia@acme.example", user.email());
    }

    @Test
    void currentUserWithOrganizationClaimAsIdentifierKeyedMap() {
        when(organizationService.displayName(ACME)).thenReturn("Acme co.");
        OidcUser oidcUser = oidcUser(Map.of(
                "roles", List.of("OrganizationContributor"),
                "organization", Map.of("acme", Map.of())));

        CurrentUserDto user = authService.getCurrentUser("olivia", oidcUser);

        assertEquals("acme", user.organizationName());
        assertEquals("Acme co.", user.organizationDisplayName());
    }

    @Test
    void currentUserOrganizationNameStaysNullWhenNotSeeded() {
        when(organizationService.displayName(ACME)).thenReturn(null);

        CurrentUserDto user = authService.getCurrentUser("olivia",
                oidcUser(Map.of("organization", List.of("acme"))));

        // Identifier without a name is what tells the frontend the organization is unregistered.
        assertEquals("acme", user.organizationName());
        assertNull(user.organizationDisplayName());
    }

    @Test
    void currentUserWithoutClaimsIsOrganizationLessReadOnly() {
        CurrentUserDto user = authService.getCurrentUser("ben", oidcUser(Map.of()));

        assertEquals("ReadOnly", user.role());
        assertNull(user.organizationName());
        assertNull(user.organizationDisplayName());
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
        assertNull(user.organizationName());
        assertNull(user.organizationDisplayName());
    }

    // ---- canEdit ----

    @Test
    void anonymousOrNamelessCallerCannotEdit() {
        // No session at all.
        assertFalse(authService.canEdit("ben", createUserMaintainer("ben")));

        callerIs("Superuser", null);
        assertFalse(authService.canEdit(null, createDefaultUserMaintainer()));
        assertFalse(authService.canEdit("  ", createDefaultUserMaintainer()));
    }

    @Test
    void superuserCanEditAnything() {
        callerIs("Superuser", "evolveum");

        assertTrue(authService.canEdit("boss", createUserMaintainer("someone")));
        assertTrue(authService.canEdit("boss", createUserMaintainer(null)));

        assertTrue(authService.canEdit("boss", createOrgMaintainer("acme")));
        assertTrue(authService.canEdit("boss", createOrgMaintainer(null)));
    }

    @Test
    void maintainerMatchesCallerUsernameCaseInsensitively() {
        callerIs("IndividualContributor", null);

        assertTrue(authService.canEdit("ben", createUserMaintainer("BEN")));
        assertFalse(authService.canEdit("ben", createUserMaintainer("someone-else")));
    }

    @Test
    void organizationActsAsTeamForWhateverItMaintains() {
        callerIs("OrganizationContributor", "acme");

        // Maintained by the caller's own organization -> every contributor to it may edit.
        assertTrue(authService.canEdit("olivia", createOrgMaintainer("acme")));
        // Maintained by another organization -> off limits.
        assertFalse(authService.canEdit("olivia", createOrgMaintainer("evolveum")));
    }

    @Test
    void individualContributorDoesNotInheritItemsMaintainedByTheirOrganization() {
        callerIs("IndividualContributor", "acme");

        // Membership alone confers nothing: an item published on behalf of acme stays
        // invisible to an org-mate who contributes as an individual.
        assertFalse(authService.canEdit("dana", createOrgMaintainer("acme")));
        // Their own items are unaffected.
        assertTrue(authService.canEdit("dana", createUserMaintainer("dana")));
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
    void allMaintainersMergeTheCatalogsRowsWithTheRealmAndTheOrganizations() {
        when(maintainerRepository.findAll()).thenReturn(List.of(
                seeded(1L, MaintainerType.COMMUNITY), seeded(2L, MaintainerType.EVOLVEUM),
                createUserMaintainer("ben")));
        when(ownershipService.toDto(any())).thenAnswer(call -> dtoOf(call.getArgument(0)));
        when(keycloakUserDirectory.listUsernames()).thenReturn(List.of("olivia", "ben"));
        when(organizationService.allAliases()).thenReturn(List.of(ACME));
        when(organizationService.displayName(ACME)).thenReturn("Acme co.");

        List<MaintainerDto> all = authService.getAllMaintainers();

        // The two catalog-wide rows first, then everything else by label; ben is not repeated.
        assertEquals(List.of("Evolveum", "Community", "Acme co.", "ben", "olivia"),
                all.stream().map(MaintainerDto::label).toList());
        assertEquals(MaintainerType.ORG,
                all.stream().filter(m -> "Acme co.".equals(m.label())).findFirst().orElseThrow().category());
    }

    @Test
    void allMaintainersSurviveAnUnreachableRealm() {
        when(maintainerRepository.findAll()).thenReturn(List.of(createUserMaintainer("ben")));
        when(ownershipService.toDto(any())).thenAnswer(call -> dtoOf(call.getArgument(0)));
        when(keycloakUserDirectory.listUsernames()).thenReturn(List.of());
        when(organizationService.allAliases()).thenReturn(List.of());

        assertEquals(List.of("ben"), authService.getAllMaintainers().stream().map(MaintainerDto::label).toList());
    }

    @Test
    void allMaintainersDoNotRepeatAUserWhoseCaseDiffersBetweenSources() {
        when(maintainerRepository.findAll()).thenReturn(List.of(createUserMaintainer("olivia")));
        when(ownershipService.toDto(any())).thenAnswer(call -> dtoOf(call.getArgument(0)));
        when(keycloakUserDirectory.listUsernames()).thenReturn(List.of("Olivia"));
        when(organizationService.allAliases()).thenReturn(List.of());

        assertEquals(1, authService.getAllMaintainers().size());
    }

    /** What OwnershipService.toDto does, as far as these tests need it. */
    private static MaintainerDto dtoOf(Maintainer maintainer) {
        String label = switch (maintainer.getCategory()) {
            case USER -> maintainer.getUsername();
            case ORG -> maintainer.getOrganization().getName();
            case EVOLVEUM -> "Evolveum";
            case COMMUNITY -> "Community";
        };
        return new MaintainerDto(maintainer.getId(), maintainer.getUsername(),
                maintainer.getOrganization() != null ? maintainer.getOrganization().getName() : null,
                maintainer.getCategory(), label);
    }

    private static Maintainer seeded(Long id, MaintainerType category) {
        return new Maintainer().setCategory(category).setId(id);
    }

    private Maintainer createDefaultUserMaintainer() {
        return createUserMaintainer("maintainer");
    }

    private Maintainer createUserMaintainer(String username) {
        return new Maintainer().setCategory(MaintainerType.USER).setUsername(username);
    }

    private Maintainer createOrgMaintainer(String orgName) {
        return new Maintainer().setCategory(MaintainerType.ORG).setOrganization(new Organization().setName(orgName));
    }
}
