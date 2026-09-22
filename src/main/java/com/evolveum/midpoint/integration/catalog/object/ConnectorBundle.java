/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "connector_bundle")
@Getter @Setter
@Accessors(chain = true)
public class ConnectorBundle implements SetOwnership, GetOwnershipListMaintainer {

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

    @OneToOne
    @JoinColumn(name = "author")
    private Author author;

    @ManyToMany
    @JoinTable(
            name = "connector_bundle_maintainers",
            joinColumns = @JoinColumn(name = "connector_bundle_id"),
            inverseJoinColumns = @JoinColumn(name = "maintainer_id")
    )
    private List<Maintainer> maintainer = new ArrayList<>();

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
    @Column(name = "build_framework", columnDefinition = "BuildFrameworkType")
    private BuildFrameworkType buildFramework;

    @OneToMany(mappedBy = "connectorBundle", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Connector> connectors = new ArrayList<>();

    @OneToMany(mappedBy = "connectorBundle", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConnectorBundleVersion> bundleVersions = new ArrayList<>();

    /**
     * Adds one maintainer, as {@link SetOwnership} writes ownership one maintainer at a time.
     *
     * <p>Hidden from Jackson: it would otherwise be a second setter for the {@code maintainer}
     * property beside the list one, which is ambiguous enough that building a deserializer for
     * this class fails outright - and it is built, because a request DTO names an enum nested here.
     */
    @JsonIgnore
    @Override
    public ConnectorBundle setMaintainer(Maintainer maintainer) {
        if (!this.maintainer.contains(maintainer)) {
            this.maintainer.add(maintainer);
        }
        return this;
    }

    public ConnectorBundle setMaintainer(List<Maintainer> maintainer) {
        this.maintainer = maintainer;
        return this;
    }

    public static ConnectorBundle createConnectorBundleDraft(ConnectorBundle source) {
        ConnectorBundle clone = new ConnectorBundle();
        clone.setRevision(source.getRevision());
        clone.setAuthor(source.getAuthor());
        // The same maintainers, in a list of the clone's own: two entities sharing one collection
        // instance is what Hibernate refuses as a "shared reference to a collection".
        clone.setMaintainer(new ArrayList<>(source.getMaintainer()));
        clone.setLifecycleState(LifecycleType.IN_REVIEW);
        clone.setBundleName(source.getBundleName());
        clone.setDisplayName(source.getDisplayName());
        clone.setDescription(source.getDescription());
        clone.setFramework(source.getFramework());
        clone.setLicense(source.getLicense());
        clone.setTicketingLink(source.getTicketingLink());
        clone.setProjectHomepage(source.getProjectHomepage());
        clone.setGitCloneUrl(source.getGitCloneUrl());
        clone.setPathToProject(source.getPathToProject());
        clone.setBuildFramework(source.getBuildFramework());
        return clone;
    }

}
