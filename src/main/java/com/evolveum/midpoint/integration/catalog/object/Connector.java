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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "connector")
@Getter @Setter
@Accessors(chain = true)
public class Connector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String revision;
    private String author;
    private String maintainer;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updated;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "fully_qualified_class_name")
    private String fullyQualifiedClassName;

    /**
     * Id of the connector this row was copy-on-write cloned from (null for connectors created
     * directly). Set by the edit flow when a shared connector is cloned so a draft's changes stay
     * invisible until approval; the approve step uses it to fold a same-version metadata edit back
     * into the original connector, so every integration method linking it sees the correction.
     */
    @Column(name = "cloned_from")
    private Integer clonedFrom;

    @ManyToOne
    @JoinColumn(name = "connector_bundle_id", nullable = false)
    private ConnectorBundle connectorBundle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @OneToMany(mappedBy = "connector", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConnectorVersion> connectorVersions = new ArrayList<>();

    @OneToMany(mappedBy = "connector", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ConnectorConnectorTag> connectorConnectorTags;
}
