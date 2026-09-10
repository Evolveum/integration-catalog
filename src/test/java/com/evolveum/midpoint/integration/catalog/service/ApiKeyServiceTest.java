/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.GraviteeProperties;
import com.evolveum.midpoint.integration.catalog.dto.CreateApiKeyRequestDto;
import com.evolveum.midpoint.integration.catalog.dto.CreatedApiKeyDto;
import com.evolveum.midpoint.integration.catalog.integration.GraviteeClient;
import com.evolveum.midpoint.integration.catalog.object.ApiKey;
import com.evolveum.midpoint.integration.catalog.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the API key creation rules and for the one-application-per-key model. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyServiceTest {

    private static final String SUB = "9f1c-uuid-sub";

    @Mock
    private ApiKeyRepository repository;

    @Mock
    private GraviteeClient gravitee;

    private static OidcUser user() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject(SUB)
                .claim("preferred_username", "olivia")
                .build();
        return new DefaultOidcUser(List.of(), idToken);
    }

    private ApiKeyService serviceWith(GraviteeProperties properties) {
        return new ApiKeyService(repository, gravitee, properties);
    }

    private static GraviteeProperties configured() {
        return new GraviteeProperties("http://localhost:8083", null, null, "api-1", "plan-1", "token");
    }

    @Test
    void unconfiguredGraviteeIsServiceUnavailableAndNothingIsCalled() {
        ApiKeyService service = serviceWith(new GraviteeProperties(null, null, null, null, null, null));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.create(user(), new CreateApiKeyRequestDto("My key", null)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    void blankNameIsRejected() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).create(user(), new CreateApiKeyRequestDto("  ", null)));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
    }

    @Test
    void expirationInThePastIsRejected() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).create(user(),
                        new CreateApiKeyRequestDto("My key", Instant.now().minusSeconds(60))));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
    }

    @Test
    void expirationBeyondTheCapIsRejected() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).create(user(),
                        new CreateApiKeyRequestDto("My key", Instant.now().plus(400, ChronoUnit.DAYS))));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
    }

    @Test
    void firstKeyCreatesTheApplicationAndReturnsTheValueOnce() throws Exception {
        when(gravitee.createApplication("olivia", SUB, "My key")).thenReturn("app-1");
        when(gravitee.createSubscription("app-1")).thenReturn("sub-1");
        when(gravitee.fetchApiKey("sub-1")).thenReturn(new GraviteeClient.ApiKeyMaterial("key-1", "the-secret"));

        CreatedApiKeyDto created = serviceWith(configured())
                .create(user(), new CreateApiKeyRequestDto("My key", null));

        assertEquals("the-secret", created.value());
        assertTrue(created.expirationAccepted());

        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(saved.capture());
        assertEquals("My key", saved.getValue().getName());
        assertEquals(SUB, saved.getValue().getOwnerSub());
        assertEquals("olivia", saved.getValue().getOwnerUsername());
        assertEquals("app-1", saved.getValue().getGraviteeApplicationId());
        assertEquals("sub-1", saved.getValue().getGraviteeSubscriptionId());
        assertEquals("key-1", saved.getValue().getGraviteeApiKeyId());
        // The value is never part of what is stored.
        assertNull(saved.getValue().getExpiresAt());
    }

    /** Gravitee refuses a second subscription of one application to one plan. */
    @Test
    void everyKeyGetsItsOwnApplication() throws Exception {
        when(gravitee.createApplication("olivia", SUB, "Second")).thenReturn("app-2");
        when(gravitee.createSubscription("app-2")).thenReturn("sub-2");
        when(gravitee.fetchApiKey("sub-2")).thenReturn(new GraviteeClient.ApiKeyMaterial("key-2", "another"));

        serviceWith(configured()).create(user(), new CreateApiKeyRequestDto("Second", null));

        verify(gravitee).createApplication("olivia", SUB, "Second");
        verify(gravitee).createSubscription("app-2");
    }

    /** A key Gravitee refuses to expire is kept, but the row must not claim an expiration. */
    @Test
    void refusedExpirationIsReportedAndNotStored() throws Exception {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        when(gravitee.createApplication(anyString(), anyString(), anyString())).thenReturn("app-1");
        when(gravitee.createSubscription("app-1")).thenReturn("sub-1");
        when(gravitee.fetchApiKey("sub-1")).thenReturn(new GraviteeClient.ApiKeyMaterial("key-1", "secret"));
        when(gravitee.expireSubscription(eq("sub-1"), any())).thenReturn(false);

        CreatedApiKeyDto created = serviceWith(configured())
                .create(user(), new CreateApiKeyRequestDto("My key", expiresAt));

        assertEquals(false, created.expirationAccepted());
        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(saved.capture());
        assertNull(saved.getValue().getExpiresAt());
    }

    @Test
    void graviteeFailureIsABadGatewayAndStoresNothing() throws Exception {
        when(gravitee.createApplication(anyString(), anyString(), anyString())).thenReturn("app-1");
        when(gravitee.createSubscription("app-1")).thenThrow(new IOException("Gravitee refused"));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).create(user(), new CreateApiKeyRequestDto("My key", null)));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        verify(repository, never()).save(any());
    }
}
