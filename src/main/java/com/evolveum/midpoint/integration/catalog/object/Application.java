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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
    @Lob
    private byte[] logo;

    @Column(name = "risk_level")
    private String riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = true)
    private ApplicationLifecycleType lifecycleState;

    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "last_modified")
    private OffsetDateTime lastModified;

    @OneToMany(mappedBy = "application")
    private List<Implementation> implementations;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ApplicationApplicationTag> applicationApplicationTags;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ApplicationOrigin> applicationOrigins;

    public Application addImplementation(Implementation implementation) {
        if (implementations == null) {
            implementations = new ArrayList<>();
        }
        implementation.setApplication(this);
        implementations.add(implementation);
        return this;
    }
}
