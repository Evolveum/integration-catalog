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
import java.util.UUID;

@Entity
@Table(name = "integration_method")
@IdClass(IntegrationMethodId.class)
@Getter @Setter
@Accessors(chain = true)
public class IntegrationMethod implements Persistable<UUID> {

    @Id
    private UUID id;

    @Id
    private String revision;

    @Transient
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @PrePersist
    void assignIdIfMissing() {
        // A new revision of an existing method keeps that method's id (set explicitly);
        // a genuinely new method has no id yet, so we generate one here.
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @ManyToOne
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "int_method_int_method_type",
            joinColumns = {
                    @JoinColumn(name = "integration_method_id", referencedColumnName = "id"),
                    @JoinColumn(name = "integration_method_revision", referencedColumnName = "revision")
            },
            inverseJoinColumns = @JoinColumn(name = "integration_method_type_id", referencedColumnName = "id")
    )
    private List<IntegrationMethodType> integMethodTypes = new ArrayList<>();

    @Column(name = "display_name")
    private String displayName;

    private String description;
    @Column(columnDefinition = "TEXT")
    private String tutorial;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "midpoint_minversion")
    private Integer midpointMinVersionId;

    @Column(name = "midpoint_maxversion")
    private Integer midpointMaxVersionId;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "lifecycle_state", columnDefinition = "LifecycleType", nullable = false)
    private LifecycleType lifecycleState;

    private String author;
    private String maintainer;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updated;

    @Column(name = "app_version")
    private String appVersion;

    /** Username of the user who approved or rejected this in-review revision. */
    @Column(name = "reviewed_by")
    private String reviewedBy;

    @OneToMany(mappedBy = "integrationMethod", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IntegrationMethodCapability> capabilities = new ArrayList<>();

    @OneToMany(mappedBy = "integrationMethod", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IntegrationMethodConnector> connectors = new ArrayList<>();
}
