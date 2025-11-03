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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Created by Dominik, Tomas.
 */
@Entity
@Table(name = "implementation_version")
@Getter @Setter
@Accessors(chain = true)
public class ImplementationVersion {

    public enum CapabilitiesType {
        READ,
        CREATE,
        MODIFY,
        DELETE
    }

    public enum ImplementationVersionLifecycleType {
        IN_PUBLISH_PROCESS,
        ACTIVE,
        DEPRECATED,
        ARCHIVED,
        WITH_ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String description;

    @Column(name = "system_version")
    private String systemVersion;

    private String author;

    @Column(name = "publish_date")
    private OffsetDateTime publishDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", columnDefinition = "varchar(64)")
    private ImplementationVersionLifecycleType lifecycleState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities")
    private String capabilitiesJson;

    @Column(name = "class_name")
    private String className;

    @ManyToOne
    @JoinColumn(name = "implementation_id", nullable = false)
    private Implementation implementation;

    @ManyToOne
    @JoinColumn(name = "bundle_version_id", nullable = false)
    private BundleVersion bundleVersion;

    //connection to Download
    @OneToMany(mappedBy = "implementationVersion", cascade = CascadeType.ALL, orphanRemoval = false)
    @OrderBy("downloadedAt DESC")
    private List<Download> downloads = new ArrayList<>();

    @Transient
    public long getDownloadCount() {
        return downloads == null ? 0 : downloads.size();
    }
}
