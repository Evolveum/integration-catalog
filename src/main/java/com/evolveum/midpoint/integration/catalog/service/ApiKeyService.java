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
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Personal API keys of the logged-in user: Gravitee mints and owns them, this keeps the catalog's
 * side of each one.
 */
@Service
public class ApiKeyService {

    private static final int MAX_EXPIRATION_DAYS = 365;

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
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "API key management is not configured - set the gravitee.* properties.");
        }
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

    private String subjectOf(OidcUser user) {
        if (user == null || user.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API keys need a logged-in user.");
        }
        return user.getSubject();
    }
}
