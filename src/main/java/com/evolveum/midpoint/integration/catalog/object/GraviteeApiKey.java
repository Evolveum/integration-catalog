/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The catalog's side of one API key: its name, its owner and the Gravitee objects behind it.
 * Gravitee owns the key itself, and the value is never stored - it is shown once at creation.
 */
@Entity
@Table(name = "gravitee_api_key")
@Getter @Setter
public class GraviteeApiKey {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

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

    @ManyToOne
    @JoinColumn(name = "application_id", nullable = false)
    private GraviteeApplication application;

}
