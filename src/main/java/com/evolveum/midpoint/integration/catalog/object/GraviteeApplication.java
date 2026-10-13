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
 * The catalog's side of one API key: Table with Application.
 * One application for one new API KEY created by GUI.
 */
@Entity
@Table(name = "gravitee_application")
@Getter @Setter
public class GraviteeApplication {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    /** Display copy, so a key can be attributed without asking the provider. */
    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GraviteeSubscription> subscriptions = new ArrayList<>();

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    private List<GraviteeApiKey> apiKeys = new ArrayList<>();
}
