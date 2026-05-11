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
@Table(name = "integration_method_connector",
    uniqueConstraints = @UniqueConstraint(name = "uq_imc_met_id_met_rev",
        columnNames = {"integ_method_id", "integ_method_revision"}))
@Getter @Setter
@Accessors(chain = true)
public class IntegrationMethodConnector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumns({
        @JoinColumn(name = "integ_method_id", referencedColumnName = "id", nullable = false),
        @JoinColumn(name = "integ_method_revision", referencedColumnName = "revision", nullable = false)
    })
    private IntegrationMethod integrationMethod;

    @ManyToOne
    @JoinColumn(name = "connector_id", nullable = false)
    private Connector connector;

    @Column(name = "connector_minversion", nullable = false)
    private String connectorMinVersion;

    @Column(name = "connector_maxversion")
    private String connectorMaxVersion;
}
