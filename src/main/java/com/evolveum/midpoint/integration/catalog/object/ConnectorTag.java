/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Entity
@Table(name = "connector_tag")
@Getter @Setter
public class ConnectorTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @OneToMany(mappedBy = "connectorTag", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ConnectorConnectorTag> connectorConnectorTags;
}
