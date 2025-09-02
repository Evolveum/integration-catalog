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
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "implementation")
@Getter @Setter
@Accessors(chain = true)
public class Implementation {

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
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "connector_bundle")
    private String connectorBundle;

    private String maintainer;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "FrameworkType")
    private FrameworkType framework;

    @Column(name = "ticketing_system_link")
    private String ticketingSystemLink;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "LicenseType")
    private LicenseType license;

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
