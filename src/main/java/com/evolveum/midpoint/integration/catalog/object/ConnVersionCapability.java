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

@Entity
@Table(name = "conn_version_capability")
@Getter @Setter
@Accessors(chain = true)
public class ConnVersionCapability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "object_class", nullable = false)
    private String objectClass;

    @ManyToOne
    @JoinColumns({
        @JoinColumn(name = "conn_version_id", referencedColumnName = "id"),
        @JoinColumn(name = "conn_version_revision", referencedColumnName = "revision")
    })
    private ConnectorVersion connectorVersion;

    @OneToMany(mappedBy = "connVersionCapability", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConnVersionCapabilityItem> items = new ArrayList<>();
}
