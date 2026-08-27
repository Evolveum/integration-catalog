/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.controller;

import com.evolveum.midpoint.integration.catalog.dto.CurrentUserDto;
import com.evolveum.midpoint.integration.catalog.object.Vote;
import com.evolveum.midpoint.integration.catalog.security.CatalogOidcUserService;
import com.evolveum.midpoint.integration.catalog.security.SecurityConfig;
import com.evolveum.midpoint.integration.catalog.service.ApplicationService;
import com.evolveum.midpoint.integration.catalog.service.AuthService;
import com.evolveum.midpoint.integration.catalog.service.TutorialStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests the security matrix of {@link SecurityConfig} together with the /api/auth
 * endpoints — the opposite setup to {@link ControllerTest}, which switches security off.
 * OIDC sessions are fabricated with spring-security-test's {@code oidcLogin()}; the role
 * authorities mirror what {@link CatalogOidcUserService} grants after a real login.
 */
@WebMvcTest(controllers = {AuthController.class, Controller.class})
@Import(SecurityConfig.class)
class AuthControllerTest {

    // JenkinsProperties needs no test setup: the application class's
    // @EnableConfigurationProperties registers it, and with no jenkins.* properties in the
    // test environment its callback token is null — the callback endpoints stay closed.

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CatalogOidcUserService catalogOidcUserService;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    // Collaborators of Controller, mocked so the slice loads; only a few are stubbed.
    @MockitoBean
    private ApplicationService applicationService;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.service.BundleService bundleService;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.mapper.ApplicationMapper applicationMapper;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.repository.ApplicationRepository applicationRepository;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.repository.RequestRepository requestRepository;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.repository.VoteRepository voteRepository;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.service.LogoStorageService logoStorageService;

    @MockitoBean
    private TutorialStorageService tutorialStorageService;

    @MockitoBean
    private com.evolveum.midpoint.integration.catalog.repository.DownloadRepository downloadRepository;

    private static RequestPostProcessor readOnlyUser() {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ReadOnly"));
    }

    private static RequestPostProcessor contributor() {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_OrganizationContributor"));
    }

    private static RequestPostProcessor superuser() {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_Superuser"));
    }

    // ---- /api/auth ----

    @Test
    void meRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsOrganizationIdentity() throws Exception {
        when(authService.getCurrentUser(any(), any())).thenReturn(new CurrentUserDto(
                "olivia", "Olivia Parker", "olivia@acme.example",
                "OrganizationContributor", "acme", "Acme co."));

        mockMvc.perform(get("/api/auth/me").with(readOnlyUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("olivia"))
                .andExpect(jsonPath("$.role").value("OrganizationContributor"))
                .andExpect(jsonPath("$.organizationId").value("acme"))
                .andExpect(jsonPath("$.organizationName").value("Acme co."));
    }

    @Test
    void organizationMembersRequireLogin() throws Exception {
        when(authService.getOrganizationMembers(any())).thenReturn(List.of("olivia", "dana"));

        mockMvc.perform(get("/api/auth/organization/members"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/organization/members").with(readOnlyUser()))
                .andExpect(status().isOk());
    }

    @Test
    void allMaintainersIsSuperuserOnly() throws Exception {
        when(authService.getAllMaintainers()).thenReturn(List.of("olivia", "Acme co."));

        mockMvc.perform(get("/api/auth/all-maintainers"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/all-maintainers").with(contributor()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/all-maintainers").with(superuser()))
                .andExpect(status().isOk());
    }

    // ---- catalog endpoint matrix ----

    @Test
    void catalogReadsArePublic() throws Exception {
        when(applicationService.list(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/applications"))
                .andExpect(status().isOk());
    }

    @Test
    void votingIsForAuthenticatedUsersIncludingReadOnly() throws Exception {
        Vote vote = new Vote();
        vote.setRequestId(1L);
        when(applicationService.submitVote(eq(1L), any())).thenReturn(vote);

        mockMvc.perform(post("/api/requests/{id}/vote", 1L).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/requests/{id}/vote", 1L).with(readOnlyUser()).with(csrf()))
                .andExpect(status().isCreated());
    }

    @Test
    void uploadRequiresContributorRole() throws Exception {
        mockMvc.perform(post("/api/upload/connector").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/upload/connector").with(readOnlyUser()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestCreationAndCancellationRequireContributorRole() throws Exception {
        mockMvc.perform(post("/api/requests").with(readOnlyUser()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/requests/{id}", 9L).with(readOnlyUser()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewWorkflowIsSuperuserOnly() throws Exception {
        mockMvc.perform(post("/api/applications/1/integration-method/2/3/start-review")
                        .with(contributor()).with(csrf()))
                .andExpect(status().isForbidden());
    }
}
