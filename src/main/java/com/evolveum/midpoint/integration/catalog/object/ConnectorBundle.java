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
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "connector_bundle")
@Getter @Setter
@Accessors(chain = true)
public class ConnectorBundle {

    public enum FrameworkType {
        CONNID,
        SCIM_REST
    }

    public enum LicenseType {
        MIT,
        APACHE_2,
        BSD,
        EUPL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "bundle_name", nullable = false)
    private String bundleName;

    private String maintainer;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "framework", columnDefinition = "frameworkType", nullable = false)
    private FrameworkType framework;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "license", columnDefinition = "licenseType", nullable = false)
    private LicenseType license;

    @Column(name = "ticketing_system_link", columnDefinition = "TEXT")
    private String ticketingSystemLink;

    @OneToMany(mappedBy = "connectorBundle")
    private List<Implementation> implementations = new ArrayList<>();

    @OneToMany(mappedBy = "connectorBundle")
    private List<BundleVersion> bundleVersions = new ArrayList<>();
}
