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
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "connector_bundle")
@Getter @Setter
@Accessors(chain = true)
public class ConnectorBundle {

    public enum FrameworkType {
        JAVA_BASED,
        LOW_CODE
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

    private String revision;
    private String author;
    private String maintainer;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updated;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "lifecycle_state", columnDefinition = "LifecycleType", nullable = false)
    private LifecycleType lifecycleState;

    @Column(name = "bundle_name")
    private String bundleName;

    @Column(name = "display_name")
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "framework", columnDefinition = "FrameworkType", nullable = false)
    private FrameworkType framework;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "license", columnDefinition = "LicenseType", nullable = false)
    private LicenseType license;

    @Column(name = "ticketing_link")
    private String ticketingLink;

    @Column(name = "project_homepage")
    private String projectHomepage;

    @Column(name = "git_clone_ulr")
    private String gitCloneUrl;

    @Column(name = "path_to_project")
    private String pathToProject;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "build_framework", columnDefinition = "BuildFrameworkType", nullable = false)
    private BuildFrameworkType buildFramework;

    @OneToMany(mappedBy = "connectorBundle", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Connector> connectors = new ArrayList<>();

    @OneToMany(mappedBy = "connectorBundle", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConnectorBundleVersion> bundleVersions = new ArrayList<>();
}
