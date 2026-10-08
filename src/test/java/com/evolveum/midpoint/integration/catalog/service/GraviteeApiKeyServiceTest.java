/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.service;

import com.evolveum.midpoint.integration.catalog.configuration.GraviteeProperties;
import com.evolveum.midpoint.integration.catalog.dto.ApiKeyDto;
import com.evolveum.midpoint.integration.catalog.dto.CreateApiKeyRequestDto;
import com.evolveum.midpoint.integration.catalog.dto.CreatedApiKeyDto;
import com.evolveum.midpoint.integration.catalog.integration.GraviteeClient;
import com.evolveum.midpoint.integration.catalog.object.GraviteeApiKey;
import com.evolveum.midpoint.integration.catalog.object.GraviteeApplication;
import com.evolveum.midpoint.integration.catalog.object.GraviteeSubscription;
import com.evolveum.midpoint.integration.catalog.repository.GraviteeApiKeyRepository;
import com.evolveum.midpoint.integration.catalog.repository.GraviteeApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for the API key rules and for the one-application-per-key model, with one or more APIs. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraviteeApiKeyServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    // Gravitee ids are UUIDs, and the catalog stores them as such.
    private static final UUID APP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SUB_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID SUB_ID_2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID KEY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID KEY_ID_2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID API_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID API_ID_2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    private static final GraviteeProperties.Api API = new GraviteeProperties.Api(API_ID.toString(), "plan-1");
    private static final GraviteeProperties.Api API_2 = new GraviteeProperties.Api(API_ID_2.toString(), "plan-2");

    @Mock
    private GraviteeApplicationRepository applicationRepository;

    @Mock
    private GraviteeApiKeyRepository apiKeyRepository;

    @Mock
    private GraviteeClient gravitee;

    /** Named by preferred_username, as the OIDC registration's user-name-attribute configures. */
    private static OidcUser user() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("olivia-subject")
                .claim("preferred_username", "olivia")
                .build();
        return new DefaultOidcUser(List.of(), idToken, "preferred_username");
    }

    private ApiKeyService serviceWith(GraviteeProperties properties) {
        return new ApiKeyService(applicationRepository, apiKeyRepository, gravitee, properties);
    }

    private ApiKeyService service() {
        return serviceWith(configuredFor(List.of(API)));
    }

    private static GraviteeProperties configuredFor(List<GraviteeProperties.Api> apis) {
        return new GraviteeProperties("http://localhost:8083", null, null, apis, "token", TIMEOUT);
    }

    private static GraviteeProperties unconfigured() {
        return new GraviteeProperties(null, null, null, null, null, TIMEOUT);
    }

    // ---- fixture: an application of olivia's with one subscription, keys linked both ways ----

    private static GraviteeApplication application() {
        GraviteeApplication application = new GraviteeApplication();
        application.setId(APP_ID);
        application.setName("qwe");
        application.setOwnerUsername("olivia");
        GraviteeSubscription subscription = new GraviteeSubscription();
        subscription.setId(SUB_ID);
        subscription.setApiId(API_ID);
        subscription.setApplication(application);
        application.getSubscriptions().add(subscription);
        return application;
    }

    private static GraviteeApiKey keyOf(GraviteeApplication application, UUID id) {
        GraviteeApiKey key = new GraviteeApiKey();
        key.setId(id);
        key.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        key.setApplication(application);
        application.getApiKeys().add(key);
        return key;
    }

    private void lockable(GraviteeApiKey key) {
        when(apiKeyRepository.findByIdForUpdate(key.getId())).thenReturn(Optional.of(key));
    }

    private void createsApplicationWithKey(String secret) throws Exception {
        when(gravitee.createApplication(anyString(), anyString())).thenReturn(APP_ID.toString());
        when(gravitee.createSubscription(APP_ID.toString(), API_ID.toString(), "plan-1")).thenReturn(SUB_ID.toString());
        when(gravitee.createSubscription(APP_ID.toString(), API_ID_2.toString(), "plan-2")).thenReturn(SUB_ID_2.toString());
        when(gravitee.fetchApiKey(APP_ID)).thenReturn(new GraviteeClient.ApiKeyMaterial(KEY_ID.toString(), secret));
    }

    private GraviteeApplication savedApplication() {
        ArgumentCaptor<GraviteeApplication> saved = ArgumentCaptor.forClass(GraviteeApplication.class);
        verify(applicationRepository).save(saved.capture());
        return saved.getValue();
    }

    // ---- create: input rules ----

    @Test
    void unconfiguredGraviteeIsServiceUnavailableAndNothingIsCalled() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(unconfigured()).create(user(), new CreateApiKeyRequestDto("My key", null)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        verifyNoInteractions(gravitee);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void blankNameIsRejected() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().create(user(), new CreateApiKeyRequestDto("  ", null)));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        verifyNoInteractions(gravitee);
    }

    @Test
    void expirationInThePastIsRejected() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().create(user(), new CreateApiKeyRequestDto("My key", Instant.now().minusSeconds(60))));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
    }

    @Test
    void expirationBeyondTheCapIsRejected() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().create(user(), new CreateApiKeyRequestDto("My key", Instant.now().plus(400, ChronoUnit.DAYS))));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
    }

    // ---- create ----

    @Test
    void firstKeyCreatesTheApplicationAndReturnsTheValueOnce() throws Exception {
        createsApplicationWithKey("the-secret");

        CreatedApiKeyDto created = service().create(user(), new CreateApiKeyRequestDto("My key", null));

        assertEquals("the-secret", created.value());
        assertTrue(created.expirationAccepted());
        verify(gravitee).createApplication("olivia", "My key");
        verify(gravitee, never()).expireSubscription(anyString(), anyString(), any());

        GraviteeApplication saved = savedApplication();
        assertEquals(APP_ID, saved.getId());
        assertEquals("My key", saved.getName());
        assertEquals("olivia", saved.getOwnerUsername());
        assertEquals(1, saved.getSubscriptions().size());
        assertEquals(SUB_ID, saved.getSubscriptions().getFirst().getId());
        assertEquals(API_ID, saved.getSubscriptions().getFirst().getApiId());
        GraviteeApiKey key = saved.getApiKeys().getFirst();
        assertEquals(KEY_ID, key.getId());
        // Only the last four characters are kept, to tell a renewed key from its predecessor.
        assertEquals("cret", key.getKeyHint());
        assertNull(key.getExpiresAt());
    }

    @Test
    void acceptedExpirationIsStored() throws Exception {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        createsApplicationWithKey("secret");
        when(gravitee.expireSubscription(SUB_ID.toString(), API_ID.toString(), expiresAt)).thenReturn(true);

        CreatedApiKeyDto created = service().create(user(), new CreateApiKeyRequestDto("My key", expiresAt));

        assertTrue(created.expirationAccepted());
        assertEquals(expiresAt, savedApplication().getApiKeys().getFirst().getExpiresAt());
    }

    /** A key Gravitee refuses to expire is kept, but the row must not claim an expiration. */
    @Test
    void refusedExpirationIsReportedAndNotStored() throws Exception {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        createsApplicationWithKey("secret");
        when(gravitee.expireSubscription(SUB_ID.toString(), API_ID.toString(), expiresAt)).thenReturn(false);

        CreatedApiKeyDto created = service().create(user(), new CreateApiKeyRequestDto("My key", expiresAt));

        assertFalse(created.expirationAccepted());
        assertNull(savedApplication().getApiKeys().getFirst().getExpiresAt());
    }

    /** With several APIs the one shared key gets a subscription to each. */
    @Test
    void everyConfiguredApiGetsASubscription() throws Exception {
        createsApplicationWithKey("secret");

        serviceWith(configuredFor(List.of(API, API_2))).create(user(), new CreateApiKeyRequestDto("My key", null));

        verify(gravitee).createSubscription(APP_ID.toString(), API_ID.toString(), "plan-1");
        verify(gravitee).createSubscription(APP_ID.toString(), API_ID_2.toString(), "plan-2");
        GraviteeApplication saved = savedApplication();
        assertEquals(List.of(SUB_ID, SUB_ID_2), saved.getSubscriptions().stream().map(GraviteeSubscription::getId).toList());
        assertEquals(1, saved.getApiKeys().size());
    }

    /**
     * One refusal is enough for the key not to expire: it keeps working on that API. The refusal
     * coming first must not be undone by a later acceptance, and must not skip the later subscription.
     */
    @Test
    void expirationRefusedForOneApiIsNotStored() throws Exception {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        createsApplicationWithKey("secret");
        when(gravitee.expireSubscription(SUB_ID.toString(), API_ID.toString(), expiresAt)).thenReturn(false);
        when(gravitee.expireSubscription(SUB_ID_2.toString(), API_ID_2.toString(), expiresAt)).thenReturn(true);

        CreatedApiKeyDto created = serviceWith(configuredFor(List.of(API, API_2)))
                .create(user(), new CreateApiKeyRequestDto("My key", expiresAt));

        assertFalse(created.expirationAccepted());
        assertNull(savedApplication().getApiKeys().getFirst().getExpiresAt());
        verify(gravitee).expireSubscription(SUB_ID_2.toString(), API_ID_2.toString(), expiresAt);
    }

    @Test
    void expirationAcceptedForEveryApiIsStored() throws Exception {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        createsApplicationWithKey("secret");
        when(gravitee.expireSubscription(anyString(), anyString(), eq(expiresAt))).thenReturn(true);

        CreatedApiKeyDto created = serviceWith(configuredFor(List.of(API, API_2)))
                .create(user(), new CreateApiKeyRequestDto("My key", expiresAt));

        assertTrue(created.expirationAccepted());
        assertEquals(expiresAt, savedApplication().getApiKeys().getFirst().getExpiresAt());
    }

    @Test
    void graviteeFailureIsABadGatewayAndStoresNothing() throws Exception {
        createsApplicationWithKey("secret");
        when(gravitee.createSubscription(APP_ID.toString(), API_ID.toString(), "plan-1"))
                .thenThrow(new IOException("Gravitee refused"));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().create(user(), new CreateApiKeyRequestDto("My key", null)));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        verify(applicationRepository, never()).save(any());
    }

    // ---- revoke ----

    /** Gravitee must go first: the reverse order would show a key as dead while it still works. */
    @Test
    void revokingTheLastKeyClosesTheSubscriptionsBeforeStampingTheRow() throws Exception {
        GraviteeApiKey key = keyOf(application(), KEY_ID);
        lockable(key);

        service().revoke(user(), KEY_ID.toString());

        InOrder order = inOrder(gravitee, apiKeyRepository);
        order.verify(gravitee).closeSubscription(SUB_ID, API_ID);
        order.verify(gravitee).revokeApiKey(APP_ID, KEY_ID);
        order.verify(apiKeyRepository).save(key);
        assertNotNull(key.getRevokedAt());
    }

    @Test
    void revokingTheLastKeyClosesEverySubscription() throws Exception {
        GraviteeApplication application = application();
        GraviteeSubscription second = new GraviteeSubscription();
        second.setId(SUB_ID_2);
        second.setApiId(API_ID_2);
        second.setApplication(application);
        application.getSubscriptions().add(second);
        GraviteeApiKey key = keyOf(application, KEY_ID);
        lockable(key);

        serviceWith(configuredFor(List.of(API, API_2))).revoke(user(), KEY_ID.toString());

        verify(gravitee).closeSubscription(SUB_ID, API_ID);
        verify(gravitee).closeSubscription(SUB_ID_2, API_ID_2);
    }

    @Test
    void revokingTwiceChangesNothing() {
        GraviteeApiKey key = keyOf(application(), KEY_ID);
        key.setRevokedAt(Instant.now().minusSeconds(60));
        lockable(key);

        service().revoke(user(), KEY_ID.toString());

        verifyNoInteractions(gravitee);
        verify(apiKeyRepository, never()).save(any());
    }

    /** Somebody else's key is reported missing rather than forbidden. */
    @Test
    void revokingAKeyOfAnotherUserIsNotFound() {
        GraviteeApiKey key = keyOf(application(), KEY_ID);
        key.getApplication().setOwnerUsername("someone-else");
        lockable(key);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().revoke(user(), KEY_ID.toString()));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        verifyNoInteractions(gravitee);
    }

    @Test
    void revokingAnUnknownIdIsNotFound() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().revoke(user(), "not-a-uuid"));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
    }

    /** During the grace period the old key can be revoked alone, without touching the new one. */
    @Test
    void revokingTheReplacedKeyLeavesTheNewOneWorking() throws Exception {
        GraviteeApplication application = application();
        GraviteeApiKey old = keyOf(application, KEY_ID);
        old.setReplacedAt(Instant.now().minusSeconds(60));
        old.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
        GraviteeApiKey renewed = keyOf(application, KEY_ID_2);
        lockable(old);

        service().revoke(user(), KEY_ID.toString());

        verify(gravitee).revokeApiKey(APP_ID, KEY_ID);
        verify(gravitee, never()).closeSubscription(any(), any());
        assertNotNull(old.getRevokedAt());
        assertNull(renewed.getRevokedAt());
    }

    /** A refusal from Gravitee must leave the row alone, so nothing claims a key is dead. */
    @Test
    void revokeFailureLeavesTheRowUnchanged() throws Exception {
        GraviteeApiKey key = keyOf(application(), KEY_ID);
        lockable(key);
        doThrow(new IOException("Gravitee refused")).when(gravitee).closeSubscription(SUB_ID, API_ID);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().revoke(user(), KEY_ID.toString()));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertNull(key.getRevokedAt());
        verify(apiKeyRepository, never()).save(any());
    }

    // ---- list ----

    /** A key revoked in the Gravitee console must not keep showing as active. */
    @Test
    void listFoldsInARevocationMadeInGravitee() throws Exception {
        Instant revokedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        GraviteeApplication application = application();
        GraviteeApiKey key = keyOf(application, KEY_ID);
        when(applicationRepository.findByOwnerUsername("olivia")).thenReturn(List.of(application));
        when(gravitee.listApiKeys(APP_ID)).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey(KEY_ID.toString(), null, true, revokedAt)));

        List<ApiKeyDto> listed = service().list(user());

        assertEquals(revokedAt, listed.getFirst().revokedAt());
        verify(apiKeyRepository).save(key);
    }

    /** Covers an expiration Gravitee applied although its answer never reached the catalog. */
    @Test
    void listTakesAnEarlierExpirationFromGravitee() throws Exception {
        Instant graviteeEnd = Instant.now().plus(1, ChronoUnit.DAYS);
        GraviteeApplication application = application();
        GraviteeApiKey key = keyOf(application, KEY_ID);
        when(applicationRepository.findByOwnerUsername("olivia")).thenReturn(List.of(application));
        when(gravitee.listApiKeys(APP_ID)).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey(KEY_ID.toString(), graviteeEnd, false, null)));

        service().list(user());

        assertEquals(graviteeEnd, key.getExpiresAt());
        assertNull(key.getRevokedAt());
        verify(apiKeyRepository).save(key);
    }

    /** Reconciling only ever shortens a key's life. */
    @Test
    void listKeepsAnEarlierRecordedExpiration() throws Exception {
        Instant recordedEnd = Instant.now().plus(1, ChronoUnit.HOURS);
        GraviteeApplication application = application();
        GraviteeApiKey key = keyOf(application, KEY_ID);
        key.setExpiresAt(recordedEnd);
        when(applicationRepository.findByOwnerUsername("olivia")).thenReturn(List.of(application));
        when(gravitee.listApiKeys(APP_ID)).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey(KEY_ID.toString(), Instant.now().plus(5, ChronoUnit.DAYS), false, null)));

        service().list(user());

        assertEquals(recordedEnd, key.getExpiresAt());
        verify(apiKeyRepository, never()).save(any());
    }

    /** An application whose keys have all ended is not asked about. */
    @Test
    void listSkipsApplicationsWithoutAWorkingKey() throws Exception {
        GraviteeApplication application = application();
        keyOf(application, KEY_ID).setRevokedAt(Instant.now().minusSeconds(60));
        keyOf(application, KEY_ID_2).setExpiresAt(Instant.now().minusSeconds(60));
        when(applicationRepository.findByOwnerUsername("olivia")).thenReturn(List.of(application));

        List<ApiKeyDto> listed = service().list(user());

        assertEquals(2, listed.size());
        verify(gravitee, never()).listApiKeys(any());
    }

    /** A key already revoked here keeps its own revocation time, and is not written again. */
    @Test
    void listDoesNotRestampARevokedKey() throws Exception {
        Instant revokedHere = Instant.now().minus(2, ChronoUnit.DAYS);
        GraviteeApplication application = application();
        GraviteeApiKey revoked = keyOf(application, KEY_ID);
        revoked.setRevokedAt(revokedHere);
        keyOf(application, KEY_ID_2);
        when(applicationRepository.findByOwnerUsername("olivia")).thenReturn(List.of(application));
        when(gravitee.listApiKeys(APP_ID)).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey(KEY_ID.toString(), null, true, Instant.now()),
                new GraviteeClient.GraviteeApiKey(KEY_ID_2.toString(), null, false, null)));

        service().list(user());

        assertEquals(revokedHere, revoked.getRevokedAt());
        verify(apiKeyRepository, never()).save(any());
    }

    /** An unreachable Gravitee must not break the settings page. */
    @Test
    void listShowsTheRecordedStateWhenGraviteeCannotBeRead() throws Exception {
        GraviteeApplication application = application();
        keyOf(application, KEY_ID);
        when(applicationRepository.findByOwnerUsername("olivia")).thenReturn(List.of(application));
        when(gravitee.listApiKeys(APP_ID)).thenThrow(new IOException("Gravitee refused"));

        List<ApiKeyDto> listed = service().list(user());

        assertEquals(1, listed.size());
        assertNull(listed.getFirst().revokedAt());
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void listWithoutGraviteeConfiguredDoesNotCallIt() {
        GraviteeApplication application = application();
        keyOf(application, KEY_ID);
        when(applicationRepository.findByOwnerUsername("olivia")).thenReturn(List.of(application));

        List<ApiKeyDto> listed = serviceWith(unconfigured()).list(user());

        assertEquals(1, listed.size());
        verifyNoInteractions(gravitee);
    }

    @Test
    void listIsNewestFirstAcrossApplications() {
        GraviteeApplication older = application();
        keyOf(older, KEY_ID).setCreatedAt(Instant.now().minus(3, ChronoUnit.DAYS));
        GraviteeApplication newer = application();
        newer.setId(UUID.randomUUID());
        keyOf(newer, KEY_ID_2).setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(applicationRepository.findByOwnerUsername("olivia")).thenReturn(List.of(older, newer));

        List<ApiKeyDto> listed = serviceWith(unconfigured()).list(user());

        assertEquals(List.of(KEY_ID_2.toString(), KEY_ID.toString()), listed.stream().map(ApiKeyDto::id).toList());
    }

    // ---- renew ----

    @Test
    void renewReplacesTheOldKeyAndReturnsTheNewValueOnce() throws Exception {
        Instant graceEnd = Instant.now().plus(2, ChronoUnit.HOURS);
        GraviteeApplication application = application();
        GraviteeApiKey old = keyOf(application, KEY_ID);
        lockable(old);
        when(gravitee.renewApiKey(APP_ID)).thenReturn(new GraviteeClient.ApiKeyMaterial(KEY_ID_2.toString(), "new-secret-abcd"));
        when(gravitee.listApiKeys(APP_ID)).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey(KEY_ID.toString(), graceEnd, false, null),
                new GraviteeClient.GraviteeApiKey(KEY_ID_2.toString(), null, false, null)));

        CreatedApiKeyDto renewed = service().renew(user(), KEY_ID.toString());

        assertEquals("new-secret-abcd", renewed.value());
        assertEquals("abcd", renewed.key().keyHint());
        assertNotNull(old.getReplacedAt());
        assertEquals(graceEnd, old.getExpiresAt());

        ArgumentCaptor<GraviteeApiKey> saved = ArgumentCaptor.forClass(GraviteeApiKey.class);
        verify(apiKeyRepository, times(2)).save(saved.capture());
        assertEquals(old, saved.getAllValues().get(0));
        GraviteeApiKey stored = saved.getAllValues().get(1);
        assertEquals(KEY_ID_2, stored.getId());
        assertEquals(application, stored.getApplication());
        assertNull(stored.getExpiresAt());
        assertNull(stored.getReplacedAt());
    }

    /** A rotation must not let access outlive the subscription the user chose to end. */
    @Test
    void renewKeepsTheSubscriptionEnd() throws Exception {
        Instant subscriptionEnd = Instant.now().plus(1, ChronoUnit.HOURS);
        GraviteeApiKey old = keyOf(application(), KEY_ID);
        old.setExpiresAt(subscriptionEnd);
        lockable(old);
        when(gravitee.renewApiKey(APP_ID)).thenReturn(new GraviteeClient.ApiKeyMaterial(KEY_ID_2.toString(), "new-secret"));
        when(gravitee.listApiKeys(APP_ID)).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey(KEY_ID.toString(), Instant.now().plus(2, ChronoUnit.HOURS), false, null)));

        CreatedApiKeyDto renewed = service().renew(user(), KEY_ID.toString());

        assertEquals(subscriptionEnd, renewed.key().expiresAt());
        assertEquals(subscriptionEnd, old.getExpiresAt());
    }

    /** The new key already exists in Gravitee, so an unreadable key list must not lose it. */
    @Test
    void renewFallsBackToTheGracePeriodWhenTheKeysCannotBeRead() throws Exception {
        GraviteeApiKey old = keyOf(application(), KEY_ID);
        lockable(old);
        when(gravitee.renewApiKey(APP_ID)).thenReturn(new GraviteeClient.ApiKeyMaterial(KEY_ID_2.toString(), "new-secret"));
        when(gravitee.listApiKeys(APP_ID)).thenThrow(new IOException("Gravitee refused"));

        CreatedApiKeyDto renewed = service().renew(user(), KEY_ID.toString());

        assertEquals("new-secret", renewed.value());
        assertNotNull(old.getExpiresAt());
        assertTrue(old.getExpiresAt().isAfter(Instant.now().plus(119, ChronoUnit.MINUTES)));
    }

    @Test
    void renewingARevokedKeyIsAConflict() {
        GraviteeApiKey key = keyOf(application(), KEY_ID);
        key.setRevokedAt(Instant.now().minusSeconds(60));
        lockable(key);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().renew(user(), KEY_ID.toString()));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        verifyNoInteractions(gravitee);
    }

    @Test
    void renewingAnAlreadyReplacedKeyIsAConflict() {
        GraviteeApiKey key = keyOf(application(), KEY_ID);
        key.setReplacedAt(Instant.now().minusSeconds(60));
        key.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        lockable(key);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().renew(user(), KEY_ID.toString()));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        verifyNoInteractions(gravitee);
    }

    @Test
    void renewFailureIsABadGatewayAndChangesNothing() throws Exception {
        GraviteeApiKey old = keyOf(application(), KEY_ID);
        lockable(old);
        when(gravitee.renewApiKey(APP_ID)).thenThrow(new IOException("Gravitee refused"));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().renew(user(), KEY_ID.toString()));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertNull(old.getReplacedAt());
        verify(apiKeyRepository, never()).save(any());
    }
}
