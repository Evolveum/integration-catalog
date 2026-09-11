/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The catalog's side of one API key: its name, its owner and the Gravitee objects behind it.
 * Gravitee owns the key itself, and the value is never stored - it is shown once at creation.
 */
@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    /** The identity provider's {@code sub} claim: unlike the username, it survives a rename. */
    @Column(name = "owner_sub", nullable = false)
    private String ownerSub;

    /** Display copy, so a key can be attributed without asking the provider. */
    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    /** One per key: Gravitee allows an application only one live subscription per plan. */
    @Column(name = "gravitee_application_id", nullable = false)
    private String graviteeApplicationId;

    @Column(name = "gravitee_subscription_id", nullable = false)
    private String graviteeSubscriptionId;

    @Column(name = "gravitee_api_key_id")
    private String graviteeApiKeyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null means the key never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Last four characters of the value: tells a renewed key from its predecessor without storing it. */
    @Column(name = "key_hint")
    private String keyHint;

    /** When a renewal superseded this key; it keeps working until {@link #expiresAt}, Gravitee's grace period. */
    @Column(name = "replaced_at")
    private Instant replacedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwnerSub() {
        return ownerSub;
    }

    public void setOwnerSub(String ownerSub) {
        this.ownerSub = ownerSub;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public String getGraviteeApplicationId() {
        return graviteeApplicationId;
    }

    public void setGraviteeApplicationId(String graviteeApplicationId) {
        this.graviteeApplicationId = graviteeApplicationId;
    }

    public String getGraviteeSubscriptionId() {
        return graviteeSubscriptionId;
    }

    public void setGraviteeSubscriptionId(String graviteeSubscriptionId) {
        this.graviteeSubscriptionId = graviteeSubscriptionId;
    }

    public String getGraviteeApiKeyId() {
        return graviteeApiKeyId;
    }

    public void setGraviteeApiKeyId(String graviteeApiKeyId) {
        this.graviteeApiKeyId = graviteeApiKeyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getKeyHint() {
        return keyHint;
    }

    public void setKeyHint(String keyHint) {
        this.keyHint = keyHint;
    }

    public Instant getReplacedAt() {
        return replacedAt;
    }

    public void setReplacedAt(Instant replacedAt) {
        this.replacedAt = replacedAt;
    }
}
