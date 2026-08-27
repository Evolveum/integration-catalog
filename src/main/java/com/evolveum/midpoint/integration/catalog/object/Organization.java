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
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * An organization users publish on behalf of. This is the only identity data the catalog
 * stores: users and roles stay with the identity provider and reach the
 * application as OIDC token claims, but the organization claim carries the organization's
 * identifier only, so the display name has to live somewhere — here.
 */
@Entity
@Table(name = "organizations")
@Getter @Setter
@Accessors(chain = true)
public class Organization {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;
}
