/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "connector_bundle_version")
@IdClass(ConnectorBundleVersionId.class)
@Getter @Setter
@Accessors(chain = true)
public class ConnectorBundleVersion implements SetOwnership, GetOwnershipListMaintainer, Persistable<Integer> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "connector_bundle_version_seq")
    @SequenceGenerator(name = "connector_bundle_version_seq", sequenceName = "connector_bundle_version_id_seq", allocationSize = 1)
    private Integer id;

    @Id
    private String revision;

    //TODO what is this?
    @Transient
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @OneToOne
    @JoinColumn(name = "author")
    private Author author;

    @ManyToMany
    @JoinTable(
            name = "connector_bundle_version_maintainers",
            joinColumns = {
                    @JoinColumn(
                            name = "connector_bundle_version_id",
                            referencedColumnName = "id"
                    ),
                    @JoinColumn(
                            name = "connector_bundle_version_revision",
                            referencedColumnName = "revision"
                    )
            },
            inverseJoinColumns = @JoinColumn(
                    name = "maintainer_id",
                    referencedColumnName = "id"
            )
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
    @Column(name = "build_framework", columnDefinition = "BuildFrameworkType")
    private BuildFrameworkType buildFramework;

    @Column(name = "commit_tag")
    private String commitTag;

    /**
     * Location of the connector build artifact (e.g. a Nexus JAR URL). Resolved at bundle-download
     * time and added to the ZIP. Nullable: a method whose connector has no artifact yields a bundle
     * without the JAR rather than a failure.
     */
    @Column(name = "artifact_url")
    private String artifactUrl;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @OneToMany(mappedBy = "connectorBundleVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConnectorVersion> connectorVersions = new ArrayList<>();

    @OneToMany(mappedBy = "connectorBundleVersion", fetch = FetchType.LAZY)
    private List<Download> downloads = new ArrayList<>();

    /**
     * Adds one maintainer, as {@link SetOwnership} writes ownership one maintainer at a time.
     *
     * <p>Hidden from Jackson: it would otherwise be a second setter for the {@code maintainer}
     * property beside the list one, which is ambiguous enough that building a deserializer for
     * this class fails outright - and it is built, this class being reachable from one that a
     * request DTO refers to.
     */
    @JsonIgnore
    @Override
    public ConnectorBundleVersion setMaintainer(Maintainer maintainer) {
        if (!this.maintainer.contains(maintainer)) {
            this.maintainer.add(maintainer);
        }
        return this;
    }

    public ConnectorBundleVersion setMaintainer(List<Maintainer> maintainer) {
        this.maintainer = maintainer;
        return this;
    }

    public static ConnectorBundleVersion createConnectorBundleVersionDraft(ConnectorBundleVersion source, ConnectorBundle bundle) {
        ConnectorBundleVersion clone = createConnectorBundleVersion(source, bundle);
        clone.setLifecycleState(LifecycleType.IN_REVIEW);
        return clone;
    }

    public static ConnectorBundleVersion createConnectorBundleVersion(ConnectorBundleVersion source, ConnectorBundle bundle) {
        ConnectorBundleVersion clone = new ConnectorBundleVersion();
        clone.setRevision(source.getRevision());
        clone.setAuthor(source.getAuthor());
        // Own list: Hibernate rejects two entities sharing one collection instance.
        clone.setMaintainer(new ArrayList<>(source.getMaintainer()));
        clone.setLifecycleState(source.getLifecycleState());
        clone.setConnectorBundle(bundle);
        clone.setBundleVersion(source.getBundleVersion());
        clone.setBrowseLink(source.getBrowseLink());
        clone.setGitCloneUrl(source.getGitCloneUrl());
        clone.setPathToProject(source.getPathToProject());
        clone.setBuildFramework(source.getBuildFramework());
        clone.setCommitTag(source.getCommitTag());
        clone.setArtifactUrl(source.getArtifactUrl());
        clone.setErrorMessage(source.getErrorMessage());
        return clone;
    }
}
