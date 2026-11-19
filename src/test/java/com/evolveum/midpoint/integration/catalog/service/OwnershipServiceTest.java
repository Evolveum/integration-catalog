/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.dto.MaintainerDto;
import com.evolveum.midpoint.integration.catalog.object.IntegrationMethod;
import com.evolveum.midpoint.integration.catalog.object.Maintainer;
import com.evolveum.midpoint.integration.catalog.object.MaintainerType;
import com.evolveum.midpoint.integration.catalog.object.Organization;
import com.evolveum.midpoint.integration.catalog.repository.AuthorRepository;
import com.evolveum.midpoint.integration.catalog.repository.MaintainerRepository;
import com.evolveum.midpoint.integration.catalog.security.CatalogClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Which maintainer a caller may set on an item: themselves or their organization if they contribute
 * for it, never someone else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OwnershipServiceTest {

    @Mock
    private OrganizationService organizationService;

    @Mock
    private MaintainerRepository maintainerRepository;

    @Mock
    private AuthorRepository authorRepository;

    private OwnershipService ownershipService;

    @BeforeEach
    void setUp() {
        ownershipService = new OwnershipService(organizationService, new CatalogClaims("roles", "organization"),
                maintainerRepository, authorRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void callerIs(String username, String role, String organization) {
        OidcIdToken.Builder idToken = OidcIdToken.withTokenValue("token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("subject");
        Map.of("roles", List.of(role), "organization", List.of(organization)).forEach(idToken::claim);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(new DefaultOidcUser(List.of(), idToken.build()), null) {
                    @Override
                    public String getName() {
                        return username;
                    }
                });
    }

    private Maintainer stored(Maintainer row) {
        when(maintainerRepository.findById(row.getId())).thenReturn(Optional.of(row));
        return row;
    }

    private Maintainer personal(String username) {
        return stored(new Maintainer().setId(9L).setCategory(MaintainerType.USER).setUsername(username));
    }

    private Maintainer organization(String alias) {
        return stored(new Maintainer().setId(3L).setCategory(MaintainerType.ORG)
                .setOrganization(new Organization().setId(2).setName(alias)));
    }

    private Maintainer linkedEvolveumRow() {
        Organization evolveum = new Organization().setId(1).setName("evolveum");
        Maintainer linked = new Maintainer().setId(2L).setCategory(MaintainerType.EVOLVEUM).setOrganization(evolveum);
        when(organizationService.getOrganizationByName("evolveum")).thenReturn(evolveum);
        when(maintainerRepository.findByCategoryAndOrganization(MaintainerType.EVOLVEUM, evolveum))
                .thenReturn(Optional.of(linked));
        return linked;
    }

    private static MaintainerDto dtoOf(Maintainer row) {
        return new MaintainerDto(row.getId(), row.getUsername(), null, row.getCategory(), null);
    }

    @Test
    void organizationContributorMayAssignTheirOrganization() {
        callerIs("olivia", "OrganizationContributor", "acme");
        Maintainer acme = organization("acme");
        IntegrationMethod method = new IntegrationMethod();

        ownershipService.assignMaintainer(method, dtoOf(acme));

        assertSame(acme, method.getMaintainer());
    }

    @Test
    void choosingTheEvolveumOrganizationAssignsItsCatalogWideRow() {
        callerIs("olivia", "OrganizationContributor", "evolveum");
        Maintainer linked = linkedEvolveumRow();
        when(maintainerRepository.save(linked)).thenReturn(linked);
        IntegrationMethod method = new IntegrationMethod();

        ownershipService.assignMaintainer(method,
                new MaintainerDto(null, null, "evolveum", MaintainerType.ORG, "Evolveum"));

        assertSame(linked, method.getMaintainer());
    }

    @Test
    void savingAnItemOnTheOrganizationsOwnRowMovesItToTheCatalogWideRow() {
        callerIs("olivia", "OrganizationContributor", "evolveum");
        Maintainer linked = linkedEvolveumRow();
        Maintainer ownRow = stored(new Maintainer().setId(14L).setCategory(MaintainerType.ORG)
                .setOrganization(linked.getOrganization()));
        IntegrationMethod method = new IntegrationMethod();

        ownershipService.assignMaintainer(method, dtoOf(ownRow));

        assertSame(linked, method.getMaintainer());
    }

    @Test
    void anotherOrganizationKeepsItsOwnRow() {
        callerIs("olivia", "OrganizationContributor", "acme");
        linkedEvolveumRow();
        Organization acmeOrg = new Organization().setId(3).setName("acme");
        Maintainer ownRow = new Maintainer().setId(5L).setCategory(MaintainerType.ORG).setOrganization(acmeOrg);
        when(organizationService.getOrganizationByName("acme")).thenReturn(acmeOrg);
        when(maintainerRepository.findByCategoryAndOrganization(MaintainerType.ORG, acmeOrg))
                .thenReturn(Optional.of(ownRow));
        when(maintainerRepository.save(ownRow)).thenReturn(ownRow);
        IntegrationMethod method = new IntegrationMethod();

        ownershipService.assignMaintainer(method, new MaintainerDto(null, null, "acme", MaintainerType.ORG, "Acme"));

        assertSame(ownRow, method.getMaintainer());
    }

    @Test
    void organizationContributorMayAssignThemselves() {
        callerIs("olivia", "OrganizationContributor", "acme");
        Maintainer own = personal("olivia");
        IntegrationMethod method = new IntegrationMethod();

        ownershipService.assignMaintainer(method, dtoOf(own));

        assertSame(own, method.getMaintainer());
    }

    @Test
    void organizationContributorMayNotAssignAColleague() {
        callerIs("olivia", "OrganizationContributor", "acme");

        assertThrows(ResponseStatusException.class,
                () -> ownershipService.assignMaintainer(new IntegrationMethod(), dtoOf(personal("bake"))));
    }

    @Test
    void organizationContributorMayNotAssignAnotherOrganization() {
        callerIs("olivia", "OrganizationContributor", "acme");

        assertThrows(ResponseStatusException.class,
                () -> ownershipService.assignMaintainer(new IntegrationMethod(), dtoOf(organization("evolveum"))));
    }

    @Test
    void individualFromAnOrganizationMayAssignThemselvesButNotIt() {
        callerIs("olivia", "IndividualContributor", "acme");
        Maintainer own = personal("olivia");
        IntegrationMethod method = new IntegrationMethod();

        ownershipService.assignMaintainer(method, dtoOf(own));
        assertSame(own, method.getMaintainer());

        assertThrows(ResponseStatusException.class,
                () -> ownershipService.assignMaintainer(new IntegrationMethod(), dtoOf(organization("acme"))));
    }
}
