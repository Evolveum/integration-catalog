/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests the realm user listing against a mocked HTTP layer: pagination, the service-account token
 * being reused, the short-lived cache and - most importantly - the fail-soft behaviour, since an
 * unreachable Keycloak must cost the maintainer list its Keycloak half and nothing else. Time is
 * supplied by the test, so cache and backoff expiry are exercised without sleeping.
 *
 * <p>All expectations are declared before the first call: {@link MockRestServiceServer} is ordered
 * and refuses expectations added after a request has been made.
 */
class KeycloakUserDirectoryTest {

    private static final String ISSUER = "http://keycloak.test/realms/catalog";
    private static final String TOKEN_URL = ISSUER + "/protocol/openid-connect/token";
    private static final String USERS_URL = "http://keycloak.test/admin/realms/catalog/users";

    private static final String TOKEN_JSON = "{\"access_token\":\"token-1\",\"expires_in\":300}";

    /** A short page: two people and the client's own service account, which is not a person. */
    private static final String USERS_JSON = "["
            + "{\"username\":\"u1\"},"
            + "{\"username\":\"u2\"},"
            + "{\"username\":\"service-account-integration-catalog\"}"
            + "]";

    private MockRestServiceServer server;
    private KeycloakUserDirectory directory;

    /** Controllable "current time"; starts at 0 and is advanced by the tests. */
    private long now;

    @BeforeEach
    void setUp() {
        directory = build(true);
    }

    private KeycloakUserDirectory build(boolean enabled) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new KeycloakUserDirectory(ISSUER, "integration-catalog", "secret", enabled,
                builder.build(), () -> now);
    }

    private void expectToken() {
        server.expect(requestTo(TOKEN_URL)).andExpect(method(POST))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
    }

    private void expectPage(int first, String json) {
        server.expect(requestTo(USERS_URL + "?first=" + first + "&max=100")).andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void listsHumanUsersAndCachesThem() {
        expectToken();
        expectPage(0, USERS_JSON);

        assertEquals(List.of("u1", "u2"), directory.listUsernames());

        // Within the TTL nothing further is fetched; the single token request above also proves
        // the service-account token is reused rather than requested per call.
        assertEquals(List.of("u1", "u2"), directory.listUsernames());
        server.verify();
    }

    @Test
    void refetchesOnceTheCacheHasExpired() {
        expectToken();
        expectPage(0, USERS_JSON);
        expectPage(0, "[{\"username\":\"u3\"}]");

        assertEquals(List.of("u1", "u2"), directory.listUsernames());
        now += 60_001;

        assertEquals(List.of("u3"), directory.listUsernames());
        server.verify();
    }

    @Test
    void followsPaginationUntilAPartialPage() {
        String fullPage = IntStream.range(0, 100)
                .mapToObj(i -> "{\"username\":\"u" + i + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
        expectToken();
        expectPage(0, fullPage);
        expectPage(100, "[{\"username\":\"last\"}]");

        List<String> usernames = directory.listUsernames();

        assertEquals(101, usernames.size());
        assertEquals("last", usernames.get(100));
        server.verify();
    }

    @Test
    void servesTheStaleListWhileKeycloakIsDownAndBacksOff() {
        expectToken();
        expectPage(0, USERS_JSON);
        server.expect(requestTo(USERS_URL + "?first=0&max=100")).andRespond(withServerError());
        expectPage(0, "[{\"username\":\"u9\"}]");

        assertEquals(List.of("u1", "u2"), directory.listUsernames());

        // The cache has expired and Keycloak now fails: the last known answer is served instead.
        now += 60_001;
        assertEquals(List.of("u1", "u2"), directory.listUsernames());

        // Still inside the backoff window, so no request is attempted at all.
        now += 10_000;
        assertEquals(List.of("u1", "u2"), directory.listUsernames());

        // Backoff over - Keycloak is tried again and recovers.
        now += 5_001;
        assertEquals(List.of("u9"), directory.listUsernames());
        server.verify();
    }

    @Test
    void answersEmptyWithoutCallingKeycloakWhenDisabled() {
        KeycloakUserDirectory disabled = build(false);

        assertTrue(disabled.listUsernames().isEmpty());
        server.verify();
    }
}
