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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Personal API keys of the logged-in user: Gravitee mints and owns them, this keeps the catalog's
 * side of each one.
 */
@Service
public class ApiKeyService {

    private static final int MAX_EXPIRATION_DAYS = 365;

    /** Gravitee's fixed renewal grace period; only assumed when its own answer cannot be read. */
    private static final Duration RENEWAL_GRACE = Duration.ofHours(2);

    private static final int HINT_LENGTH = 4;

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository repository;
    private final GraviteeClient gravitee;
    private final GraviteeProperties properties;

    public ApiKeyService(ApiKeyRepository repository, GraviteeClient gravitee, GraviteeProperties properties) {
        this.repository = repository;
        this.gravitee = gravitee;
        this.properties = properties;
    }

    /** Keys of the given user, newest first. Revoked and expired ones are included. */
    public List<ApiKeyDto> list(OidcUser user) {
        return repository.findByOwnerSubOrderByCreatedAtDesc(subjectOf(user)).stream()
                .map(ApiKeyDto::of)
                .toList();
    }

    /**
     * Creates a key and returns its value, the only time it is available. Nothing is written
     * locally unless Gravitee succeeded, so no row can point at a key that does not exist.
     */
    @Transactional
    public CreatedApiKeyDto create(OidcUser user, CreateApiKeyRequestDto request) {
        requireConfigured();
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The key needs a name.");
        }
        Instant expiresAt = validExpiration(request.expiresAt());

        String subject = subjectOf(user);
        String username = user.getPreferredUsername() != null ? user.getPreferredUsername() : subject;

        try {
            // One application per key: Gravitee refuses a second live subscription of the same
            // application to the same plan, so a shared per-user application would allow one key.
            String applicationId = gravitee.createApplication(username, subject, name);

            String subscriptionId = gravitee.createSubscription(applicationId);
            GraviteeClient.ApiKeyMaterial material = gravitee.fetchApiKey(subscriptionId);
            boolean expirationAccepted = expiresAt == null
                    || gravitee.expireSubscription(subscriptionId, expiresAt);

            ApiKey key = new ApiKey();
            key.setId(UUID.randomUUID());
            key.setName(name);
            key.setOwnerSub(subject);
            key.setOwnerUsername(username);
            key.setGraviteeApplicationId(applicationId);
            key.setGraviteeSubscriptionId(subscriptionId);
            key.setGraviteeApiKeyId(material.id());
            key.setKeyHint(hintOf(material.value()));
            key.setCreatedAt(Instant.now());
            key.setExpiresAt(expirationAccepted ? expiresAt : null);
            repository.save(key);

            return new CreatedApiKeyDto(ApiKeyDto.of(key), material.value(), expirationAccepted);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The API key could not be created: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The API key creation was interrupted.", e);
        }
    }

    /**
     * Replaces one of the caller's working keys with a new key on the same subscription and returns
     * its value, the only time it is available. The old key keeps working for Gravitee's grace
     * period and stays listed as replaced; the new one ends when the subscription does.
     */
    @Transactional
    public CreatedApiKeyDto renew(OidcUser user, String id) {
        requireConfigured();
        ApiKey old = ownKey(subjectOf(user), id);
        Instant now = Instant.now();
        if (!isWorking(old, now) || old.getReplacedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an active key that has not been replaced yet can be rotated.");
        }
        String subscriptionId = old.getGraviteeSubscriptionId();
        // The newest key carries the subscription's end: a renewal does not extend how long access lasts.
        Instant subscriptionEnd = old.getExpiresAt();

        GraviteeClient.ApiKeyMaterial material;
        try {
            material = gravitee.renewApiKey(subscriptionId);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The API key could not be rotated: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The rotation was interrupted.", e);
        }

        // Gravitee has just given every working key of the subscription an end; record it.
        Map<String, Instant> graceEnds = graceEnds(subscriptionId);
        for (ApiKey sibling : repository.findByGraviteeSubscriptionId(subscriptionId)) {
            if (!isWorking(sibling, now)) {
                continue;
            }
            Instant graceEnd = graceEnds.getOrDefault(sibling.getGraviteeApiKeyId(), now.plus(RENEWAL_GRACE));
            sibling.setExpiresAt(earliest(graceEnd, subscriptionEnd));
            if (sibling.getReplacedAt() == null) {
                sibling.setReplacedAt(now);
            }
            repository.save(sibling);
        }

        ApiKey renewed = new ApiKey();
        renewed.setId(UUID.randomUUID());
        renewed.setName(old.getName());
        renewed.setOwnerSub(old.getOwnerSub());
        renewed.setOwnerUsername(old.getOwnerUsername());
        renewed.setGraviteeApplicationId(old.getGraviteeApplicationId());
        renewed.setGraviteeSubscriptionId(subscriptionId);
        renewed.setGraviteeApiKeyId(material.id());
        renewed.setKeyHint(hintOf(material.value()));
        renewed.setCreatedAt(now);
        renewed.setExpiresAt(earliest(material.expireAt(), subscriptionEnd));
        repository.save(renewed);

        return new CreatedApiKeyDto(ApiKeyDto.of(renewed), material.value(), true);
    }

    /**
     * Revokes one of the caller's own keys and leaves the others of its subscription working - a
     * renewed key and the one it replaced share one. The subscription itself is closed only with
     * its last working key. Gravitee goes first: the reverse order would show a key as dead while
     * it still opens doors. Revoking twice is not an error.
     */
    @Transactional
    public void revoke(OidcUser user, String id) {
        requireConfigured();
        ApiKey key = ownKey(subjectOf(user), id);
        if (key.getRevokedAt() != null) {
            return;
        }
        Instant now = Instant.now();
        String subscriptionId = key.getGraviteeSubscriptionId();
        List<ApiKey> otherWorking = repository.findByGraviteeSubscriptionId(subscriptionId).stream()
                .filter(other -> !other.getId().equals(key.getId()) && isWorking(other, now))
                .toList();
        boolean closeSubscription = otherWorking.isEmpty() || key.getGraviteeApiKeyId() == null;
        try {
            if (closeSubscription) {
                gravitee.closeSubscription(subscriptionId);
            } else {
                gravitee.revokeApiKey(subscriptionId, key.getGraviteeApiKeyId());
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The API key could not be revoked: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The revocation was interrupted.", e);
        }
        key.setRevokedAt(now);
        repository.save(key);
        if (closeSubscription) {
            // A closed subscription takes every key of it along.
            for (ApiKey other : otherWorking) {
                other.setRevokedAt(now);
                repository.save(other);
            }
        }
    }

    /**
     * When Gravitee says each key of the subscription stops working. Best-effort: the renewal already
     * happened, so an unreadable answer falls back to the documented grace period rather than
     * losing the new key.
     */
    private Map<String, Instant> graceEnds(String subscriptionId) {
        Map<String, Instant> ends = new HashMap<>();
        try {
            for (GraviteeClient.ApiKeyState state : gravitee.listApiKeys(subscriptionId)) {
                if (state.id() != null && state.expireAt() != null) {
                    ends.put(state.id(), state.expireAt());
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("Could not read the keys of subscription {} after renewing it; assuming the {} grace period.",
                    subscriptionId, RENEWAL_GRACE, e);
        }
        return ends;
    }

    /** Says so plainly, rather than letting the call reach Gravitee without an api id or a token. */
    private void requireConfigured() {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "API key management is not configured - set the gravitee.* properties.");
        }
    }

    /** Somebody else's key is reported missing rather than forbidden: it is none of their business. */
    private ApiKey ownKey(String subject, String id) {
        UUID keyId;
        try {
            keyId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such API key.", e);
        }
        return repository.findById(keyId)
                .filter(key -> subject.equals(key.getOwnerSub()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such API key."));
    }

    /** The expiration the caller asked for, refused when past or beyond the cap. */
    private Instant validExpiration(Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The expiration must be in the future.");
        }
        if (expiresAt.isAfter(now.plusSeconds(MAX_EXPIRATION_DAYS * 24L * 3600L))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A key may live at most " + MAX_EXPIRATION_DAYS + " days.");
        }
        return expiresAt;
    }

    private static boolean isWorking(ApiKey key, Instant now) {
        return key.getRevokedAt() == null && (key.getExpiresAt() == null || key.getExpiresAt().isAfter(now));
    }

    /** The earlier of two instants, where null means "never". */
    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    private static String hintOf(String value) {
        return value.length() <= HINT_LENGTH ? value : value.substring(value.length() - HINT_LENGTH);
    }

    private String subjectOf(OidcUser user) {
        if (user == null || user.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API keys need a logged-in user.");
        }
        return user.getSubject();
    }
}
