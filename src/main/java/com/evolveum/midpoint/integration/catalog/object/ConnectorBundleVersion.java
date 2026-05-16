/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "connector_bundle_version")
@IdClass(ConnectorBundleVersionId.class)
@Getter @Setter
@Accessors(chain = true)
public class ConnectorBundleVersion implements Persistable<Integer> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "connector_bundle_version_seq")
    @SequenceGenerator(name = "connector_bundle_version_seq", sequenceName = "connector_bundle_version_id_seq", allocationSize = 1)
    private Integer id;

    @Id
    private String revision;

    @Transient
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

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

    @ManyToOne
    @JoinColumn(name = "connector_bundle_id")
    private ConnectorBundle connectorBundle;

    @Column(name = "bundle_version")
    private String bundleVersion;

    @Column(name = "browse_link")
    private String browseLink;

    @Column(name = "git_clone_ulr")
    private String gitCloneUrl;

    @Column(name = "path_to_project")
    private String pathToProject;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "build_framework", columnDefinition = "BuildFrameworkType", nullable = false)
    private BuildFrameworkType buildFramework;

    @Column(name = "commit_tag")
    private String commitTag;

    @OneToMany(mappedBy = "connectorBundleVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConnectorVersion> connectorVersions = new ArrayList<>();

    @OneToMany(mappedBy = "connectorBundleVersion", fetch = FetchType.LAZY)
    private List<Download> downloads = new ArrayList<>();
}
