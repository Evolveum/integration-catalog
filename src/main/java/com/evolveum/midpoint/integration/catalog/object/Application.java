/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import com.evolveum.midpoint.integration.catalog.dto.ApplicationTagDto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Created by Dominik, Tomas.
 */
@Entity
@Table(name = "application")
@Getter @Setter
@Accessors(chain = true)
public class Application {

    public enum ApplicationLifecycleType {
        REQUESTED,
        IN_PUBLISH_PROCESS,
        ACTIVE,
        WITH_ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    @Column(name = "display_name")
    private String displayName;

    private String description;

    //TODO - how to load logo - this way or with url somehow
    @Column(columnDefinition = "bytea")
    private byte[] logo;

    @Enumerated(EnumType.STRING)
    @JdbcType(value = PostgreSQLEnumJdbcType.class)
    @Column(name = "lifecycle_state", columnDefinition = "applicationLifecycleType")
    private ApplicationLifecycleType lifecycleState;

    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "last_modified")
    private OffsetDateTime lastModified;

    @OneToMany(mappedBy = "application", fetch = FetchType.EAGER)
    private List<Implementation> implementations;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ApplicationApplicationTag> applicationApplicationTags;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ApplicationOrigin> applicationOrigins;

    // Transient fields for receiving data from frontend (not persisted to database)
    @Transient
    private List<String> origins; // Country names from frontend

    @Transient
    private List<ApplicationTagDto> tags; // Tag DTOs from frontend

    public Application addImplementation(Implementation implementation) {
        if (implementations == null) {
            implementations = new ArrayList<>();
        }
        implementation.setApplication(this);
        implementations.add(implementation);
        return this;
    }
}
