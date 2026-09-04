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
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
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
public class IntegrationMethod implements OwnedItem, Persistable<UUID> {

    @Id
    private UUID id;

    @Id
    private String revision;

    @Transient
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @PrePersist
    void assignDefaults() {
        // A new revision of an existing method keeps that method's id (set explicitly);
        // a genuinely new method has no id yet, so we generate one here.
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        // Default the creation time for genuinely new methods, but let a forked
        // revision inherit its source's created_at (set explicitly) so the method
        // keeps its original position when ordered by created_at.
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
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

    /**
     * Ownership as it stood when this row was written. A token only ever describes its own
     * bearer, so the uploader's organization, the maintaining organization and the
     * uploader's category are recorded here instead of being looked up per request.
     */
    @Column(name = "author_org_id")
    private String authorOrgId;

    /**
     * Set when an organization rather than a person maintains the item; {@link #maintainer}
     * then holds a username only. References organizations.id, so a rename of the
     * organization needs no change here.
     */
    @Column(name = "maintainer_org_id")
    private String maintainerOrgId;

    /** The uploader's catalog category at the time of writing: Evolveum, Partner or Community. */
    @Column(name = "author_category")
    private String authorCategory;

    /**
     * The uploader's own address, taken from their token when the row was written. Stamped for the
     * same reason as the columns above: a token describes only its bearer, so the address of the
     * person named in {@link #author} cannot be looked up afterwards. Null for rows written before
     * the column existed, and for anyone whose token carried no address.
     */
    @Column(name = "author_email")
    private String authorEmail;

    // Not @CreationTimestamp: a forked revision inherits its source's created_at
    // (see assignDefaults / createDraft) so a method keeps its original ordering.
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updated;

    @Column(name = "app_version")
    private String appVersion;

    /**
     * Username of the reviewer: set when a review is started (REVIEWING) and kept when the
     * revision is approved or rejected. Requires the reviewed_by column
     * (see config/sql/add_reviewing_state.sql for existing databases).
     */
    @Column(name = "reviewed_by")
    private String reviewedBy;

    /** Support portal work package for this revision; null when none was opened. Not inherited by a fork. */
    @Column(name = "support_ticket_id")
    private Integer supportTicketId;

    @OneToMany(mappedBy = "integrationMethod", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IntegrationMethodCapability> capabilities = new ArrayList<>();

    @OneToMany(mappedBy = "integrationMethod", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IntegrationMethodConnector> connectors = new ArrayList<>();
}
