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
        CREATE("Create"),
        GET("Get"),
        UPDATE("Update"),
        DELETE("Delete"),
        TEST("Test"),
        SCRIPT_ON_CONNECTOR("ScriptOnConnector"),
        SCRIPT_ON_RESOURCE("ScriptOnResource"),
        AUTHENTICATION("Authentication"),
        SEARCH("Search"),
        VALIDATE("Validate"),
        SYNC("Sync"),
        LIVE_SYNC("LiveSync"),
        SCHEMA("Schema"),
        DISCOVER_CONFIGURATION("DiscoverConfiguration"),
        RESOLVE_USERNAME("ResolveUsername"),
        PARTIAL_SCHEMA("PartialSchema"),
        COMPLEX_UPDATE_DELTA("ComplexUpdateDelta"),
        UPDATE_DELTA("UpdateDelta");


        public final String value;

        CapabilitiesType(String value){
            this.value = value;
        }
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

    @Column(name = "publish_date")
    private OffsetDateTime publishDate;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "lifecycle_state", columnDefinition = "implementationVersionLifecycleType")
    private ImplementationVersionLifecycleType lifecycleState;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "build_framework", columnDefinition = "buildFrameworkType", nullable = false)
    private BuildFrameworkType buildFramework;

    @Column(name = "capabilities", columnDefinition = "capabilitiesType[]")
    private CapabilitiesType[] capabilities;

    @Column(name = "connid_version")
    private String connidVersion;

    @ManyToOne
    @JoinColumn(name = "implementation_id", nullable = false)
    private Implementation implementation;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    //connection to Download
    @OneToMany(mappedBy = "implementationVersion", cascade = CascadeType.ALL, orphanRemoval = false)
    @OrderBy("downloadedAt DESC")
    private List<Download> downloads = new ArrayList<>();

    @Transient
    public long getDownloadCount() {
        return downloads == null ? 0 : downloads.size();
    }
}
