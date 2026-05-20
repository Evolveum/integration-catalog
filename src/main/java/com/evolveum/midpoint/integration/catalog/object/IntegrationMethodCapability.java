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
@Table(name = "integration_method_capability")
@Getter @Setter
@Accessors(chain = true)
public class IntegrationMethodCapability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "object_class", nullable = false)
    private String objectClass;

    @ManyToOne
    @JoinColumns({
        @JoinColumn(name = "integ_method_id", referencedColumnName = "id"),
        @JoinColumn(name = "integ_method_revision", referencedColumnName = "revision")
    })
    private IntegrationMethod integrationMethod;

    @OneToMany(mappedBy = "integrationMethodCapability", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IntegrationMethodCapabilityItem> items = new ArrayList<>();
}
