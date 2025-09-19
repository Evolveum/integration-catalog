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

    public enum BuildFrameworkType {
        MAVEN,
        GRADLE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String description;

    @Column(name = "connector_version")
    private String connectorVersion;

    @Column(name = "browse_link")
    private String browseLink;

    @Column(name = "checkout_link")
    private String checkoutLink;

    @Column(name = "download_link")
    private String downloadLink;

    @Column(name = "system_version")
    private String systemVersion;

    private String author;

    @Column(name = "released_date")
    private LocalDate releasedDate;

    @Column(name = "publish_date", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime publishDate;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "lifecycle_state", columnDefinition = "implementationVersionLifecycleType")
    private ImplementationVersionLifecycleType lifecycleState;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "build_framework", columnDefinition = "buildFrameworkType")
    private BuildFrameworkType buildFramework;

    @Column(name = "connid_version")
    private String connidVersion;

    @ManyToOne
    @JoinColumn(name = "implementation_id", nullable = false)
    private Implementation implementation;

    @Column(name = "error_message")
    private String errorMessage;

    //connection to Downloads
    @OneToMany(mappedBy = "implementationVersion", cascade = CascadeType.ALL, orphanRemoval = false)
    @OrderBy("downloadedAt DESC")
    private List<Downloads> downloads = new ArrayList<>();

    @Transient
    public long getDownloadCount() {
        return downloads == null ? 0 : downloads.size();
    }
}
