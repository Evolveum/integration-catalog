/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Created by Dominik.
 */
@Entity
@Table(name = "implementation_implementation_tag")
@Getter @Setter
public class ImplementationImplementationTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "implementation_id", nullable = false)
    private Implementation implementation;

    @ManyToOne
    @JoinColumn(name = "tag_id", nullable = false)
    private ImplementationTag implementationTag;
}
