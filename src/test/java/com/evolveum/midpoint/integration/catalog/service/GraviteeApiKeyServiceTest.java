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

import static java.util.UUID.fromString;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.mockito.Mockito.when;

/** Unit tests for the API key creation rules and for the one-application-per-key model. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraviteeApiKeyServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final List<GraviteeProperties.Api> APIS = List.of(new GraviteeProperties.Api("api-1", "plan-1"));
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

    private static GraviteeProperties configured() {
        return new GraviteeProperties("http://localhost:8083", null, null, APIS, "token", TIMEOUT);
    }

    @Test
    void unconfiguredGraviteeIsServiceUnavailableAndNothingIsCalled() {
        ApiKeyService service = serviceWith(new GraviteeProperties(null, null, null, null, null, TIMEOUT));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.create(user(), new CreateApiKeyRequestDto("My key", null)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        verify(applicationRepository, never()).save(any());
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
        when(gravitee.createApplication("olivia", "My key")).thenReturn("app-1");
        when(createSubscriptionForDefaultAPI("app-1")).thenReturn("sub-1");
        when(gravitee.fetchApiKey(fromString("app-1"))).thenReturn(new GraviteeClient.ApiKeyMaterial("key-1", "the-secret"));

        CreatedApiKeyDto created = serviceWith(configured())
                .create(user(), new CreateApiKeyRequestDto("My key", null));

        assertEquals("the-secret", created.value());
        assertTrue(created.expirationAccepted());

        ArgumentCaptor<GraviteeApplication> saved = ArgumentCaptor.forClass(GraviteeApplication.class);
        verify(applicationRepository).save(saved.capture());
        assertEquals("My key", saved.getValue().getName());
        assertEquals("olivia", saved.getValue().getOwnerUsername());
        assertEquals("app-1", saved.getValue().getId());
        assertEquals("sub-1", saved.getValue().getSubscriptions().getFirst().getId());
        assertEquals("key-1", saved.getValue().getApiKeys().getFirst().getId());
        // Only the last four characters are kept, to tell a renewed key from its predecessor.
        assertEquals("cret", saved.getValue().getApiKeys().getFirst().getKeyHint());
        // The value is never part of what is stored.
        assertNull(saved.getValue().getApiKeys().getFirst().getExpiresAt());
    }

    private String createSubscriptionForDefaultAPI(String applicationId) throws IOException, InterruptedException {
        return gravitee.createSubscription(applicationId, APIS.getFirst().id(), APIS.getFirst().planId());
    }

    /** Gravitee refuses a second subscription of one application to one plan. */
    @Test
    void everyKeyGetsItsOwnApplication() throws Exception {
        when(gravitee.createApplication("olivia", "Second")).thenReturn("app-2");
        when(createSubscriptionForDefaultAPI("app-2" )).thenReturn("sub-2");
        when(gravitee.fetchApiKey(fromString("app-2"))).thenReturn(new GraviteeClient.ApiKeyMaterial("key-2", "another"));

        serviceWith(configured()).create(user(), new CreateApiKeyRequestDto("Second", null));

        verify(gravitee).createApplication("olivia", "Second");
        verify(gravitee).createSubscription("app-2", APIS.getFirst().id(), APIS.getFirst().planId());
    }

    /** A key Gravitee refuses to expire is kept, but the row must not claim an expiration. */
    @Test
    void refusedExpirationIsReportedAndNotStored() throws Exception {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        when(gravitee.createApplication(anyString(), anyString())).thenReturn("app-1");
        when(createSubscriptionForDefaultAPI("app-1")).thenReturn("sub-1");
        when(gravitee.fetchApiKey(fromString("app-1"))).thenReturn(new GraviteeClient.ApiKeyMaterial("key-1", "secret"));
        when(gravitee.expireSubscription(eq("sub-1"), APIS.getFirst().id(), any())).thenReturn(false);

        CreatedApiKeyDto created = serviceWith(configured())
                .create(user(), new CreateApiKeyRequestDto("My key", expiresAt));

        assertEquals(false, created.expirationAccepted());
        ArgumentCaptor<GraviteeApplication> saved = ArgumentCaptor.forClass(GraviteeApplication.class);
        verify(applicationRepository).save(saved.capture());
        assertNull(saved.getValue().getApiKeys().getFirst().getExpiresAt());
    }

    @Test
    void graviteeFailureIsABadGatewayAndStoresNothing() throws Exception {
        when(gravitee.createApplication(anyString(), anyString())).thenReturn("app-1");
        when(createSubscriptionForDefaultAPI("app-1")).thenThrow(new IOException("Gravitee refused"));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).create(user(), new CreateApiKeyRequestDto("My key", null)));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        verify(applicationRepository, never()).save(any());
    }

    // ---- revoke ----

    private GraviteeApiKey ownedKey(UUID id) {
        GraviteeApiKey key = new GraviteeApiKey();
        key.setId(id);
        GraviteeApplication application = new GraviteeApplication();
        application.setOwnerUsername("olivia");
        application.setName("qwe");
        key.setApplication(application);
        GraviteeSubscription subscription = new GraviteeSubscription();
        subscription.setId(fromString("sub-1"));
        subscription.setApiId(fromString(APIS.getFirst().id()));
        application.setSubscriptions(List.of());
        return key;
    }

    /** Gravitee must go first: the reverse order would show a key as dead while it still works. */
    @Test
    void revokeClosesTheSubscriptionBeforeStampingTheRow() throws Exception {
        UUID id = UUID.randomUUID();
        GraviteeApiKey key = ownedKey(id);
        when(apiKeyRepository.findByIdForUpdate(id)).thenReturn(Optional.of(key));

        serviceWith(configured()).revoke(user(), id.toString());

        InOrder order = inOrder(gravitee, apiKeyRepository);
        order.verify(gravitee).closeSubscription(fromString("sub-1"), fromString(APIS.getFirst().id()));
        order.verify(apiKeyRepository).save(key);
        assertNotNull(key.getRevokedAt());
    }

    @Test
    void revokingTwiceChangesNothing() {
        UUID id = UUID.randomUUID();
        GraviteeApiKey key = ownedKey(id);
        key.setRevokedAt(Instant.now().minusSeconds(60));
        when(apiKeyRepository.findByIdForUpdate(id)).thenReturn(Optional.of(key));

        serviceWith(configured()).revoke(user(), id.toString());

        verify(apiKeyRepository, never()).save(any());
    }

    /** Somebody else's key is reported missing rather than forbidden. */
    @Test
    void revokingAKeyOfAnotherUserIsNotFound() {
        UUID id = UUID.randomUUID();
        GraviteeApiKey key = ownedKey(id);
        key.getApplication().setOwnerUsername("someone-else");
        when(apiKeyRepository.findByIdForUpdate(id)).thenReturn(Optional.of(key));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).revoke(user(), id.toString()));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
    }

    @Test
    void revokingAnUnknownIdIsNotFound() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).revoke(user(), "not-a-uuid"));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
    }

    /** During the grace period the old key can be revoked alone, without touching the new one. */
    @Test
    void revokingTheReplacedKeyLeavesTheNewOneWorking() throws Exception {
        GraviteeApiKey old = ownedKey(UUID.randomUUID());
        old.setId(fromString("key-1"));
        old.setReplacedAt(Instant.now().minusSeconds(60));
        old.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
        GraviteeApiKey renewed = ownedKey(UUID.randomUUID());
        renewed.setId(fromString("key-2"));
        when(apiKeyRepository.findByIdForUpdate(old.getId())).thenReturn(Optional.of(old));

        serviceWith(configured()).revoke(user(), old.getId().toString());

        verify(gravitee).revokeApiKey(fromString("app-1"), fromString("key-1"));
        verify(gravitee, never()).closeSubscription(fromString(anyString()), fromString(APIS.getFirst().id()));
        assertNotNull(old.getRevokedAt());
        assertNull(renewed.getRevokedAt());
    }

    // ---- list ----

    /** A key revoked in the Gravitee console must not keep showing as active. */
    @Test
    void listFoldsInARevocationMadeInGravitee() throws Exception {
        Instant revokedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        GraviteeApiKey key = ownedKey(UUID.randomUUID());
        key.setId(fromString("key-1"));
        GraviteeApplication application = new GraviteeApplication();
        application.setId(fromString("app-1"));
        application.getApiKeys().add(key);
        when(applicationRepository.findByOwnerUsername(user().getName())).thenReturn(List.of(application));
        when(gravitee.listApiKeys(fromString("app-1"))).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", null, true, revokedAt)));

        List<ApiKeyDto> listed = serviceWith(configured()).list(user());

        assertEquals(revokedAt, listed.get(0).revokedAt());
        verify(apiKeyRepository).save(key);
    }

    /** Covers an expiration Gravitee applied although its answer never reached the catalog. */
    @Test
    void listTakesAnEarlierExpirationFromGravitee() throws Exception {
        Instant graviteeEnd = Instant.now().plus(1, ChronoUnit.DAYS);
        GraviteeApiKey key = ownedKey(UUID.randomUUID());
        key.setId(fromString("key-1"));
        GraviteeApplication application = new GraviteeApplication();
        application.setId(fromString("app-1"));
        application.getApiKeys().add(key);
        when(applicationRepository.findByOwnerUsername(user().getName())).thenReturn(List.of(application));
        when(gravitee.listApiKeys(fromString("app-1"))).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", graviteeEnd, false, null)));

        serviceWith(configured()).list(user());

        assertEquals(graviteeEnd, key.getExpiresAt());
        assertNull(key.getRevokedAt());
    }

    /** Reconciling only ever shortens a key's life. */
    @Test
    void listKeepsAnEarlierRecordedExpiration() throws Exception {
        Instant recordedEnd = Instant.now().plus(1, ChronoUnit.HOURS);
        GraviteeApiKey key = ownedKey(UUID.randomUUID());
        key.setId(fromString("key-1"));
        key.setExpiresAt(recordedEnd);
        GraviteeApplication application = new GraviteeApplication();
        application.setId(fromString("app-1"));
        application.getApiKeys().add(key);
        when(applicationRepository.findByOwnerUsername(user().getName())).thenReturn(List.of(application));
        when(gravitee.listApiKeys(fromString("sub-1"))).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", Instant.now().plus(5, ChronoUnit.DAYS), false, null)));

        serviceWith(configured()).list(user());

        assertEquals(recordedEnd, key.getExpiresAt());
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void listSkipsKeysThatAreNoLongerWorking() throws Exception {
        GraviteeApiKey key = ownedKey(UUID.randomUUID());
        key.setId(fromString("key-1"));
        key.setRevokedAt(Instant.now().minusSeconds(60));
        GraviteeApplication application = new GraviteeApplication();
        application.setId(fromString("app-1"));
        application.getApiKeys().add(key);
        when(applicationRepository.findByOwnerUsername(user().getName())).thenReturn(List.of(application));

        serviceWith(configured()).list(user());

        verify(gravitee, never()).listApiKeys(fromString(anyString()));
    }

    /** An unreachable Gravitee must not break the settings page. */
    @Test
    void listShowsTheRecordedStateWhenGraviteeCannotBeRead() throws Exception {
        GraviteeApiKey key = ownedKey(UUID.randomUUID());
        key.setId(fromString("key-1"));
        GraviteeApplication application = new GraviteeApplication();
        application.setId(fromString("app-1"));
        application.getApiKeys().add(key);
        when(applicationRepository.findByOwnerUsername(user().getName())).thenReturn(List.of(application));
        when(gravitee.listApiKeys(fromString("sub-1"))).thenThrow(new IOException("Gravitee refused"));

        List<ApiKeyDto> listed = serviceWith(configured()).list(user());

        assertEquals(1, listed.size());
        assertNull(listed.get(0).revokedAt());
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void listWithoutGraviteeConfiguredDoesNotCallIt() throws Exception {
        GraviteeApiKey key = ownedKey(UUID.randomUUID());
        GraviteeApplication application = new GraviteeApplication();
        application.setId(fromString("app-1"));
        application.getApiKeys().add(key);
        when(applicationRepository.findByOwnerUsername(user().getName())).thenReturn(List.of(application));

        serviceWith(new GraviteeProperties(null, null, null, null, null, TIMEOUT)).list(user());

        verify(gravitee, never()).listApiKeys(fromString(anyString()));
    }

    // ---- renew ----

    @Test
    void renewReplacesTheOldKeyAndReturnsTheNewValueOnce() throws Exception {
        Instant graceEnd = Instant.now().plus(2, ChronoUnit.HOURS);
        GraviteeApiKey old = ownedKey(UUID.randomUUID());
        old.setId(fromString("key-1"));
        GraviteeApplication application = new GraviteeApplication();
        application.setId(fromString("app-1"));
        application.getApiKeys().add(old);
        when(apiKeyRepository.findByIdForUpdate(old.getId())).thenReturn(Optional.of(old));
        when(apiKeyRepository.findById(fromString("key-1"))).thenReturn(Optional.of(old));
        when(gravitee.renewApiKey(fromString("sub-1"))).thenReturn(new GraviteeClient.ApiKeyMaterial("key-2", "new-secret-abcd"));
        when(gravitee.listApiKeys(fromString("sub-1"))).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", graceEnd, false, null),
                new GraviteeClient.GraviteeApiKey("key-2", null, false, null)));

        CreatedApiKeyDto renewed = serviceWith(configured()).renew(user(), old.getId().toString());

        assertEquals("new-secret-abcd", renewed.value());
        assertEquals("abcd", renewed.key().keyHint());
        assertNotNull(old.getReplacedAt());
        assertEquals(graceEnd, old.getExpiresAt());

        ArgumentCaptor<GraviteeApiKey> saved = ArgumentCaptor.forClass(GraviteeApiKey.class);
        verify(apiKeyRepository, times(2)).save(saved.capture());
        GraviteeApiKey stored = saved.getAllValues().get(1);
        assertEquals("key-2", stored.getId());
        assertEquals("sub-1", stored.getApplication().getSubscriptions().getFirst().getId());
        assertEquals("qwe", stored.getApplication().getName());
        assertNull(stored.getExpiresAt());
        assertNull(stored.getReplacedAt());
    }

    /** A rotation must not let access outlive the subscription the user chose to end. */
    @Test
    void renewKeepsTheSubscriptionEnd() throws Exception {
        Instant subscriptionEnd = Instant.now().plus(1, ChronoUnit.HOURS);
        GraviteeApiKey old = ownedKey(UUID.randomUUID());
        old.setId(fromString("key-1"));
        old.setExpiresAt(subscriptionEnd);
        when(apiKeyRepository.findByIdForUpdate(old.getId())).thenReturn(Optional.of(old));
        when(gravitee.renewApiKey(fromString("sub-1"))).thenReturn(new GraviteeClient.ApiKeyMaterial("key-2", "new-secret"));
        when(gravitee.listApiKeys(fromString("sub-1"))).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", Instant.now().plus(2, ChronoUnit.HOURS), false, null)));

        CreatedApiKeyDto renewed = serviceWith(configured()).renew(user(), old.getId().toString());

        assertEquals(subscriptionEnd, renewed.key().expiresAt());
        assertEquals(subscriptionEnd, old.getExpiresAt());
    }

    /** The new key already exists in Gravitee, so an unreadable key list must not lose it. */
    @Test
    void renewFallsBackToTheGracePeriodWhenTheKeysCannotBeRead() throws Exception {
        GraviteeApiKey old = ownedKey(UUID.randomUUID());
        old.setId(fromString("key-1"));
        when(apiKeyRepository.findByIdForUpdate(old.getId())).thenReturn(Optional.of(old));
        when(gravitee.renewApiKey(fromString("sub-1"))).thenReturn(new GraviteeClient.ApiKeyMaterial("key-2", "new-secret"));
        when(gravitee.listApiKeys(fromString("sub-1"))).thenThrow(new IOException("Gravitee refused"));

        CreatedApiKeyDto renewed = serviceWith(configured()).renew(user(), old.getId().toString());

        assertEquals("new-secret", renewed.value());
        assertNotNull(old.getExpiresAt());
        assertTrue(old.getExpiresAt().isAfter(Instant.now().plus(119, ChronoUnit.MINUTES)));
    }

    @Test
    void renewingARevokedKeyIsAConflict() {
        GraviteeApiKey key = ownedKey(UUID.randomUUID());
        key.setRevokedAt(Instant.now().minusSeconds(60));
        when(apiKeyRepository.findByIdForUpdate(key.getId())).thenReturn(Optional.of(key));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).renew(user(), key.getId().toString()));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
    }

    @Test
    void renewingAnAlreadyReplacedKeyIsAConflict() {
        GraviteeApiKey key = ownedKey(UUID.randomUUID());
        key.setReplacedAt(Instant.now().minusSeconds(60));
        key.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(apiKeyRepository.findByIdForUpdate(key.getId())).thenReturn(Optional.of(key));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).renew(user(), key.getId().toString()));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
    }

    /** A refusal from Gravitee must leave the row alone, so nothing claims a key is dead. */
    @Test
    void revokeFailureLeavesTheRowUnchanged() throws Exception {
        UUID id = UUID.randomUUID();
        GraviteeApiKey key = ownedKey(id);
        when(apiKeyRepository.findByIdForUpdate(id)).thenReturn(Optional.of(key));
        doThrow(new IOException("Gravitee refused")).when(gravitee).closeSubscription(fromString("sub-1"), fromString(APIS.getFirst().id()));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).revoke(user(), id.toString()));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertNull(key.getRevokedAt());
        verify(applicationRepository, never()).save(any());
    }
}
