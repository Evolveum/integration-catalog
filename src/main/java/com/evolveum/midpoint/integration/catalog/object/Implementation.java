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
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "implementation")
@Getter @Setter
@Accessors(chain = true)
public class Implementation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name")
    private String displayName;

    @ManyToOne
    @JoinColumn(name = "connector_bundle_id", nullable = false)
    private ConnectorBundle connectorBundle;

    @ManyToOne
    @JoinColumn(name = "application", nullable = false)
    private Application application;

    @OneToMany(mappedBy = "implementation")
    private List<ImplementationVersion> implementationVersions = new ArrayList<>();

    @OneToMany(mappedBy = "implementation", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ImplementationImplementationTag> implementationImplementationTags;
}
