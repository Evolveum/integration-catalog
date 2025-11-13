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
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "display_name")
    private String displayName;

    @ManyToOne
    @JoinColumn(name = "connector_bundle_id", nullable = false)
    private ConnectorBundle connectorBundle;

    @ManyToOne
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @OneToMany(mappedBy = "implementation")
    private List<ImplementationVersion> implementationVersions;

    @OneToMany(mappedBy = "implementation", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ImplementationImplementationTag> implementationImplementationTags;

    public Implementation addImplementationVersion(ImplementationVersion implementationVersion) {
        if (implementationVersions == null) {
            implementationVersions = new ArrayList<>();
        }
        implementationVersion.setImplementation(this);
        implementationVersions.add(implementationVersion);
        return this;
    }
}
