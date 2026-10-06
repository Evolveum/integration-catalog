/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * The catalog's record of one Gravitee subscription: which API key it was made under and which
 * Gravitee API it opens. Gravitee still owns the subscription; this only links the ids.
 */
@Entity
@Table(name = "api_key_subscription")
@Getter @Setter
public class ApiKeySubscription {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Unique here and in Gravitee: a subscription belongs to one key. */
    @Column(name = "subscription_id", nullable = false, unique = true)
    private String subscriptionId;

    /** The Gravitee API the subscription was made for. */
    @Column(name = "api_id", nullable = false)
    private String apiId;
}