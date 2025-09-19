/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/**
 * Created by Dominik.
 */
@Entity
@Table(name = "implementation_tag")
@Getter @Setter
public class ImplementationTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private int id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @OneToMany(mappedBy = "implementationTag", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ImplementationImplementationTag> implementationImplementationTags;
}
