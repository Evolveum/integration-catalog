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
import com.evolveum.midpoint.integration.catalog.object.ApiKey;
import com.evolveum.midpoint.integration.catalog.repository.ApiKeyRepository;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
class ApiKeyServiceTest {


    @Mock
    private ApiKeyRepository repository;

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
        return new ApiKeyService(repository, gravitee, properties);
    }

    private static GraviteeProperties configured() {
        return new GraviteeProperties("http://localhost:8083", null, null, "api-1", "plan-1", "token", null);
    }

    @Test
    void unconfiguredGraviteeIsServiceUnavailableAndNothingIsCalled() {
        ApiKeyService service = serviceWith(new GraviteeProperties(null, null, null, null, null, null, null));

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
        when(gravitee.createApplication("olivia", "My key")).thenReturn("app-1");
        when(gravitee.createSubscription("app-1")).thenReturn("sub-1");
        when(gravitee.fetchApiKey("sub-1")).thenReturn(new GraviteeClient.ApiKeyMaterial("key-1", "the-secret"));

        CreatedApiKeyDto created = serviceWith(configured())
                .create(user(), new CreateApiKeyRequestDto("My key", null));

        assertEquals("the-secret", created.value());
        assertTrue(created.expirationAccepted());

        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(saved.capture());
        assertEquals("My key", saved.getValue().getName());
        assertEquals("olivia", saved.getValue().getOwnerUsername());
        assertEquals("app-1", saved.getValue().getGraviteeApplicationId());
        assertEquals("sub-1", saved.getValue().getGraviteeSubscriptionId());
        assertEquals("key-1", saved.getValue().getGraviteeApiKeyId());
        // Only the last four characters are kept, to tell a renewed key from its predecessor.
        assertEquals("cret", saved.getValue().getKeyHint());
        // The value is never part of what is stored.
        assertNull(saved.getValue().getExpiresAt());
    }

    /** Gravitee refuses a second subscription of one application to one plan. */
    @Test
    void everyKeyGetsItsOwnApplication() throws Exception {
        when(gravitee.createApplication("olivia", "Second")).thenReturn("app-2");
        when(gravitee.createSubscription("app-2")).thenReturn("sub-2");
        when(gravitee.fetchApiKey("sub-2")).thenReturn(new GraviteeClient.ApiKeyMaterial("key-2", "another"));

        serviceWith(configured()).create(user(), new CreateApiKeyRequestDto("Second", null));

        verify(gravitee).createApplication("olivia", "Second");
        verify(gravitee).createSubscription("app-2");
    }

    /** A key Gravitee refuses to expire is kept, but the row must not claim an expiration. */
    @Test
    void refusedExpirationIsReportedAndNotStored() throws Exception {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        when(gravitee.createApplication(anyString(), anyString())).thenReturn("app-1");
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
        when(gravitee.createApplication(anyString(), anyString())).thenReturn("app-1");
        when(gravitee.createSubscription("app-1")).thenThrow(new IOException("Gravitee refused"));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).create(user(), new CreateApiKeyRequestDto("My key", null)));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        verify(repository, never()).save(any());
    }

    // ---- revoke ----

    /** A key of the caller's own: the owner is what tells it from somebody else's. */
    private ApiKey ownedKey(UUID id) {
        ApiKey key = new ApiKey();
        key.setId(id);
        key.setName("qwe");
        key.setOwnerUsername("olivia");
        key.setGraviteeSubscriptionId("sub-1");
        return key;
    }

    /** Gravitee must go first: the reverse order would show a key as dead while it still works. */
    @Test
    void revokeClosesTheSubscriptionBeforeStampingTheRow() throws Exception {
        UUID id = UUID.randomUUID();
        ApiKey key = ownedKey(id);
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(key));

        serviceWith(configured()).revoke(user(), id.toString());

        InOrder order = inOrder(gravitee, repository);
        order.verify(gravitee).closeSubscription("sub-1");
        order.verify(repository).save(key);
        assertNotNull(key.getRevokedAt());
    }

    @Test
    void revokingTwiceChangesNothing() {
        UUID id = UUID.randomUUID();
        ApiKey key = ownedKey(id);
        key.setRevokedAt(Instant.now().minusSeconds(60));
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(key));

        serviceWith(configured()).revoke(user(), id.toString());

        verify(repository, never()).save(any());
    }

    /** Somebody else's key is reported missing rather than forbidden. */
    @Test
    void revokingAKeyOfAnotherUserIsNotFound() {
        UUID id = UUID.randomUUID();
        ApiKey key = ownedKey(id);
        key.setOwnerUsername("ben");
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(key));

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
        ApiKey old = ownedKey(UUID.randomUUID());
        old.setGraviteeApiKeyId("key-1");
        old.setReplacedAt(Instant.now().minusSeconds(60));
        old.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
        ApiKey renewed = ownedKey(UUID.randomUUID());
        renewed.setGraviteeApiKeyId("key-2");
        when(repository.findByIdForUpdate(old.getId())).thenReturn(Optional.of(old));
        when(repository.findByGraviteeSubscriptionId("sub-1")).thenReturn(List.of(old, renewed));

        serviceWith(configured()).revoke(user(), old.getId().toString());

        verify(gravitee).revokeApiKey("sub-1", "key-1");
        verify(gravitee, never()).closeSubscription(anyString());
        assertNotNull(old.getRevokedAt());
        assertNull(renewed.getRevokedAt());
    }

    // ---- list ----

    /** A key revoked in the Gravitee console must not keep showing as active. */
    @Test
    void listFoldsInARevocationMadeInGravitee() throws Exception {
        Instant revokedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        ApiKey key = ownedKey(UUID.randomUUID());
        key.setGraviteeApiKeyId("key-1");
        when(repository.findByOwnerUsernameOrderByCreatedAtDesc(user().getName())).thenReturn(List.of(key));
        when(gravitee.listApiKeys("sub-1")).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", null, true, revokedAt)));

        List<ApiKeyDto> listed = serviceWith(configured()).list(user());

        assertEquals(revokedAt, listed.get(0).revokedAt());
        verify(repository).save(key);
    }

    /** Covers an expiration Gravitee applied although its answer never reached the catalog. */
    @Test
    void listTakesAnEarlierExpirationFromGravitee() throws Exception {
        Instant graviteeEnd = Instant.now().plus(1, ChronoUnit.DAYS);
        ApiKey key = ownedKey(UUID.randomUUID());
        key.setGraviteeApiKeyId("key-1");
        when(repository.findByOwnerUsernameOrderByCreatedAtDesc(user().getName())).thenReturn(List.of(key));
        when(gravitee.listApiKeys("sub-1")).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", graviteeEnd, false, null)));

        serviceWith(configured()).list(user());

        assertEquals(graviteeEnd, key.getExpiresAt());
        assertNull(key.getRevokedAt());
    }

    /** Reconciling only ever shortens a key's life. */
    @Test
    void listKeepsAnEarlierRecordedExpiration() throws Exception {
        Instant recordedEnd = Instant.now().plus(1, ChronoUnit.HOURS);
        ApiKey key = ownedKey(UUID.randomUUID());
        key.setGraviteeApiKeyId("key-1");
        key.setExpiresAt(recordedEnd);
        when(repository.findByOwnerUsernameOrderByCreatedAtDesc(user().getName())).thenReturn(List.of(key));
        when(gravitee.listApiKeys("sub-1")).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", Instant.now().plus(5, ChronoUnit.DAYS), false, null)));

        serviceWith(configured()).list(user());

        assertEquals(recordedEnd, key.getExpiresAt());
        verify(repository, never()).save(any());
    }

    @Test
    void listSkipsKeysThatAreNoLongerWorking() throws Exception {
        ApiKey key = ownedKey(UUID.randomUUID());
        key.setGraviteeApiKeyId("key-1");
        key.setRevokedAt(Instant.now().minusSeconds(60));
        when(repository.findByOwnerUsernameOrderByCreatedAtDesc(user().getName())).thenReturn(List.of(key));

        serviceWith(configured()).list(user());

        verify(gravitee, never()).listApiKeys(anyString());
    }

    /** An unreachable Gravitee must not break the settings page. */
    @Test
    void listShowsTheRecordedStateWhenGraviteeCannotBeRead() throws Exception {
        ApiKey key = ownedKey(UUID.randomUUID());
        key.setGraviteeApiKeyId("key-1");
        when(repository.findByOwnerUsernameOrderByCreatedAtDesc(user().getName())).thenReturn(List.of(key));
        when(gravitee.listApiKeys("sub-1")).thenThrow(new IOException("Gravitee refused"));

        List<ApiKeyDto> listed = serviceWith(configured()).list(user());

        assertEquals(1, listed.size());
        assertNull(listed.get(0).revokedAt());
        verify(repository, never()).save(any());
    }

    @Test
    void listWithoutGraviteeConfiguredDoesNotCallIt() throws Exception {
        ApiKey key = ownedKey(UUID.randomUUID());
        when(repository.findByOwnerUsernameOrderByCreatedAtDesc(user().getName())).thenReturn(List.of(key));

        serviceWith(new GraviteeProperties(null, null, null, null, null, null, null)).list(user());

        verify(gravitee, never()).listApiKeys(anyString());
    }

    // ---- renew ----

    @Test
    void renewReplacesTheOldKeyAndReturnsTheNewValueOnce() throws Exception {
        Instant graceEnd = Instant.now().plus(2, ChronoUnit.HOURS);
        ApiKey old = ownedKey(UUID.randomUUID());
        old.setGraviteeApiKeyId("key-1");
        old.setGraviteeApplicationId("app-1");
        when(repository.findByIdForUpdate(old.getId())).thenReturn(Optional.of(old));
        when(repository.findByGraviteeSubscriptionId("sub-1")).thenReturn(List.of(old));
        when(gravitee.renewApiKey("sub-1")).thenReturn(new GraviteeClient.ApiKeyMaterial("key-2", "new-secret-abcd"));
        when(gravitee.listApiKeys("sub-1")).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", graceEnd, false, null),
                new GraviteeClient.GraviteeApiKey("key-2", null, false, null)));

        CreatedApiKeyDto renewed = serviceWith(configured()).renew(user(), old.getId().toString());

        assertEquals("new-secret-abcd", renewed.value());
        assertEquals("abcd", renewed.key().keyHint());
        assertNotNull(old.getReplacedAt());
        assertEquals(graceEnd, old.getExpiresAt());

        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository, times(2)).save(saved.capture());
        ApiKey stored = saved.getAllValues().get(1);
        assertEquals("key-2", stored.getGraviteeApiKeyId());
        assertEquals("sub-1", stored.getGraviteeSubscriptionId());
        assertEquals("qwe", stored.getName());
        assertNull(stored.getExpiresAt());
        assertNull(stored.getReplacedAt());
    }

    /** A rotation must not let access outlive the subscription the user chose to end. */
    @Test
    void renewKeepsTheSubscriptionEnd() throws Exception {
        Instant subscriptionEnd = Instant.now().plus(1, ChronoUnit.HOURS);
        ApiKey old = ownedKey(UUID.randomUUID());
        old.setGraviteeApiKeyId("key-1");
        old.setExpiresAt(subscriptionEnd);
        when(repository.findByIdForUpdate(old.getId())).thenReturn(Optional.of(old));
        when(repository.findByGraviteeSubscriptionId("sub-1")).thenReturn(List.of(old));
        when(gravitee.renewApiKey("sub-1")).thenReturn(new GraviteeClient.ApiKeyMaterial("key-2", "new-secret"));
        when(gravitee.listApiKeys("sub-1")).thenReturn(List.of(
                new GraviteeClient.GraviteeApiKey("key-1", Instant.now().plus(2, ChronoUnit.HOURS), false, null)));

        CreatedApiKeyDto renewed = serviceWith(configured()).renew(user(), old.getId().toString());

        assertEquals(subscriptionEnd, renewed.key().expiresAt());
        assertEquals(subscriptionEnd, old.getExpiresAt());
    }

    /** The new key already exists in Gravitee, so an unreadable key list must not lose it. */
    @Test
    void renewFallsBackToTheGracePeriodWhenTheKeysCannotBeRead() throws Exception {
        ApiKey old = ownedKey(UUID.randomUUID());
        old.setGraviteeApiKeyId("key-1");
        when(repository.findByIdForUpdate(old.getId())).thenReturn(Optional.of(old));
        when(repository.findByGraviteeSubscriptionId("sub-1")).thenReturn(List.of(old));
        when(gravitee.renewApiKey("sub-1")).thenReturn(new GraviteeClient.ApiKeyMaterial("key-2", "new-secret"));
        when(gravitee.listApiKeys("sub-1")).thenThrow(new IOException("Gravitee refused"));

        CreatedApiKeyDto renewed = serviceWith(configured()).renew(user(), old.getId().toString());

        assertEquals("new-secret", renewed.value());
        assertNotNull(old.getExpiresAt());
        assertTrue(old.getExpiresAt().isAfter(Instant.now().plus(119, ChronoUnit.MINUTES)));
    }

    @Test
    void renewingARevokedKeyIsAConflict() {
        ApiKey key = ownedKey(UUID.randomUUID());
        key.setRevokedAt(Instant.now().minusSeconds(60));
        when(repository.findByIdForUpdate(key.getId())).thenReturn(Optional.of(key));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).renew(user(), key.getId().toString()));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
    }

    @Test
    void renewingAnAlreadyReplacedKeyIsAConflict() {
        ApiKey key = ownedKey(UUID.randomUUID());
        key.setReplacedAt(Instant.now().minusSeconds(60));
        key.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(repository.findByIdForUpdate(key.getId())).thenReturn(Optional.of(key));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).renew(user(), key.getId().toString()));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
    }

    /** A refusal from Gravitee must leave the row alone, so nothing claims a key is dead. */
    @Test
    void revokeFailureLeavesTheRowUnchanged() throws Exception {
        UUID id = UUID.randomUUID();
        ApiKey key = ownedKey(id);
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(key));
        doThrow(new IOException("Gravitee refused")).when(gravitee).closeSubscription("sub-1");

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> serviceWith(configured()).revoke(user(), id.toString()));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertNull(key.getRevokedAt());
        verify(repository, never()).save(any());
    }
}
