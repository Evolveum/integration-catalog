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
@Table(name = "capability")
@Getter @Setter
@Accessors(chain = true)
public class Capability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "globality", length = 30)
    private String globality;
}
