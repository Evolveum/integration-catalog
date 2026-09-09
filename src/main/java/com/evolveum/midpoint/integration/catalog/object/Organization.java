/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * An organization users publish on behalf of. Exists because the organization claim carries the
 * alias only, leaving the display name nowhere else to live.
 */
@Entity
@Table(name = "organizations")
@Getter @Setter
@Accessors(chain = true)
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /**
     * The value the OIDC organization claim carries; the provider's to change, and null for an
     * organization that predates the provider and has not been given one yet.
     */
    @Column(unique = true)
    private String alias;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;
}
