/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Entity
@Table(name = "organizations")
@Getter @Setter
@Accessors(chain = true)
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Shared contact address for the organization. Needed alongside {@link CatalogUser#getEmail()}
     * because an organization contributor publishes on behalf of their organization, so the
     * maintainer named on an integration method is not always a person.
     */
    private String email;
}
