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
@Table(name = "connector_version")
@IdClass(ConnectorVersionId.class)
@Getter @Setter
@Accessors(chain = true)
public class ConnectorVersion implements Persistable<Integer> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "connector_version_seq")
    @SequenceGenerator(name = "connector_version_seq", sequenceName = "connector_version_id_seq", allocationSize = 1)
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
    @JoinColumns({
        @JoinColumn(name = "connector_bundle_version_id", referencedColumnName = "id"),
        @JoinColumn(name = "connector_bundle_version_revision", referencedColumnName = "revision")
    })
    private ConnectorBundleVersion connectorBundleVersion;

    @ManyToOne
    @JoinColumn(name = "connector_id")
    private Connector connector;

    @Column(name = "fully_qualified_class_name")
    private String fullyQualifiedClassName;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @OneToMany(mappedBy = "connectorVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConnVersionCapability> capabilities = new ArrayList<>();
}
