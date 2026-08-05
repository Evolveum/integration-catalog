/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import com.evolveum.midpoint.integration.catalog.security.KeycloakUserService.KeycloakUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests the Keycloak Admin API client against a mocked HTTP layer: the service-account
 * token caching, the organization member index, the short-lived caches and — most
 * importantly — the fail-soft behavior (stale cache + backoff) while Keycloak is down.
 * Time is supplied by the test, so TTL and backoff expiry are exercised without sleeping.
 */
class KeycloakUserServiceTest {

    private static final String ISSUER = "http://keycloak.test/realms/catalog";
    private static final String TOKEN_URL = ISSUER + "/protocol/openid-connect/token";
    private static final String USERS_URL = "http://keycloak.test/admin/realms/catalog/users";
    private static final String ORGS_URL = "http://keycloak.test/admin/realms/catalog/organizations";

    private static final String TOKEN_JSON = "{\"access_token\":\"token-1\",\"expires_in\":300}";
    private static final String ORGS_JSON = "[{\"id\":\"o1\",\"alias\":\"acme\",\"name\":\"Acme co.\"}]";
    private static final String MEMBERS_JSON = "[{\"username\":\"u1\"}]";
    private static final String U1_JSON =
            "[{\"username\":\"u1\",\"attributes\":{\"role\":[\"OrganizationContributor\"]}}]";
    private static final String USERS_PAGE_JSON = "["
            + "{\"username\":\"u1\",\"attributes\":{\"role\":[\"OrganizationContributor\"]}},"
            + "{\"username\":\"u2\",\"attributes\":{\"role\":[\"ReadOnly\"]}},"
            + "{\"username\":\"service-account-integration-catalog\"}"
            + "]";

    private MockRestServiceServer server;
    private KeycloakUserService service;
    /** Controllable "current time"; starts at 0 and is advanced by the tests. */
    private long now;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new KeycloakUserService(ISSUER, "integration-catalog", "secret",
                builder.build(), () -> now);
    }

    private void expectToken() {
        server.expect(requestTo(TOKEN_URL)).andExpect(method(POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
    }

    private void expectOrganizationIndex() {
        server.expect(requestTo(ORGS_URL + "?first=0&max=100")).andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(withSuccess(ORGS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ORGS_URL + "/o1/members?first=0&max=100")).andExpect(method(GET))
                .andRespond(withSuccess(MEMBERS_JSON, MediaType.APPLICATION_JSON));
    }

    @Test
    void findUserResolvesRoleAndOrganizationAndCaches() {
        expectToken();
        expectOrganizationIndex();
        server.expect(requestTo(USERS_URL + "?exact=true&username=u1")).andExpect(method(GET))
                .andRespond(withSuccess(U1_JSON, MediaType.APPLICATION_JSON));

        Optional<KeycloakUser> user = service.findUser("U1");

        assertEquals(Optional.of(new KeycloakUser("u1", "OrganizationContributor", "acme", "Acme co.")),
                user);

        // Within the cache TTL the same lookup makes no further HTTP calls (the single
        // token request above also proves the service-account token is reused).
        assertEquals(user, service.findUser("u1"));
        server.verify();
    }

    @Test
    void listUsersFiltersServiceAccountsAndIndexesOrganizations() {
        expectToken();
        expectOrganizationIndex();
        server.expect(requestTo(USERS_URL + "?first=0&max=100")).andExpect(method(GET))
                .andRespond(withSuccess(USERS_PAGE_JSON, MediaType.APPLICATION_JSON));

        List<KeycloakUser> users = service.listUsers();

        assertEquals(List.of(
                new KeycloakUser("u1", "OrganizationContributor", "acme", "Acme co."),
                new KeycloakUser("u2", "ReadOnly", null, null)), users);

        // Organization filtering runs on the cached listing — no further HTTP calls.
        assertEquals(List.of("u1"), service.findUsersByOrganization("acme").stream()
                .map(KeycloakUser::username)
                .toList());
        server.verify();
    }

    @Test
    void unavailableKeycloakServesStaleCacheThenBacksOffThenRecovers() {
        Optional<KeycloakUser> u1 =
                Optional.of(new KeycloakUser("u1", "OrganizationContributor", "acme", "Acme co."));

        // The mock server requires ALL expectations before the first actual request; they
        // are matched strictly in this order, so any HTTP call made during the backoff
        // phase below would consume one out of order and fail the final verify().
        // Phase 1 - healthy lookup fills the caches:
        expectToken();
        expectOrganizationIndex();
        server.expect(requestTo(USERS_URL + "?exact=true&username=u1"))
                .andRespond(withSuccess(U1_JSON, MediaType.APPLICATION_JSON));
        // Phase 2 - after the cache TTL Keycloak is down:
        server.expect(requestTo(ORGS_URL + "?first=0&max=100")).andRespond(withServerError());
        server.expect(requestTo(USERS_URL + "?exact=true&username=u1")).andRespond(withServerError());
        // Phase 4 - after the backoff Keycloak is probed again (token from phase 1 is
        // still valid, so no new token request):
        expectOrganizationIndex();
        server.expect(requestTo(USERS_URL + "?exact=true&username=someone-else"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        // Phase 1) Healthy lookup fills the caches.
        assertEquals(u1, service.findUser("u1"));

        // Phase 2) Cache TTL passes, Keycloak goes down: the stale answer is served instead.
        now += 61_000;
        assertEquals(u1, service.findUser("u1"));

        // Phase 3) Within the backoff window nothing is asked over HTTP at all:
        // unknown users read empty, the stale hit keeps being served.
        assertEquals(Optional.empty(), service.findUser("someone-else"));
        assertEquals(List.of(), service.listUsers());
        assertEquals(u1, service.findUser("u1"));

        // Phase 4) After the backoff Keycloak answers again.
        now += 16_000;
        assertEquals(Optional.empty(), service.findUser("someone-else"));

        server.verify();
    }
}
