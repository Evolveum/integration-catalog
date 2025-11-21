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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bundle_version")
@Getter @Setter
@Accessors(chain = true)
public class BundleVersion {

    public enum BuildFrameworkType {
        MAVEN,
        GRADLE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "connector_bundle_id", nullable = false)
    private ConnectorBundle connectorBundle;

    @Column(name = "connector_version")
    private String connectorVersion;

    @Column(name = "browse_link")
    private String browseLink;

    @Column(name = "checkout_link")
    private String checkoutLink;

    @Column(name = "download_link")
    private String downloadLink;

    @Column(name = "connid_version")
    private String connidVersion;

    @Column(name = "released_date")
    private LocalDate releasedDate;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "build_framework", columnDefinition = "buildFrameworkType", nullable = false)
    private BuildFrameworkType buildFramework;

    @Column(name = "path_to_project", columnDefinition = "TEXT")
    private String pathToProject;

    @OneToMany(mappedBy = "bundleVersion")
    private List<ImplementationVersion> implementationVersions = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "connid_version", referencedColumnName = "version", insertable = false, updatable = false)
    private ConnidVersion connidVersionObject;
}
